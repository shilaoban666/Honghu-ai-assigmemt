package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.IngestionResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.S3UploadReceivedMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagIngestionEvent;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.document.processer.DocumentIngestionHandler;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStateService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagIngestionEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 文档摄取服务 —— RAG 摄取链路的<strong>路由层</strong>。
 *
 * <h3>本类的位置</h3>
 * <pre>
 *   RagFilesUploadListener.onMessage(messageBody, messageId)
 *        ↓
 *   DocumentIngestionService.ingestMessages(messageBody, messageId)        ← 你正在看的这一层
 *        ├─ RagEventMessageParser.parse(...)                               ← 把 SQS/SNS 原文解析成 N 条 S3UploadReceivedMessage
 *        └─ for each message:
 *             ├─ 解析 fileType
 *             ├─ 命中具体 handler → handler.handleFileMessage(...)
 *             └─ 未命中 handler → recordUnsupportedFile(...)
 * </pre>
 *
 * <h3>职责拆解</h3>
 * <ol>
 *     <li>统一解析 SQS 原文，屏蔽 S3 / SNS / EventBridge 等不同来源的结构差异</li>
 *     <li>按文件类型把每条 record 路由到对应的 {@link DocumentIngestionHandler}</li>
 *     <li>对没有 handler 可处理的类型，写一条 SKIPPED 记录给前端展示，避免"上传成功但状态接口查不到"</li>
 *     <li>把 handler 的 {@link IngestionResult} 翻译成"上层是 ack 还是抛异常等待 SQS 重试"的明确信号</li>
 * </ol>
 *
 * <h3>事务策略</h3>
 * <p>本方法<strong>没有</strong>方法级 {@code @Transactional}：</p>
 * <ul>
 *     <li>一条 SQS 消息可能包含多条 S3 records，如果用一个大事务包住，
 *         前面已经成功处理的 record 在后面失败时会被一起回滚，前端就看不到中间态。</li>
 *     <li>真正的 DB 写入由 {@link RagIngestionStateService} 通过
 *         {@code REQUIRES_NEW} 独立事务管控，每条 record 的状态独立 commit。</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentIngestionService {

    private final Map<String, DocumentIngestionHandler> handlerMap;
    private final RagEventMessageParser ragEventMessageParser;
    private final RagProperties ragProperties;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagIngestionEventRepository ragIngestionEventRepository;

    /**
     * Spring 启动时自动收集所有具体 handler，并按文件类型建一个快速索引表。
     *
     * <p>例如："pdf" → {@code PdfIngestionProcesser}，
     * "txt"/"md"/"json"/"xml"/"csv" → {@code PlainTextDocumentIngestionProcesser}。</p>
     *
     * <p>这种"按 supportedTypes() 自注册"的方式让新增文件类型只需要 implement 接口
     * + @Component，不用改本类的 if/switch。</p>
     */
    public DocumentIngestionService(List<DocumentIngestionHandler> handlers,
                                    RagEventMessageParser ragEventMessageParser,
                                    RagProperties ragProperties,
                                    RagDocumentRepository ragDocumentRepository,
                                    RagIngestionEventRepository ragIngestionEventRepository) {
        this.ragEventMessageParser = ragEventMessageParser;
        this.ragProperties = ragProperties;
        this.ragDocumentRepository = ragDocumentRepository;
        this.ragIngestionEventRepository = ragIngestionEventRepository;

        // handlerMap 是“扩展名 -> 处理器”的快速索引表，运行时路由不会再遍历全部 handlers。
        this.handlerMap = new HashMap<>();
        for (DocumentIngestionHandler handler : handlers) {
            for (String type : handler.supportedTypes()) {
                // 统一按小写注册，避免 PDF / Pdf / pdf 这类大小写差异导致路由失败。
                handlerMap.put(type.toLowerCase(Locale.ROOT), handler);
            }
        }
        log.info("RAG 摄取 handler 注册完成: handlerCount={}, supportedTypes={}", handlers.size(), handlerMap.keySet());
    }

    /**
     * 执行一条 SQS 消息里所有 S3 records 的摄取。
     *
     * <p>主流程：</p>
     * <ol>
     *     <li>把原始消息体解析成若干 {@link S3UploadReceivedMessage}（屏蔽 SNS/S3 包装差异）</li>
     *     <li>对每一条 record：
     *         <ol>
     *             <li>解析 fileType</li>
     *             <li>命中 handler → 调用 {@link DocumentIngestionHandler#handleFileMessage}</li>
     *             <li>没命中 → 写一条 SKIPPED 状态，避免前端看不到任何记录</li>
     *         </ol>
     *     </li>
     *     <li>任何一条 record 返回 {@code !shouldDeleteMessage} 时立刻抛异常，
     *         让上层 listener 把整条 SQS 消息交还给 SQS 重投。</li>
     * </ol>
     *
     * <p><strong>为什么这里要返回 {@link IngestionResult}：</strong>
     * 上层 Listener 需要根据结果决定这条消息应该确认还是应该抛异常等待 SQS 重新投递。</p>
     */
    public void ingestMessages(String messageBody, String messageId) throws IOException {
        // 第一步：解析。把"SQS 原始消息体"拆成"N 条标准化的 S3 上传事件对象"。
        List<S3UploadReceivedMessage> uploadEventMessages = parseMessageBody(messageBody, messageId);
        log.info("SQS 文档消息解析完成: messageId={}, recordCount={}", messageId, uploadEventMessages.size());

        // 第二步：逐条处理。S3 事件规范允许 1 条消息内含多条 records，所以这里要循环。
        for (int i = 0; i < uploadEventMessages.size(); i++) {
            S3UploadReceivedMessage uploadEventMessage = uploadEventMessages.get(i);
            // 单条 record 级别的耗时统计，方便后续排查慢文件。
            long startedAt = System.currentTimeMillis();

            // 从消息里拿到文件类型，并统一标准化成小写。
            String fileType = normalizeFileType(uploadEventMessage.resolvedFileType());

            // 根据文件类型找到对应的处理器；找不到则进入 unsupported 分支。
            DocumentIngestionHandler handler = handlerMap.get(fileType);
            String handlerName = handler == null ? "<unsupported>" : handler.getClass().getSimpleName();

            log.info("开始处理 RAG 摄取记录: messageId={}, recordIndex={}, totalRecords={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, fileType={}, handler={}",
                    messageId,
                    i + 1,
                    uploadEventMessages.size(),
                    abbreviate(uploadEventMessage.deduplicationKey(), 48),
                    uploadEventMessage.bucketName(),
                    uploadEventMessage.fileId(),
                    abbreviate(uploadEventMessage.sessionId(), 32),
                    fileType,
                    handlerName);
            if (log.isDebugEnabled()) {
                log.debug("RAG 摄取记录 objectKey: messageId={}, recordIndex={}, objectKey={}",
                        messageId, i + 1, uploadEventMessage.objectKey());
            }

            // 路由：有 handler 就交给具体 handler，没 handler 就走"已知未支持"的占位流程。
            IngestionResult result = handler != null
                    ? handler.handleFileMessage(uploadEventMessage, messageId)
                    : recordUnsupportedFile(uploadEventMessage, messageId, fileType);

            log.info("RAG 摄取记录处理完成: messageId={}, recordIndex={}, totalRecords={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, fileType={}, handler={}, shouldDeleteMessage={}, success={}, durationMs={}, detail={}",
                    messageId,
                    i + 1,
                    uploadEventMessages.size(),
                    abbreviate(uploadEventMessage.deduplicationKey(), 48),
                    uploadEventMessage.bucketName(),
                    uploadEventMessage.fileId(),
                    abbreviate(uploadEventMessage.sessionId(), 32),
                    fileType,
                    handlerName,
                    result.shouldDeleteMessage(),
                    result.success(),
                    System.currentTimeMillis() - startedAt,
                    result.detail());

            // shouldDeleteMessage=false 表示"暂时无法消费、需要稍后重试"。
            // 抛异常给上层 → SQS 不 ack → visibility timeout 后重投。
            if (!result.shouldDeleteMessage()) {
                throw new IllegalStateException("文档摄取需要稍后重试: " + result.detail());
            }
        }
    }

    /**
     * 统一解析原始 SQS 消息体。
     *
     * <p>之所以单独抽出来，是因为 Listener 只关心"抛不抛异常"；
     * 真正与消息结构耦合的解析逻辑都留在解析器层。</p>
     *
     * <p>当解析出零条 records 时，按"成功消费"处理（比如 s3:TestEvent 等元事件），
     * 避免把这种正常的"无业务消息"反复重投。</p>
     */
    public List<S3UploadReceivedMessage> parseMessageBody(String messageBody, String messageId) throws IOException {
        // 统一委托给消息解析器，把各种来源格式转成标准 record 列表。
        List<S3UploadReceivedMessage> uploadEventMessages = ragEventMessageParser.parse(messageBody);
        if (uploadEventMessages.isEmpty()) {
            log.warn("SQS 消息未解析出任何 S3 Records，按成功消费跳过: queueName={}, messageId={}",
                    ragProperties.getListener().getQueueName(), messageId);
        } else {
            log.debug("SQS 消息解析得到 S3 Records: queueName={}, messageId={}, recordCount={}",
                    ragProperties.getListener().getQueueName(), messageId, uploadEventMessages.size());
        }
        return uploadEventMessages;
    }

    /**
     * 记录"当前上传文件类型在 RAG 链路里还没有具体 handler"的跳过状态。
     *
     * <p>这样前端不会遇到"文件已上传，但状态接口查不到任何记录"的尴尬情况。</p>
     *
     * <p>逻辑要点：</p>
     * <ol>
     *     <li>幂等：先按 deduplicationKey 找事件，已是终态则直接返回；正在 PROCESSING 则交还 retryLater。</li>
     *     <li>把事件标记为 SKIPPED，并在 RagDocument 主表上同步一条 SKIPPED 记录。</li>
     *     <li>不需要走 stateService：本路径不会被高并发访问，且失败影响面小。</li>
     * </ol>
     */
    private IngestionResult recordUnsupportedFile(S3UploadReceivedMessage message, String messageId, String fileType) {
        // 构造一条对外可理解的“跳过原因”，用于事件表、文档表和日志统一展示。
        String normalizedFileType = normalizeFileType(fileType);
        String reason = StringUtils.hasText(normalizedFileType)
                ? "当前简单 RAG 暂不支持该文件类型解析: " + normalizedFileType
                : "无法识别文件类型，当前简单 RAG 已跳过";

        // 先处理 ingestion event 的幂等更新逻辑，避免重复消息反复插入状态行。
        Optional<RagIngestionEvent> existingEventOptional = ragIngestionEventRepository.findByDeduplicationKey(message.deduplicationKey());
        if (existingEventOptional.isPresent()) {
            RagIngestionEvent existingEvent = existingEventOptional.get();
            if (existingEvent.getFileStatus() == RagIngestionEvent.FileStatus.PROCESSING) {
                return IngestionResult.retryLater("当前消息正在被其他实例处理，稍后重试");
            }
            if (existingEvent.getFileStatus() == RagIngestionEvent.FileStatus.SUCCESS
                    || existingEvent.getFileStatus() == RagIngestionEvent.FileStatus.SKIPPED) {
                return IngestionResult.skip("消息已处理，无需重复消费");
            }
            existingEvent.setQueueMessageId(messageId);
            existingEvent.setBucketName(message.bucketName());
            existingEvent.setObjectKey(message.objectKey());
            existingEvent.setEventName(message.eventName());
            existingEvent.setFileStatus(RagIngestionEvent.FileStatus.SKIPPED);
            existingEvent.setRagStatus(RagIngestionEvent.RagStatus.SKIPPED);
            existingEvent.setErrorMessage(reason);
            existingEvent.setProcessedAt(LocalDateTime.now());
            ragIngestionEventRepository.save(existingEvent);
        } else {
            ragIngestionEventRepository.save(RagIngestionEvent.builder()
                    .queueMessageId(messageId)
                    .deduplicationKey(message.deduplicationKey())
                    .bucketName(message.bucketName())
                    .objectKey(message.objectKey())
                    .eventName(message.eventName())
                    .fileStatus(RagIngestionEvent.FileStatus.SKIPPED)
                    .ragStatus(RagIngestionEvent.RagStatus.SKIPPED)
                    .errorMessage(reason)
                    .processedAt(LocalDateTime.now())
                    .build());
        }

        // 再同步更新 RagDocument 主表，确保前端查文件状态时也能看到一条 SKIPPED 记录。
        RagDocument document = ragDocumentRepository.findByBucketNameAndObjectKey(message.bucketName(), message.objectKey())
                .orElseGet(RagDocument::new);
        document.setBucketName(message.bucketName());
        document.setObjectKey(message.objectKey());
        document.setObjectEtag(message.objectEtag());
        document.setFileName(message.fileName());
        document.setFileType(normalizedFileType);
        document.setFileSize(message.objectSize());
        document.setOwnerFolder(message.ownerFolder());
        document.setSessionId(message.sessionId());
        document.setFileId(message.fileId());
        document.setStatus(RagDocument.Status.SKIPPED);
        document.setErrorMessage(reason);
        ragDocumentRepository.save(document);

        log.info("RAG 摄取跳过：未找到可用 handler, messageId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, fileType={}, reason={}",
                messageId,
                abbreviate(message.deduplicationKey(), 48),
                message.bucketName(),
                message.fileId(),
                abbreviate(message.sessionId(), 32),
                normalizedFileType,
                reason);
        if (log.isDebugEnabled()) {
            log.debug("未命中 handler 的 objectKey: messageId={}, objectKey={}", messageId, message.objectKey());
        }
        return IngestionResult.skip(reason);
    }

    /**
     * 对日志字段做保护性缩略。
     *
     * <p>S3 objectKey、deduplicationKey、sessionId 这类字段可能很长。
     * 如果原样打进每一行日志，既影响可读性，也可能让日志量不必要地膨胀。
     * 本方法只用于日志展示，不参与任何业务判断，因此截断不会影响幂等、查询或路由。</p>
     *
     * @param value 原始字符串
     * @param maxLength 允许展示的最大长度；超出时保留前缀并追加省略号
     * @return 适合写入日志的缩略字符串
     */
    private String abbreviate(String value, int maxLength) {
        // 日志里对超长字段做截断，避免 objectKey / deduplicationKey 过长时刷屏。
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 3)) + "...";
    }

    /**
     * 规范化文件类型字符串。
     *
     * <p>handlerMap 的 key 统一使用“小写、不带多余空白”的扩展名。
     * 因此所有从消息里解析出来的 fileType 都要先经过这个方法再路由，
     * 避免 {@code PDF}、{@code Pdf}、{@code pdf } 这类输入差异导致找不到处理器。</p>
     *
     * <p>空值统一返回空字符串，让调用方可以自然落到 unsupported 分支，
     * 而不需要每个地方都额外写 null 判断。</p>
     *
     * @param fileType 原始文件类型或扩展名
     * @return 规范化后的小写扩展名；无有效内容时返回空字符串
     */
    private String normalizeFileType(String fileType) {
        // 对空值统一返回空串，外层就可以直接走 unsupported 分支而不用到处判 null。
        if (!StringUtils.hasText(fileType)) {
            return "";
        }

        // 去空白并转小写，保证 handlerMap 的路由 key 一致。
        return fileType.trim().toLowerCase(Locale.ROOT);
    }
}
