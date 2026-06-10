package com.honghu.ai.assigment.rag.index.document.processer;

import com.honghu.ai.assigment.config.properties.AwsProperties;
import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.dto.record.ChunkCandidate;
import com.honghu.ai.assigment.dto.record.EventReservation;
import com.honghu.ai.assigment.dto.record.IngestionResult;
import com.honghu.ai.assigment.dto.record.S3UploadReceivedMessage;
import com.honghu.ai.assigment.entity.RagDocument;
import com.honghu.ai.assigment.entity.RagDocumentChunk;
import com.honghu.ai.assigment.entity.RagIngestionEvent;
import com.honghu.ai.assigment.manager.AwsManager;
import com.honghu.ai.assigment.rag.ChunkMetadata;
import com.honghu.ai.assigment.rag.index.cleaner.RagTextCleaner;
import com.honghu.ai.assigment.rag.index.splitter.RagTextSplitter;

import com.honghu.ai.assigment.rag.index.embdding.RagVectorIndexingService;
import com.honghu.ai.assigment.rag.monitor.RagIngestionStateService;
import com.honghu.ai.assigment.rag.index.parser.RagDocumentParser;
import com.honghu.ai.assigment.repository.RagDocumentChunkRepository;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档摄取处理器抽象基类。
 *
 * <p>把"绝大多数文件类型都通用"的 RAG 入库流程集中起来：</p>
 * <ol>
 *     <li>做消息级幂等预占，避免 SQS 至少一次投递造成重复处理</li>
 *     <li>校验事件、文件类型、对象大小等基础前置条件</li>
 *     <li>从 S3 下载文件，通过注入的 {@link RagDocumentParser} 解析为原始文本</li>
 *     <li>通过注入的 {@link RagTextCleaner} 清洗文本，再通过 {@link RagTextSplitter} 策略切成 chunk 并写入索引</li>
 * </ol>
 *
 * <h3>设计模式</h3>
 * <ul>
 *   <li><b>Template Method</b>：{@link #handleFileMessage} 定义不变的处理流程骨架</li>
 *   <li><b>Strategy</b>：{@link RagDocumentParser} 和 {@link RagTextCleaner} 是两个独立的策略接口，
 *       子类通过构造器注入具体实现，从而在不修改流程的前提下支持不同文件格式</li>
 *   <li><b>Adapter</b>：各 Parser 实现将 PDFBox / Apache POI / Tesseract 等外部库
 *       统一适配到 {@link RagDocumentParser} 接口</li>
 * </ul>
 *
 * <h3>事务策略（重要）</h3>
 * <p>本类<strong>不</strong>使用方法级 {@code @Transactional}，所有写库操作委托给
 * {@link RagIngestionStateService}，由它以 {@code REQUIRES_NEW} 方式独立 commit，
 * 使每一步状态变更立刻对前端可见，失败状态也不会被外层回滚吞掉。</p>
 *
 * <p><strong>注意：</strong>本类刻意保持<strong>无可变实例字段</strong>，
 * Spring 单例 Bean 在多线程并发消费 SQS 时不能把 event/document 放在成员变量里。</p>
 */
@Slf4j
public abstract class AbstractDocumentIngestionProcesser implements DocumentIngestionHandler {

    private final AwsManager awsManager;
    private final RagProperties ragProperties;
    private final RagIngestionStateService stateService;
    private final AwsProperties awsProperties;
    private final RagVectorIndexingService vectorIndexingService;
    private final RagDocumentChunkRepository chunkRepository;

    /** 本处理器使用的解析策略（由子类通过构造器注入）。 */
    private final RagDocumentParser parser;

    /** 本处理器使用的清洗策略（由子类通过构造器注入）。 */
    private final RagTextCleaner cleaner;

    /** 本处理器使用的切分策略（由子类通过构造器注入）。 */
    private final RagTextSplitter splitter;

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    /**
     * 子类构造器统一调用本父类构造器。
     *
     * @param parser  解析策略，负责将文件字节转换为原始文本（格式相关）
     * @param cleaner 清洗策略，负责对原始文本做规范化处理（格式相关或通用）
     * @param splitter 切分策略，负责将清洗后文本切成可索引的 chunk
     */
    protected AbstractDocumentIngestionProcesser(AwsManager awsManager,
                                                 RagProperties ragProperties,
                                                 RagIngestionStateService stateService,
                                                 AwsProperties awsProperties,
                                                 RagVectorIndexingService vectorIndexingService,
                                                 RagDocumentChunkRepository chunkRepository,
                                                 RagDocumentParser parser,
                                                 RagTextCleaner cleaner,
                                                 RagTextSplitter splitter) {
        this.awsManager = awsManager;
        this.ragProperties = ragProperties;
        this.stateService = stateService;
        this.awsProperties = awsProperties;
        this.vectorIndexingService = vectorIndexingService;
        this.chunkRepository = chunkRepository;
        this.parser = parser;
        this.cleaner = cleaner;
        this.splitter = Objects.requireNonNull(splitter, "splitter must not be null");
    }

    /**
     * RAG 摄取主流水（Template Method）。整条流程拆成 7 个步骤，每一步状态都通过
     * stateService 独立 commit，失败时由 catch 块统一兜底落 FAILED。
     *
     * <pre>
     *   ┌── (0) validateIncomingMessage：bucket 白名单 / 事件类型校验
     *   ├── (1) reserveEvent：消息级幂等预占（带 stale lock 夺锁）
     *   ├── (2) 早退判断：同版本对象已 INDEXED 直接 ack
     *   ├── (3) PARSING 阶段：fileType / supportedExtensions / 大小限制
     *   ├── (4) upsertDocumentProcessing：把文档主记录切到 PROCESSING
     *   ├── (5) DOWNLOADING + EXTRACTING：S3 拉文件 → parser 解析 → cleaner 清洗
     *   ├── (6) CHUNKING：按字符窗口 + 自然边界切块
     *   └── (7) INDEXING：替换 chunk 索引 + 终态 SUCCESS
     * </pre>
     */
    @Override
    public IngestionResult handleFileMessage(S3UploadReceivedMessage uploadEventmessage, String messageId) throws IOException {
        long startedAt = System.currentTimeMillis();
        log.info("RAG 文档摄取开始: messageId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, eventName={}, resolvedFileType={}",
                messageId,
                abbreviate(uploadEventmessage == null ? null : uploadEventmessage.deduplicationKey(), 48),
                uploadEventmessage == null ? null : uploadEventmessage.bucketName(),
                uploadEventmessage == null ? null : uploadEventmessage.fileId(),
                abbreviate(uploadEventmessage == null ? null : uploadEventmessage.sessionId(), 32),
                uploadEventmessage == null ? null : uploadEventmessage.eventName(),
                uploadEventmessage == null ? null : normalizeFileType(uploadEventmessage.resolvedFileType()));
        if (log.isDebugEnabled() && uploadEventmessage != null) {
            log.debug("RAG 文档摄取 objectKey: messageId={}, objectKey={}", messageId, uploadEventmessage.objectKey());
        }

        // ─── (0) 入参基础校验：RAG 全局开关 / bucket 白名单 / ObjectCreated 事件 ─────────
        IngestionResult preCheckResult = validateIncomingMessage(uploadEventmessage);
        if (preCheckResult != null) {
            logReservationOrValidationExit(uploadEventmessage, messageId, null, preCheckResult, startedAt, "pre-check");
            return preCheckResult;
        }

        // ─── (1) 消息级幂等预占。 ─────────────────────────────────────────────
        // SUCCESS/SKIPPED → 直接返回幂等跳过；
        // PROCESSING 且未超 stale 阈值 → retryLater；
        // PROCESSING 但已僵尸 → 强行夺锁；
        // FAILED → 翻回 PROCESSING 继续。
        EventReservation reservation = stateService.reserveEvent(uploadEventmessage, messageId);
        if (reservation.result() != null) {
            logReservationOrValidationExit(uploadEventmessage, messageId, reservation.event(), reservation.result(), startedAt, "reserve-event");
            return reservation.result();
        }
        Long eventId = reservation.event().getEventId();
        log.info("RAG 文档摄取预占成功: messageId={}, eventId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}",
                messageId, eventId,
                abbreviate(uploadEventmessage.deduplicationKey(), 48),
                uploadEventmessage.bucketName(),
                uploadEventmessage.fileId(),
                abbreviate(uploadEventmessage.sessionId(), 32));

        // 仅做读操作，无事务必要；用来在 (2) 做"同版本已索引"早退判断。
        RagDocument existingDocument = stateService.findDocument(uploadEventmessage).orElse(null);
        if (existingDocument != null && log.isDebugEnabled()) {
            log.debug("命中已有文档主记录: messageId={}, eventId={}, documentId={}, status={}, objectEtag={}, lastIndexedAt={}",
                    messageId, eventId,
                    existingDocument.getDocumentId(),
                    existingDocument.getStatus(),
                    existingDocument.getObjectEtag(),
                    existingDocument.getLastIndexedAt());
        }

        try {
            // ─── (2) 同版本对象已索引则跳过：节省一次完整的 download + chunk + insert。 ──
            // 注意 etag 都为 null 时 Objects.equals 也会返回 true，存在误跳过风险（详见 review 文档 Bug 11）。
            if (existingDocument != null
                    && existingDocument.getStatus() == RagDocument.Status.INDEXED
                    && Objects.equals(existingDocument.getObjectEtag(), uploadEventmessage.objectEtag())) {
                stateService.markEventSuccess(eventId, "同版本对象已完成索引，直接跳过重复消息");
                log.info("RAG 文档摄取幂等跳过: messageId={}, eventId={}, documentId={}, durationMs={}, reason=同版本对象已完成索引",
                        messageId, eventId, existingDocument.getDocumentId(), System.currentTimeMillis() - startedAt);
                return IngestionResult.skip("重复消息，文档已索引完成");
            }

            // ─── (3) PARSING 阶段：判定 fileType + 大小 ─────────────────────────────
            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.PARSING, "开始校验文件元数据");

            String fileType = normalizeFileType(uploadEventmessage.resolvedFileType());
            if (!StringUtils.hasText(fileType)) {
                // 路径里完全无法判断类型 → 标 SKIPPED 并 ack（重投也不会变好）。
                String reason = "无法从对象路径解析文件类型";
                stateService.markDocument(uploadEventmessage, fileType, uploadEventmessage.objectSize(), reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, durationMs={}, reason={}", messageId, eventId, System.currentTimeMillis() - startedAt, reason);
                return IngestionResult.skip(reason);
            }

            if (!supportedExtensions().contains(fileType)) {
                // 不在 RAG 允许的扩展名白名单内 → 标 SKIPPED。
                String reason = "当前简单 RAG 暂不支持该文件类型解析: " + fileType;
                stateService.markDocument(uploadEventmessage, fileType, uploadEventmessage.objectSize(), reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, fileType={}, durationMs={}, reason={}", messageId, eventId, fileType, System.currentTimeMillis() - startedAt, reason);
                return IngestionResult.skip(reason);
            }

            // headObject 同时承担两个作用：兜底拿 contentLength，以及隐式校验对象在 S3 真实存在。
            HeadObjectResponse headObject = awsManager.headObject(uploadEventmessage.bucketName(), uploadEventmessage.objectKey());
            long objectSize = resolveObjectSize(uploadEventmessage, headObject);
            log.debug("RAG 文档元数据校验完成: messageId={}, eventId={}, fileType={}, objectSize={}, eTag={}",
                    messageId, eventId, fileType, objectSize, uploadEventmessage.objectEtag());

            if (objectSize > ragProperties.getIngestion().getMaxObjectSizeBytes()) {
                // 超过单文件上限 → 不下载、不入索引，标 SKIPPED 即可。
                String reason = "文件过大，超过简单 RAG 限制: " + objectSize + " bytes";
                stateService.markDocument(uploadEventmessage, fileType, objectSize, reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, fileType={}, objectSize={}, limitBytes={}, durationMs={}, reason={}",
                        messageId, eventId, fileType, objectSize,
                        ragProperties.getIngestion().getMaxObjectSizeBytes(),
                        System.currentTimeMillis() - startedAt, reason);
                return IngestionResult.skip(reason);
            }

            // ─── (4) 把文档主记录切到 PROCESSING（独立事务，前端轮询立即可见）─────────
            RagDocument document = stateService.upsertDocumentProcessing(uploadEventmessage, fileType, objectSize);
            log.debug("RAG 文档主记录进入 PROCESSING: messageId={}, eventId={}, documentId={}, fileType={}, objectSize={}",
                    messageId, eventId, document.getDocumentId(), fileType, objectSize);

            // ─── (5) DOWNLOADING + EXTRACTING：S3 拉文件 → 子类抽纯文本 ──────────────
            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.DOWNLOADING, "开始从 S3 下载文件");
            byte[] fileBytes = awsManager.getObjectBytes(uploadEventmessage.bucketName(), uploadEventmessage.objectKey());
            log.debug("RAG 文档下载完成: messageId={}, eventId={}, documentId={}, fileType={}, downloadedBytes={}",
                    messageId, eventId, document.getDocumentId(), fileType, fileBytes.length);

            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.EXTRACTING, "开始解析并清洗文档文本");
            String extractedText = extractText(fileBytes, fileType);
            log.debug("RAG 文档抽取完成: messageId={}, eventId={}, documentId={}, fileType={}, extractedCharacters={}, tokenEstimate={}",
                    messageId, eventId, document.getDocumentId(), fileType,
                    extractedText == null ? 0 : extractedText.length(),
                    StringUtils.hasText(extractedText) ? estimateTokens(extractedText) : 0);

            if (!StringUtils.hasText(extractedText)) {
                // 抽出来全是空白 → 没有索引价值，但仍然算"消费成功"。
                String reason = "抽取后的文本为空，跳过索引";
                stateService.markDocument(uploadEventmessage, fileType, objectSize, reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, documentId={}, durationMs={}, reason={}",
                        messageId, eventId, document.getDocumentId(), System.currentTimeMillis() - startedAt, reason);
                return IngestionResult.skip(reason);
            }

            // ─── (6) CHUNKING：按 chunkSize / overlap / 自然边界切块 ────────────────
            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.CHUNKING, "开始按配置切分文档分块");
            ChunkMetadata baseMetadata = buildBaseChunkMetadata(document, fileType);
            List<ChunkCandidate> chunks = splitter.chunk(extractedText, baseMetadata);
            log.debug("RAG 文档分块完成: messageId={}, eventId={}, documentId={}, fileType={}, chunkCount={}, extractedCharacters={}",
                    messageId, eventId, document.getDocumentId(), fileType, chunks.size(), extractedText.length());

            if (chunks.isEmpty()) {
                String reason = "分块结果为空，跳过索引";
                stateService.markDocument(uploadEventmessage, fileType, objectSize, reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, documentId={}, durationMs={}, reason={}",
                        messageId, eventId, document.getDocumentId(), System.currentTimeMillis() - startedAt, reason);
                return IngestionResult.skip(reason);
            }

            // ─── (7) INDEXING：把 chunk 全量替换写入 + 终态 SUCCESS ──────────────────
            // replaceDocumentIndex 内部是"删旧→写新→标 INDEXED"原子事务。
            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.INDEXING, "开始写入 RAG 索引分块");
            Integer previousChunkCount = document.getChunkCount();
            stateService.replaceDocumentIndex(document.getDocumentId(), chunks, extractedText.length());
            // 如果配置了向量索引，就在写完 chunk 后调用 DashScope 生成向量并写入 Milvus；如果向量写入失败，则记录告警但不影响最终索引成功。
            if ("vector".equalsIgnoreCase(ragProperties.getRetrieval().getMode())) {
                stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.EMBEDDING, "开始调用 DashScope 生成向量并写入 Milvus");
                try {
                    List<RagDocumentChunk> persistedChunks = chunkRepository.findByDocumentIdWithDocument(document.getDocumentId());
                    //调用生成向量化，并且写入向量数据库
                    vectorIndexingService.replaceVectorIndex(document.getDocumentId(), persistedChunks, previousChunkCount);
                } catch (Exception ex) {
                    log.warn("向量写入失败（已降级，文档仍可用 keyword 检索）: documentId={}, error={}",
                            document.getDocumentId(), ex.getMessage(), ex);
                }
            }

            stateService.markEventSuccess(eventId, "文档索引完成");
            log.info("RAG 文档摄取成功: messageId={}, eventId={}, documentId={}, fileType={}, objectSize={}, extractedCharacters={}, chunkCount={}, durationMs={}",
                    messageId, eventId, document.getDocumentId(), fileType, objectSize,
                    extractedText.length(), chunks.size(), System.currentTimeMillis() - startedAt);
            return IngestionResult.success("文档索引完成");

        } catch (Exception ex) {
            return handleFailure(uploadEventmessage, eventId, ex, startedAt);
        }
    }

    /**
     * 文本提取：调用注入的 parser 解析原始字节，再调用 cleaner 清洗。
     *
     * <p>这是 Strategy 模式的组合调用点，子类通过构造器注入不同的策略实现，
     * 无需覆盖本方法即可支持不同文件格式。</p>
     */
    private String extractText(byte[] fileBytes, String fileType) throws IOException {
        // 先调用当前处理器绑定的 parser，把原始文件字节转换成“尚未清洗”的原始文本。
        String rawText = parser.parse(fileBytes);
        // 如果解析后没有任何有效文本，就直接返回空字符串，避免 cleaner 和 splitter 白跑一遍。
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        // 读取允许的最大抽取字符数，并用 1000 兜底，避免配置过小导致文本几乎无法使用。
        int maxChars = Math.max(1000, ragProperties.getIngestion().getMaxExtractedCharacters());
        // 把原始文本交给当前处理器绑定的 cleaner 做规范化清洗和必要截断。
        String cleaned = cleaner.clean(rawText, maxChars);
        // 如果原始文本长度已经超过上限，记录告警，帮助排查“为什么 chunk 比原文少”。
        if (rawText.length() > maxChars) {
            log.warn("抽取文本过长，已按配置截断: fileType={}, originalLength={}, maxCharacters={}", fileType, rawText.length(), maxChars);
        }
        // 返回清洗后的最终可切分文本。
        return cleaned;
    }

    /**
     * 通过注入的 {@link RagTextSplitter} 策略切分文本为可索引的 chunk。
     *
     * <p>具体切分算法由子类构造时注入的 splitter 决定，本方法仅做委托。</p>
     */
    @Override
    public List<ChunkCandidate> chunk(String text) {
        return splitter.chunk(text);
    }

    /**
     * 入参基础校验。
     *
     * <p>这一层只判断"根本没必要进入数据库/下载阶段"的情况，包括：</p>
     * <ol>
     *     <li>RAG 全局开关</li>
     *     <li>必要字段（bucket / key）齐全</li>
     *     <li>消息中的 bucket 在白名单内（防 SSRF / 越权读 S3）</li>
     *     <li>事件类型属于 ObjectCreated 家族</li>
     * </ol>
     */
    private IngestionResult validateIncomingMessage(S3UploadReceivedMessage message) {
        if (!ragProperties.isEnabled()) {
            log.info("RAG 摄取预检查跳过：RAG 已关闭");
            return IngestionResult.skip("RAG 已关闭，跳过处理");
        }
        if (message == null || !StringUtils.hasText(message.bucketName()) || !StringUtils.hasText(message.objectKey())) {
            log.warn("RAG 摄取预检查失败：S3 事件缺少 bucket 或 objectKey");
            return IngestionResult.permanentFailure("S3 事件缺少 bucket / objectKey，无法处理");
        }
        if (!isBucketAllowed(message.bucketName())) {
            log.warn("拒绝处理来自非白名单 bucket 的 S3 事件: bucket={}", message.bucketName());
            return IngestionResult.permanentFailure("Bucket 未在 RAG 摄取白名单内: " + message.bucketName());
        }
        if (StringUtils.hasText(message.eventName()) && !message.eventName().startsWith("ObjectCreated")) {
            log.info("RAG 摄取预检查跳过：非 ObjectCreated 事件, eventName={}", message.eventName());
            return IngestionResult.skip("当前只处理 ObjectCreated 事件: " + message.eventName());
        }
        return null;
    }

    /**
     * 校验 bucket 是否在白名单内。
     *
     * <p>校验顺序：</p>
     * <ol>
     *     <li>优先使用 {@code app.rag.listener.allowed-buckets} 显式列表</li>
     *     <li>缺省时退化为只信任 {@code app.aws.s3.uploaded-bucket}（当前真正落盘的桶）</li>
     *     <li>如果两者都没配，宁可拒绝也不放行——避免在错误配置下变成攻击面</li>
     * </ol>
     */
    private boolean isBucketAllowed(String bucketName) {
        java.util.List<String> allowed = ragProperties.getListener().getAllowedBuckets();
        if (allowed != null && !allowed.isEmpty()) {
            return allowed.contains(bucketName);
        }
        String uploadedBucket = awsProperties.getS3().getUploadedBucket();
        if (StringUtils.hasText(uploadedBucket)) {
            return uploadedBucket.equals(bucketName);
        }
        return false;
    }

    /**
     * 处理摄取过程中的异常。
     *
     * <p>关键：所有失败状态写入都通过 {@link RagIngestionStateService}（独立事务），
     * 即便 try 中抛出 RuntimeException，FAILED 状态也能可靠落库。</p>
     */
    private IngestionResult handleFailure(S3UploadReceivedMessage message, Long eventId, Exception ex, long startedAt) {
        String detail = buildFailureDetail(ex);
        boolean retryable = isRetryable(ex);
        log.error("RAG 文档摄取失败: eventId={}, deduplicationKey={}, bucket={}, fileId={}, retryable={}, durationMs={}, error={}",
                eventId, abbreviate(message.deduplicationKey(), 48), message.bucketName(), message.fileId(),
                retryable, System.currentTimeMillis() - startedAt, detail, ex);
        try {
            String fileType = normalizeFileType(message.resolvedFileType());
            stateService.markDocument(message, fileType, message.objectSize(), detail, RagDocument.Status.FAILED);
            stateService.markEventFailed(eventId, detail);
        } catch (Exception persistEx) {
            log.error("写入 RAG 失败状态时再次发生异常: eventId={}", eventId, persistEx);
        }
        return retryable ? IngestionResult.retryLater(detail) : IngestionResult.permanentFailure(detail);
    }

    private void logReservationOrValidationExit(S3UploadReceivedMessage message, String messageId,
                                                RagIngestionEvent event, IngestionResult result,
                                                long startedAt, String phase) {
        if (result == null) return;
        String template = "RAG 文档摄取%s: phase={}, messageId={}, eventId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, shouldDeleteMessage={}, success={}, durationMs={}, detail={}";
        String type = result.shouldDeleteMessage() ? "提前结束" : "需等待重试";
        Object[] args = {
                phase, messageId,
                event == null ? null : event.getEventId(),
                abbreviate(message == null ? null : message.deduplicationKey(), 48),
                message == null ? null : message.bucketName(),
                message == null ? null : message.fileId(),
                abbreviate(message == null ? null : message.sessionId(), 32),
                result.shouldDeleteMessage(), result.success(),
                System.currentTimeMillis() - startedAt, result.detail()
        };
        if (result.shouldDeleteMessage()) {
            log.info(String.format(template, type), args);
        } else {
            log.warn(String.format(template, type), args);
        }
    }



    /**
     * 读取允许处理的扩展名集合，并统一转成小写。
     */
    private Set<String> supportedExtensions() {
        // 从配置中拿到允许摄取的扩展名列表。
        return ragProperties.getIngestion().getSupportedExtensions().stream()
                // 过滤掉 null、空串、纯空白，避免配置脏数据污染判断。
                .filter(StringUtils::hasText)
                // 统一转成小写，避免 PDF / Pdf / pdf 等大小写差异造成误判。
                .map(this::normalizeFileType)
                // 收集成 Set，既方便 contains 判断，也能自动去重。
                .collect(Collectors.toSet());
    }

    /**
     * 规范化文件扩展名。
     *
     * <p>摄取链路会用文件类型做两类判断：</p>
     * <ol>
     *     <li>是否在 {@code supportedExtensions} 白名单内；</li>
     *     <li>写入 {@code RagDocument.fileType}，供前端展示和后续检索元数据使用。</li>
     * </ol>
     *
     * <p>因此这里必须把大小写和首尾空白统一掉，确保 {@code PDF}、{@code pdf}、
     * {@code " pdf "} 都被视为同一种类型。空值返回空字符串，让上层走“无法解析文件类型”的跳过路径。</p>
     *
     * @param fileType 原始文件扩展名或类型字符串
     * @return 小写且去掉首尾空白的扩展名；无有效内容时返回空字符串
     */
    private String normalizeFileType(String fileType) {
        // 对空值统一返回空字符串，减少外层到处判 null。
        if (!StringUtils.hasText(fileType)) return "";
        // 去掉首尾空白并转成固定小写，保证扩展名比较稳定一致。
        return fileType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析对象大小。
     *
     * <p>对象大小有两个来源：S3 事件消息体中的 size，以及 S3 HEAD 请求返回的 contentLength。
     * 优先使用消息体，是因为它已经随事件到达，不需要依赖额外请求结果；
     * 当消息体没有 size 或 size 异常时，再使用 HEAD 结果兜底。</p>
     *
     * <p>这个值主要用于“单文件最大大小限制”判断和状态展示，不参与文件内容解析。</p>
     *
     * @param message 标准化后的上传事件
     * @param headObject S3 HEAD 对象响应
     * @return 非负对象大小；没有可靠值时返回 0
     */
    private long resolveObjectSize(S3UploadReceivedMessage message, HeadObjectResponse headObject) {
        // 优先使用消息体里自带的对象大小；如果消息里已经有值，就不重复依赖 headObject。
        if (message.objectSize() != null && message.objectSize() >= 0) return message.objectSize();
        // 否则回退到 S3 HEAD 返回的 contentLength，并用 0 兜底防止出现负数。
        return Math.max(0L, headObject.contentLength());
    }

    /**
     * 判断异常是否适合交给 SQS 重试。
     *
     * <p>这里区分“临时性外部故障”和“业务/数据问题”：</p>
     * <ul>
     *     <li>网络抖动、AWS SDK 客户端异常、S3 429/5xx 通常是临时故障，重试可能成功；</li>
     *     <li>解析失败、非法文件类型、程序参数错误等通常重试也不会变好，应该尽快落失败态。</li>
     * </ul>
     *
     * @param ex 主流程捕获到的异常
     * @return {@code true} 表示返回 retryLater，让 SQS 后续重投；{@code false} 表示永久失败
     */
    private boolean isRetryable(Exception ex) {
        // IO 异常、AWS SDK 客户端异常通常是临时性问题，允许重试。
        if (ex instanceof IOException || ex instanceof SdkClientException) return true;
        // S3 429 或 5xx 一般表示限流或服务端暂时失败，也应该重试。
        if (ex instanceof S3Exception s3ex) return s3ex.statusCode() == 429 || s3ex.statusCode() >= 500;
        // 其他异常默认视为非重试型，让消息尽快暴露问题而不是无休止回放。
        return false;
    }

    /**
     * 构造写入状态表和日志的失败详情。
     *
     * <p>优先保留异常自身 message，因为它通常包含最直接的故障原因。
     * 如果 message 为空，再退回到异常类型名，至少能让排查者知道失败来自哪类异常。</p>
     *
     * @param ex 摄取过程中捕获到的异常
     * @return 可写入状态表的失败描述
     */
    private String buildFailureDetail(Exception ex) {
        // 尽量保留原始异常消息，方便后续在前端、日志、数据库状态中定位问题。
        String message = ex.getMessage();
        // 如果异常消息为空，再退回到“异常类型名”这种最基本的说明。
        return StringUtils.hasText(message) ? message : "处理失败: " + ex.getClass().getSimpleName();
    }

    /**
     * 估算文本 token 数。
     *
     * <p>当前使用 CL100K_BASE 编码器估算 token，这个编码与 OpenAI/DashScope 常见模型比较接近。
     * 估算结果用于日志观测，不作为严格截断依据；真正的 chunk 截断仍基于字符数配置。</p>
     *
     * @param text 要估算的文本
     * @return 至少为 1 的 token 估算值
     */
    private int estimateTokens(String text) {
        // 使用与 OpenAI / DashScope 常见兼容的 CL100K_BASE 估算 token 数，最少返回 1。
        return Math.max(1, ENCODING.countTokens(text));
    }

    /**
     * 构造传给 splitter 的基础 chunk metadata。
     *
     * <p>这里把文档主表中的稳定业务上下文一次性灌入 {@link ChunkMetadata}：
     * documentId、sessionId、ownerFolder、fileId、fileName、fileType 和 source。
     * 后续每个 chunk 都会继承这些字段，检索命中后才能知道“这段内容来自哪个文件、哪个会话、哪个用户目录”。</p>
     *
     * @param document 已进入 PROCESSING 的文档主记录
     * @param fileType 当前文件类型
     * @return 每个 chunk 都会继承的基础 metadata
     */
    private ChunkMetadata buildBaseChunkMetadata(RagDocument document, String fileType) {
        return ChunkMetadata.ofBase(
                String.valueOf(document.getDocumentId()),
                nullSafe(document.getSessionId()),
                nullSafe(document.getOwnerFolder()),
                nullSafe(document.getFileId()),
                nullSafe(document.getFileName()),
                nullSafe(fileType),
                nullSafe(document.getBucketName()) + "/" + nullSafe(document.getObjectKey()));
    }

    /**
     * 把可能为 null 的字符串转换为空字符串。
     *
     * <p>metadata 和 Milvus JSON 字段里尽量避免写入 null，
     * 因为 null 在不同序列化器、数据库 JSON、Milvus filter 中的表现不完全一致。
     * 统一成空字符串可以让后续读取更稳定。</p>
     *
     * @param value 原始字符串
     * @return 非 null 字符串
     */
    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 对日志字段做保护性缩略。
     *
     * <p>只用于日志，不参与任何业务判断。目的是在保留前缀定位能力的同时，
     * 避免超长 deduplicationKey、sessionId 或 objectKey 把日志行拉得过长。</p>
     *
     * @param value 原始字符串
     * @param maxLength 最大展示长度
     * @return 缩略后的日志字符串
     */
    private String abbreviate(String value, int maxLength) {
        // 如果本来就为空或长度足够短，就直接返回原值，不做截断。
        if (!StringUtils.hasText(value) || value.length() <= maxLength) return value;
        // 超长时保留前缀并补上省略号，既控制日志长度，又能保留足够的排查信息。
        return value.substring(0, Math.max(1, maxLength - 3)) + "...";
    }
}
