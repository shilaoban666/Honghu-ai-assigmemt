package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.processer;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.EventReservation;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.IngestionResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.S3UploadReceivedMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagIngestionEvent;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.DocumentIngestionHandler;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.RagIngestionStateService;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档摄取处理器抽象基类。
 *
 * <p>这一层把"绝大多数文件类型都通用"的 RAG 入库流程集中起来：</p>
 * <ol>
 *     <li>做消息级幂等预占，避免 SQS 至少一次投递造成重复处理</li>
 *     <li>校验事件、文件类型、对象大小等基础前置条件</li>
 *     <li>从 S3 下载文件并抽取纯文本</li>
 *     <li>把文本切成 chunk，替换旧索引并更新状态</li>
 * </ol>
 *
 * <p>具体的"某种文件怎么抽文本"，由子类按文件类型覆盖 {@link #extractText(String, byte[])}。</p>
 *
 * <h3>事务策略（重要）</h3>
 * <p>本类<strong>不</strong>再使用方法级 {@code @Transactional} 包住整条流水。
 * 原因：</p>
 * <ul>
 *     <li>整条流水包含 S3 下载、PDF 解析等耗时 I/O，事务长时间持有 DB 连接，
 *         且容易超过 SQS visibility timeout。</li>
 *     <li>更严重的是，原实现在 try 抛异常时会让 Spring 把整个事务标记为 rollback-only，
 *         catch 块里再 {@code save()} 失败状态也只是放进 EntityManager，方法返回时统一回滚，
 *         数据库永远看不到 FAILED，前端永远显示 PROCESSING。</li>
 *     <li>同理，过程中的 RAG 阶段状态（PARSING/DOWNLOADING/EXTRACTING/CHUNKING/INDEXING）
 *         在大事务下也只在最后才一次性可见，前端无法轮询出真实进度。</li>
 * </ul>
 * <p>因此所有需要写库的动作都委托给 {@link RagIngestionStateService}，由它以
 * {@code REQUIRES_NEW} 方式独立 commit，每一步状态变更立刻生效。</p>
 *
 * <p><strong>注意：</strong>本类刻意保持<strong>无可变实例字段</strong>。
 * Spring Bean 默认是单例，多线程并发消费 SQS 时不能把 event/document 等放在成员变量里。</p>
 */
@Slf4j
public abstract class AbstractDocumentIngestionProcesser implements DocumentIngestionHandler {

    private final AwsManager awsManager;
    private final RagProperties ragProperties;
    private final RagIngestionStateService stateService;
    private final AwsProperties awsProperties;

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    /**
     * 子类构造器统一调用本父类构造器；显式 {@link Autowired} 让 Spring 在多实现注入下也能正确装配。
     *
     * @param awsManager   S3 / SQS 底层访问统一入口
     * @param ragProperties RAG 配置（白名单、chunk 参数等）
     * @param stateService RAG 状态独立事务服务，所有写库统一走它
     * @param awsProperties AWS 全局配置，用来兜底 bucket 白名单（缺省时只信任 uploaded-bucket）
     */
    @Autowired
    protected AbstractDocumentIngestionProcesser(AwsManager awsManager,
                                                 RagProperties ragProperties,
                                                 RagIngestionStateService stateService,
                                                 AwsProperties awsProperties) {
        this.awsManager = awsManager;
        this.ragProperties = ragProperties;
        this.stateService = stateService;
        this.awsProperties = awsProperties;
    }

    /**
     * RAG 摄取主流水。整条流程拆成 7 个步骤，每一步状态都通过 stateService 独立 commit，
     * 失败时由 catch 块统一兜底落 FAILED。
     *
     * <pre>
     *   ┌── (0) validateIncomingMessage：bucket 白名单 / 事件类型校验
     *   │
     *   ├── (1) reserveEvent：消息级幂等预占（带 stale lock 夺锁）
     *   │
     *   ├── (2) 早退判断：同版本对象已 INDEXED 直接 ack
     *   │
     *   ├── (3) PARSING 阶段：fileType / supportedExtensions / 大小 限制
     *   │
     *   ├── (4) upsertDocumentProcessing：把文档主记录切到 PROCESSING
     *   │
     *   ├── (5) DOWNLOADING + EXTRACTING：S3 拉文件 → 子类抽文本
     *   │
     *   ├── (6) CHUNKING：按字符窗口 + 自然边界切块
     *   │
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
        log.info("RAG 文档摄取预占成功，准备进入处理流水: messageId={}, eventId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}",
                messageId,
                eventId,
                abbreviate(uploadEventmessage.deduplicationKey(), 48),
                uploadEventmessage.bucketName(),
                uploadEventmessage.fileId(),
                abbreviate(uploadEventmessage.sessionId(), 32));

        // 仅做读操作，无事务必要；用来在 (2) 做"同版本已索引"早退判断。
        RagDocument existingDocument = stateService.findDocument(uploadEventmessage).orElse(null);
        if (existingDocument != null && log.isDebugEnabled()) {
            log.debug("命中已有文档主记录: messageId={}, eventId={}, documentId={}, status={}, objectEtag={}, lastIndexedAt={}",
                    messageId,
                    eventId,
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
                log.info("RAG 文档摄取幂等跳过: messageId={}, eventId={}, documentId={}, bucket={}, fileId={}, sessionId={}, fileType={}, durationMs={}, reason={}",
                        messageId,
                        eventId,
                        existingDocument.getDocumentId(),
                        uploadEventmessage.bucketName(),
                        uploadEventmessage.fileId(),
                        abbreviate(uploadEventmessage.sessionId(), 32),
                        existingDocument.getFileType(),
                        System.currentTimeMillis() - startedAt,
                        "同版本对象已完成索引");
                return IngestionResult.skip("重复消息，文档已索引完成");
            }

            // ─── (3) PARSING 阶段：判定 fileType + 大小 ─────────────────────────────
            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.PARSING, "开始校验文件元数据");

            String fileType = normalizeFileType(uploadEventmessage.resolvedFileType());
            if (!StringUtils.hasText(fileType)) {
                // 路径里完全无法判断类型 → 标 SKIPPED 并 ack（重投也不会变好）。
                String reason = "无法从对象路径解析文件类型";
                stateService.markDocument(uploadEventmessage, fileType, uploadEventmessage.objectSize(),
                        reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, bucket={}, fileId={}, sessionId={}, durationMs={}, reason={}",
                        messageId,
                        eventId,
                        uploadEventmessage.bucketName(),
                        uploadEventmessage.fileId(),
                        abbreviate(uploadEventmessage.sessionId(), 32),
                        System.currentTimeMillis() - startedAt,
                        reason);
                return IngestionResult.skip(reason);
            }

            if (!supportedExtensions().contains(fileType)) {
                // 不在 RAG 允许的扩展名白名单内 → 标 SKIPPED。
                String reason = "当前简单 RAG 暂不支持该文件类型解析: " + fileType;
                stateService.markDocument(uploadEventmessage, fileType, uploadEventmessage.objectSize(),
                        reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, bucket={}, fileId={}, sessionId={}, fileType={}, durationMs={}, reason={}",
                        messageId,
                        eventId,
                        uploadEventmessage.bucketName(),
                        uploadEventmessage.fileId(),
                        abbreviate(uploadEventmessage.sessionId(), 32),
                        fileType,
                        System.currentTimeMillis() - startedAt,
                        reason);
                return IngestionResult.skip(reason);
            }

            // headObject 同时承担两个作用：兜底拿 contentLength，以及隐式校验对象在 S3 真实存在。
            HeadObjectResponse headObject = awsManager.headObject(uploadEventmessage.bucketName(), uploadEventmessage.objectKey());
            long objectSize = resolveObjectSize(uploadEventmessage, headObject);
            log.debug("RAG 文档元数据校验完成: messageId={}, eventId={}, bucket={}, fileId={}, fileType={}, objectSize={}, eTag={}",
                    messageId,
                    eventId,
                    uploadEventmessage.bucketName(),
                    uploadEventmessage.fileId(),
                    fileType,
                    objectSize,
                    uploadEventmessage.objectEtag());
            if (objectSize > ragProperties.getIngestion().getMaxObjectSizeBytes()) {
                // 超过单文件上限 → 不下载、不入索引，标 SKIPPED 即可。
                String reason = "文件过大，超过简单 RAG 限制: " + objectSize + " bytes";
                stateService.markDocument(uploadEventmessage, fileType, objectSize, reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, bucket={}, fileId={}, sessionId={}, fileType={}, objectSize={}, limitBytes={}, durationMs={}, reason={}",
                        messageId,
                        eventId,
                        uploadEventmessage.bucketName(),
                        uploadEventmessage.fileId(),
                        abbreviate(uploadEventmessage.sessionId(), 32),
                        fileType,
                        objectSize,
                        ragProperties.getIngestion().getMaxObjectSizeBytes(),
                        System.currentTimeMillis() - startedAt,
                        reason);
                return IngestionResult.skip(reason);
            }

            // ─── (4) 把文档主记录切到 PROCESSING（独立事务，前端轮询立即可见）─────────
            RagDocument document = stateService.upsertDocumentProcessing(uploadEventmessage, fileType, objectSize);
            log.debug("RAG 文档主记录进入 PROCESSING: messageId={}, eventId={}, documentId={}, bucket={}, fileId={}, sessionId={}, fileType={}, objectSize={}",
                    messageId,
                    eventId,
                    document.getDocumentId(),
                    uploadEventmessage.bucketName(),
                    uploadEventmessage.fileId(),
                    abbreviate(uploadEventmessage.sessionId(), 32),
                    fileType,
                    objectSize);

            // ─── (5) DOWNLOADING + EXTRACTING：S3 拉文件 → 子类抽纯文本 ──────────────
            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.DOWNLOADING, "开始从 S3 下载文件");
            byte[] fileBytes = awsManager.getObjectBytes(uploadEventmessage.bucketName(), uploadEventmessage.objectKey());
            log.debug("RAG 文档下载完成: messageId={}, eventId={}, documentId={}, bucket={}, fileId={}, fileType={}, downloadedBytes={}",
                    messageId,
                    eventId,
                    document.getDocumentId(),
                    uploadEventmessage.bucketName(),
                    uploadEventmessage.fileId(),
                    fileType,
                    fileBytes.length);

            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.EXTRACTING, "开始抽取文档文本");
            String extractedText = extractText(fileType, fileBytes);
            log.debug("RAG 文档抽取完成: messageId={}, eventId={}, documentId={}, bucket={}, fileId={}, fileType={}, extractedCharacters={}, tokenEstimate={}",
                    messageId,
                    eventId,
                    document.getDocumentId(),
                    uploadEventmessage.bucketName(),
                    uploadEventmessage.fileId(),
                    fileType,
                    extractedText == null ? 0 : extractedText.length(),
                    StringUtils.hasText(extractedText) ? estimateTokens(extractedText) : 0);
            if (!StringUtils.hasText(extractedText)) {
                // 抽出来全是空白 → 没有索引价值，但仍然算"消费成功"。
                String reason = "抽取后的文本为空，跳过索引";
                stateService.markDocument(uploadEventmessage, fileType, objectSize, reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, documentId={}, bucket={}, fileId={}, sessionId={}, fileType={}, durationMs={}, reason={}",
                        messageId,
                        eventId,
                        document.getDocumentId(),
                        uploadEventmessage.bucketName(),
                        uploadEventmessage.fileId(),
                        abbreviate(uploadEventmessage.sessionId(), 32),
                        fileType,
                        System.currentTimeMillis() - startedAt,
                        reason);
                return IngestionResult.skip(reason);
            }

            // ─── (6) CHUNKING：按 chunkSize / overlap / 自然边界切块 ────────────────
            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.CHUNKING, "开始按配置切分文档分块");
            List<ChunkCandidate> chunks = chunk(extractedText);
            log.debug("RAG 文档分块完成: messageId={}, eventId={}, documentId={}, bucket={}, fileId={}, fileType={}, chunkCount={}, extractedCharacters={}",
                    messageId,
                    eventId,
                    document.getDocumentId(),
                    uploadEventmessage.bucketName(),
                    uploadEventmessage.fileId(),
                    fileType,
                    chunks.size(),
                    extractedText.length());
            if (chunks.isEmpty()) {
                String reason = "分块结果为空，跳过索引";
                stateService.markDocument(uploadEventmessage, fileType, objectSize, reason, RagDocument.Status.SKIPPED);
                stateService.markEventSkipped(eventId, reason);
                log.info("RAG 文档摄取跳过: messageId={}, eventId={}, documentId={}, bucket={}, fileId={}, sessionId={}, fileType={}, durationMs={}, reason={}",
                        messageId,
                        eventId,
                        document.getDocumentId(),
                        uploadEventmessage.bucketName(),
                        uploadEventmessage.fileId(),
                        abbreviate(uploadEventmessage.sessionId(), 32),
                        fileType,
                        System.currentTimeMillis() - startedAt,
                        reason);
                return IngestionResult.skip(reason);
            }

            // ─── (7) INDEXING：把 chunk 全量替换写入 + 终态 SUCCESS ──────────────────
            // replaceDocumentIndex 内部是"删旧→写新→标 INDEXED"原子事务。
            stateService.updateRagStatus(eventId, RagIngestionEvent.RagStatus.INDEXING, "开始写入 RAG 索引分块");
            stateService.replaceDocumentIndex(document.getDocumentId(), chunks, extractedText.length());
            stateService.markEventSuccess(eventId, "文档索引完成");

            log.info("RAG 文档摄取成功: messageId={}, eventId={}, documentId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, fileType={}, objectSize={}, extractedCharacters={}, chunkCount={}, durationMs={}",
                    messageId,
                    eventId,
                    document.getDocumentId(),
                    abbreviate(uploadEventmessage.deduplicationKey(), 48),
                    uploadEventmessage.bucketName(),
                    uploadEventmessage.fileId(),
                    abbreviate(uploadEventmessage.sessionId(), 32),
                    fileType,
                    objectSize,
                    extractedText.length(),
                    chunks.size(),
                    System.currentTimeMillis() - startedAt);
            return IngestionResult.success("文档索引完成");
        } catch (Exception ex) {
            return handleFailure(uploadEventmessage, eventId, ex, startedAt);
        }
    }

    @Override
    public String extractText(String fileType, byte[] fileBytes) throws IOException {
        if (!StringUtils.hasText(fileType)) {
            return "";
        }
        String normalizedType = fileType.trim().toLowerCase(Locale.ROOT);
        String rawText = switch (normalizedType) {
            // 这些类型本质上都可以直接按 UTF-8 文本读取。
            case "txt", "md", "json", "xml", "csv" -> new String(fileBytes, StandardCharsets.UTF_8);
            default -> throw new UnsupportedOperationException("当前简单 RAG 暂不支持该文件类型解析: " + fileType);
        };
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        String sanitized = rawText
                .replace(" ", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        int maxCharacters = Math.max(1000, ragProperties.getIngestion().getMaxExtractedCharacters());
        if (sanitized.length() > maxCharacters) {
            log.warn("抽取文本过长，已按配置截断: fileType={}, originalLength={}, maxCharacters={}",
                    fileType, sanitized.length(), maxCharacters);
            return sanitized.substring(0, maxCharacters);
        }
        return sanitized;
    }

    /**
     * 把一整段文档文本切成多个可检索 chunk。
     *
     * <p>当前采用的策略是：</p>
     * <ol>
     *     <li>按固定字符窗口切</li>
     *     <li>尽量在换行/句号/问号等自然边界收尾</li>
     *     <li>相邻 chunk 保留 overlap，降低语义被硬切断的概率</li>
     * </ol>
     */
    @Override
    public List<ChunkCandidate> chunk(String text) {

        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int chunkSize = Math.max(1, ragProperties.getIngestion().getChunkSize());
        int overlap = Math.max(0, Math.min(chunkSize - 1, ragProperties.getIngestion().getChunkOverlap()));
        int minChunkLength = Math.max(1, ragProperties.getIngestion().getMinChunkLength());
        int maxChunks = Math.max(1, ragProperties.getIngestion().getMaxChunksPerDocument());
        String normalized = text.trim();

        if (normalized.length() <= chunkSize) {
            return List.of(new ChunkCandidate(0, normalized, normalized.length(), estimateTokens(normalized)));
        }

        List<ChunkCandidate> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < normalized.length() && chunks.size() < maxChunks) {
            int rawEnd = Math.min(normalized.length(), start + chunkSize);
            int adjustedEnd = adjustChunkEnd(normalized, start, rawEnd, minChunkLength);
            if (adjustedEnd <= start) {
                adjustedEnd = rawEnd;
            }

            String chunkContent = normalized.substring(start, adjustedEnd).trim();
            if (StringUtils.hasText(chunkContent)) {
                chunks.add(new ChunkCandidate(index++, chunkContent, chunkContent.length(), estimateTokens(chunkContent)));
            }

            if (adjustedEnd >= normalized.length()) {
                break;
            }

            start = Math.max(adjustedEnd - overlap, start + 1);
        }
        if (chunks.isEmpty()) {
            return Collections.emptyList();
        }

        if (chunks.size() > 1) {
            ChunkCandidate lastChunk = chunks.get(chunks.size() - 1);
            if (lastChunk.charCount() < minChunkLength) {
                ChunkCandidate previousChunk = chunks.get(chunks.size() - 2);
                String mergedContent = previousChunk.content() + "\n" + lastChunk.content();
                chunks.set(chunks.size() - 2,
                        new ChunkCandidate(previousChunk.chunkIndex(), mergedContent, mergedContent.length(), estimateTokens(mergedContent)));
                chunks.remove(chunks.size() - 1);
            }
        }

        if (log.isDebugEnabled()) {
            ChunkCandidate firstChunk = chunks.get(0);
            ChunkCandidate lastChunk = chunks.get(chunks.size() - 1);
            log.debug("RAG chunk 策略执行完成: chunkSize={}, overlap={}, minChunkLength={}, maxChunks={}, generatedChunks={}, firstChunkChars={}, lastChunkChars={}",
                    chunkSize,
                    overlap,
                    minChunkLength,
                    maxChunks,
                    chunks.size(),
                    firstChunk.charCount(),
                    lastChunk.charCount());
        }

        return chunks;
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
            // 只 log bucket 名，不 log 完整 key，避免日志被攻击者刷脏。
            log.warn("拒绝处理来自非白名单 bucket 的 S3 事件: bucket={}", message.bucketName());
            return IngestionResult.permanentFailure("Bucket 未在 RAG 摄取白名单内: " + message.bucketName());
        }
        if (StringUtils.hasText(message.eventName()) && !message.eventName().startsWith("ObjectCreated")) {
            log.info("RAG 摄取预检查跳过：非 ObjectCreated 事件, bucket={}, fileId={}, sessionId={}, eventName={}",
                    message.bucketName(),
                    message.fileId(),
                    abbreviate(message.sessionId(), 32),
                    message.eventName());
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

        log.error("RAG 文档摄取失败: eventId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, resolvedFileType={}, retryable={}, durationMs={}, error={}",
                eventId,
                abbreviate(message.deduplicationKey(), 48),
                message.bucketName(),
                message.fileId(),
                abbreviate(message.sessionId(), 32),
                normalizeFileType(message.resolvedFileType()),
                retryable,
                System.currentTimeMillis() - startedAt,
                detail,
                ex);
        if (log.isDebugEnabled()) {
            log.debug("RAG 文档摄取失败 objectKey: eventId={}, objectKey={}", eventId, message.objectKey());
        }

        try {
            String fileType = normalizeFileType(message.resolvedFileType());
            stateService.markDocument(message, fileType, message.objectSize(), detail, RagDocument.Status.FAILED);
            stateService.markEventFailed(eventId, detail);
        } catch (Exception persistEx) {
            // 失败状态写入本身又出错时，不能再吃掉异常，否则消息可能被错误确认。
            log.error("写入 RAG 失败状态时再次发生异常: eventId={}", eventId, persistEx);
        }

        return retryable
                ? IngestionResult.retryLater(detail)
                : IngestionResult.permanentFailure(detail);
    }

    private void logReservationOrValidationExit(S3UploadReceivedMessage message,
                                                String messageId,
                                                RagIngestionEvent event,
                                                IngestionResult result,
                                                long startedAt,
                                                String phase) {
        if (result == null) {
            return;
        }
        String template = result.shouldDeleteMessage()
                ? "RAG 文档摄取提前结束: phase={}, messageId={}, eventId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, shouldDeleteMessage={}, success={}, durationMs={}, detail={}"
                : "RAG 文档摄取需等待重试: phase={}, messageId={}, eventId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, shouldDeleteMessage={}, success={}, durationMs={}, detail={}";
        if (result.shouldDeleteMessage()) {
            log.info(template,
                    phase,
                    messageId,
                    event == null ? null : event.getEventId(),
                    abbreviate(message == null ? null : message.deduplicationKey(), 48),
                    message == null ? null : message.bucketName(),
                    message == null ? null : message.fileId(),
                    abbreviate(message == null ? null : message.sessionId(), 32),
                    result.shouldDeleteMessage(),
                    result.success(),
                    System.currentTimeMillis() - startedAt,
                    result.detail());
        } else {
            log.warn(template,
                    phase,
                    messageId,
                    event == null ? null : event.getEventId(),
                    abbreviate(message == null ? null : message.deduplicationKey(), 48),
                    message == null ? null : message.bucketName(),
                    message == null ? null : message.fileId(),
                    abbreviate(message == null ? null : message.sessionId(), 32),
                    result.shouldDeleteMessage(),
                    result.success(),
                    System.currentTimeMillis() - startedAt,
                    result.detail());
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 3)) + "...";
    }

    private long resolveObjectSize(S3UploadReceivedMessage message, HeadObjectResponse headObject) {
        if (message.objectSize() != null && message.objectSize() >= 0) {
            return message.objectSize();
        }
        return Math.max(0L, headObject.contentLength());
    }

    private boolean isRetryable(Exception ex) {
        if (ex instanceof IOException || ex instanceof SdkClientException) {
            return true;
        }
        if (ex instanceof S3Exception s3Exception) {
            return s3Exception.statusCode() == 429 || s3Exception.statusCode() >= 500;
        }
        return false;
    }

    private String buildFailureDetail(Exception ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message)
                ? message
                : "处理失败: " + ex.getClass().getSimpleName();
    }

    /**
     * 读取允许处理的扩展名集合，并统一转成小写。
     */
    private Set<String> supportedExtensions() {
        return ragProperties.getIngestion().getSupportedExtensions().stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeFileType)
                .collect(Collectors.toSet());
    }

    private String normalizeFileType(String fileType) {
        if (!StringUtils.hasText(fileType)) {
            return "";
        }
        return fileType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 尝试把 chunk 的结束位置挪到更自然的文本边界。
     */
    private int adjustChunkEnd(String text, int start, int rawEnd, int minChunkLength) {
        if (rawEnd >= text.length()) {
            return rawEnd;
        }
        int searchStart = Math.max(start + minChunkLength, rawEnd - 80);
        for (int i = rawEnd; i >= searchStart; i--) {
            char current = text.charAt(i - 1);
            if (current == '\n' || current == '。' || current == '！' || current == '？' || current == '.' || current == ';' || current == '；') {
                return i;
            }
        }
        return rawEnd;
    }

    /**
     * 粗略估算 chunk token 数。
     */
    private int estimateTokens(String text) {
        return Math.max(1, ENCODING.countTokens(text));
    }
}
