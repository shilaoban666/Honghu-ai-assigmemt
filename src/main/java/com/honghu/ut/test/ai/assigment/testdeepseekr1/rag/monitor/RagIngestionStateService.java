package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.EventReservation;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.IngestionResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.S3UploadReceivedMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocumentChunk;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagIngestionEvent;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentChunkRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagIngestionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RAG 摄取状态写入服务。
 *
 * <p>本服务只承担一个职责：把 RAG 摄取链路里每一次状态变更<strong>立即落库</strong>。
 * 因此每个 public 方法都标注独立事务，并且<strong>不</strong>依赖调用方的事务上下文。</p>
 *
 * <h3>为什么要单独抽这一层</h3>
 * <ul>
 *     <li><b>避免大事务吞掉失败状态</b>：原实现把整条流水裹在一个 {@code @Transactional} 里，
 *         主流程一旦抛 RuntimeException，Spring 会把当前事务标记为 rollback-only，
 *         catch 块里再调 {@code save()} 也只是 stage 到 EntityManager，方法返回时统一回滚，
 *         结果 {@code FAILED} 状态根本写不进数据库。</li>
 *     <li><b>让中间态对前端可见</b>：原实现里 PARSING / DOWNLOADING / EXTRACTING / CHUNKING / INDEXING
 *         都只是 stage 到大事务，前端轮询时只能看到 {@code RECEIVED} 直接跳到 {@code SUCCESS}。
 *         改为独立事务后，每一阶段的 {@link RagIngestionEvent#getRagStatus()} 立刻可见。</li>
 *     <li><b>解耦文件状态与 RAG 阶段状态</b>：{@link RagIngestionEvent.FileStatus}
 *         描述这条 SQS 消息整体处理到哪儿（RECEIVED/PROCESSING/SUCCESS/FAILED/SKIPPED），
 *         {@link RagIngestionEvent.RagStatus} 描述 RAG 内部走到了哪一步
 *         （PARSING/DOWNLOADING/EXTRACTING/CHUNKING/EMBEDDING/INDEXING/...）。
 *         本服务里 {@link #updateRagStatus} 只动 RagStatus，
 *         {@link #markEventSuccess} / {@link #markEventSkipped} / {@link #markEventFailed}
 *         才同时推进两者到终态。</li>
 * </ul>
 *
 * <p>注意：所有方法都必须从外部 Bean 调用，<strong>不要</strong>在同一个 Bean 里自调用，
 * 否则 Spring AOP 不会生效，独立事务语义就被破坏了。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestionStateService {

    private final RagIngestionEventRepository ragIngestionEventRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagDocumentChunkRepository ragDocumentChunkRepository;
    private final RagProperties ragProperties;

    /**
     * 预占事件处理权（独立事务）。
     *
     * <p>解决 SQS 至少一次投递的重复消费问题：</p>
     * <ul>
     *     <li>已经 SUCCESS / SKIPPED：直接返回幂等跳过结果</li>
     *     <li>正在 PROCESSING 且未超过 stale 阈值：返回 retryLater，让 SQS 后续重投</li>
     *     <li>正在 PROCESSING 但已经超过 stale 阈值：判定为僵尸锁，强行夺锁继续处理</li>
     *     <li>之前 FAILED：把状态翻回 PROCESSING 继续处理</li>
     *     <li>从未见过：插一条 PROCESSING 记录；并发插入触发唯一键冲突时返回 retryLater</li>
     * </ul>
     *
     * <p><strong>为什么需要僵尸锁夺锁逻辑：</strong> 上一个消费实例可能因 OOM / 容器被
     * 强杀 / DB 写入 PROCESSING 后宕机等原因，导致 PROCESSING 状态永远停留。
     * 没有这一段，后续重试永远拿不到锁，只能等着进 DLQ。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventReservation reserveEvent(S3UploadReceivedMessage message, String queueMessageId) {
        // 先按 deduplicationKey 查是否已经存在同一条上传事件的处理记录。
        Optional<RagIngestionEvent> existingOptional =
                ragIngestionEventRepository.findByDeduplicationKey(message.deduplicationKey());
        if (existingOptional.isPresent()) {
            RagIngestionEvent existing = existingOptional.get();
            if (existing.getFileStatus() == RagIngestionEvent.FileStatus.SUCCESS
                    || existing.getFileStatus() == RagIngestionEvent.FileStatus.SKIPPED) {
                log.info("RAG 事件预占命中终态，直接跳过: eventId={}, queueMessageId={}, deduplicationKey={}, fileStatus={}, bucket={}, fileId={}, sessionId={}",
                        existing.getEventId(),
                        queueMessageId,
                        abbreviate(message.deduplicationKey(), 48),
                        existing.getFileStatus(),
                        message.bucketName(),
                        message.fileId(),
                        abbreviate(message.sessionId(), 32));
                return new EventReservation(existing, IngestionResult.skip("消息已处理，无需重复消费"));
            }
            if (existing.getFileStatus() == RagIngestionEvent.FileStatus.PROCESSING) {
                if (!isProcessingLockStale(existing)) {
                    log.info("RAG 事件预占发现活跃锁，本次等待重试: eventId={}, queueMessageId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, lastUpdatedAt={}",
                            existing.getEventId(),
                            queueMessageId,
                            abbreviate(message.deduplicationKey(), 48),
                            message.bucketName(),
                            message.fileId(),
                            abbreviate(message.sessionId(), 32),
                            existing.getUpdatedAt());
                    return new EventReservation(existing, IngestionResult.retryLater("消息正在被其他实例处理，稍后重试"));
                }
                log.warn("检测到 PROCESSING 僵尸锁，强行夺锁: deduplicationKey={}, lockedSince={}",
                        existing.getDeduplicationKey(), existing.getUpdatedAt());
            }

            // 走到这里说明要么是 FAILED 重试，要么是僵尸 PROCESSING 被夺锁，
            // 总之都需要把旧记录改回“当前实例正在处理”。
            existing.setQueueMessageId(queueMessageId);
            existing.setBucketName(message.bucketName());
            existing.setObjectKey(message.objectKey());
            existing.setEventName(message.eventName());
            existing.setErrorMessage(null);
            existing.setFileStatus(RagIngestionEvent.FileStatus.PROCESSING);
            existing.setRagStatus(RagIngestionEvent.RagStatus.RECEIVED);
            existing.setProcessedAt(null);
            RagIngestionEvent saved = ragIngestionEventRepository.save(existing);
            log.info("RAG 事件预占成功（复用旧记录）: eventId={}, queueMessageId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, fileStatus={}, ragStatus={}",
                    saved.getEventId(),
                    queueMessageId,
                    abbreviate(message.deduplicationKey(), 48),
                    message.bucketName(),
                    message.fileId(),
                    abbreviate(message.sessionId(), 32),
                    saved.getFileStatus(),
                    saved.getRagStatus());
            return new EventReservation(saved, null);
        }
        try {
            // 从未见过的事件，直接创建一条新的 PROCESSING 记录，表示当前实例抢到了处理权。
            RagIngestionEvent created = RagIngestionEvent.builder()
                    .queueMessageId(queueMessageId)
                    .deduplicationKey(message.deduplicationKey())
                    .bucketName(message.bucketName())
                    .objectKey(message.objectKey())
                    .eventName(message.eventName())
                    .fileStatus(RagIngestionEvent.FileStatus.PROCESSING)
                    .ragStatus(RagIngestionEvent.RagStatus.RECEIVED)
                    .build();
            RagIngestionEvent saved = ragIngestionEventRepository.save(created);
            log.info("RAG 事件预占成功（新建记录）: eventId={}, queueMessageId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}, fileStatus={}, ragStatus={}",
                    saved.getEventId(),
                    queueMessageId,
                    abbreviate(message.deduplicationKey(), 48),
                    message.bucketName(),
                    message.fileId(),
                    abbreviate(message.sessionId(), 32),
                    saved.getFileStatus(),
                    saved.getRagStatus());
            return new EventReservation(saved, null);
        } catch (DataIntegrityViolationException ex) {
            // 并发情况下，两个消费者可能同时 insert 同一 deduplicationKey，
            // 一方成功后，另一方会撞唯一键；此时返回 retryLater 即可。
            log.warn("检测到并发重复消费，稍后重试: queueMessageId={}, deduplicationKey={}, bucket={}, fileId={}, sessionId={}",
                    queueMessageId,
                    abbreviate(message.deduplicationKey(), 48),
                    message.bucketName(),
                    message.fileId(),
                    abbreviate(message.sessionId(), 32));
            return new EventReservation(null, IngestionResult.retryLater("并发重复消费，等待下次重试"));
        }
    }

    /**
     * 判断一条 PROCESSING 事件是否已经"卡死"到该被强行夺锁。
     *
     * <p>判断依据：上次更新时间距今已经超过 {@link RagProperties.Listener#getStaleProcessingMinutes()}。
     * {@code updatedAt} 由 Hibernate 的 {@code @UpdateTimestamp} 自动维护，
     * 每次 stateService 的状态变更都会刷新它，因此活跃实例不会被误判为僵尸。</p>
     */
    private boolean isProcessingLockStale(RagIngestionEvent event) {
        // staleMinutes 至少为 1，避免错误配置成 0 导致所有 PROCESSING 都被立即视为僵尸。
        int staleMinutes = Math.max(1, ragProperties.getListener().getStaleProcessingMinutes());

        // updatedAt 优先表示“最近一次被触碰的时间”；若为空，再退回 createdAt。
        LocalDateTime lastTouched = event.getUpdatedAt() != null ? event.getUpdatedAt() : event.getCreatedAt();
        if (lastTouched == null) {
            return false;
        }

        // 早于“当前时间 - staleMinutes”则视为锁已陈旧，可以夺锁重试。
        return lastTouched.isBefore(LocalDateTime.now().minusMinutes(staleMinutes));
    }

    /**
     * 仅推进 RAG 阶段状态（独立事务，立即可见）。
     *
     * <p>FileStatus 不动，依旧停留在 PROCESSING；这是"RAG 内部走到第几步"的细粒度可观测字段。</p>
     *
     * <p>注意：detail 写进 {@code errorMessage} 字段是当前 schema 的限制——这个字段被复用作
     * "状态描述 + 失败原因"，前端展示前需要结合 fileStatus 判断；后续建议拆出独立列。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateRagStatus(Long eventId, RagIngestionEvent.RagStatus ragStatus, String detail) {
        // 先安全加载 event；不存在时只记 warn，不直接抛异常中断主链路。
        RagIngestionEvent event = loadEventOrWarn(eventId);
        if (event == null) {
            return;
        }
        RagIngestionEvent.RagStatus previousStatus = event.getRagStatus();

        // 只推进 RAG 内部阶段，不改变外层 fileStatus。
        event.setRagStatus(ragStatus);
        if (StringUtils.hasText(detail)) {
            // 当前 schema 里 detail 暂时复用 errorMessage 列存储。
            event.setErrorMessage(detail);
        }
        ragIngestionEventRepository.save(event);
        log.debug("RAG 阶段状态推进: eventId={}, deduplicationKey={}, from={}, to={}, detail={}",
                eventId,
                abbreviate(event.getDeduplicationKey(), 48),
                previousStatus,
                ragStatus,
                detail);
    }

    /** 终态：成功（独立事务）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEventSuccess(Long eventId, String detail) {
        finalizeEvent(eventId, RagIngestionEvent.FileStatus.SUCCESS, RagIngestionEvent.RagStatus.SUCCESS, detail);
    }

    /** 终态：业务跳过（独立事务）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEventSkipped(Long eventId, String detail) {
        finalizeEvent(eventId, RagIngestionEvent.FileStatus.SKIPPED, RagIngestionEvent.RagStatus.SKIPPED, detail);
    }

    /**
     * 终态：失败（独立事务）。
     *
     * <p>必须独立事务，否则主流程异常导致整体回滚时，失败状态会被一起抹掉，
     * 前端会一直看到 PROCESSING，且 {@link #reserveEvent} 也无法识别上次失败需要重试。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEventFailed(Long eventId, String detail) {
        finalizeEvent(eventId, RagIngestionEvent.FileStatus.FAILED, RagIngestionEvent.RagStatus.FAILED, detail);
    }

    private void finalizeEvent(Long eventId,
                               RagIngestionEvent.FileStatus fileStatus,
                               RagIngestionEvent.RagStatus ragStatus,
                               String detail) {
        // 成功 / 失败 / 跳过三种终态都统一走这里，避免三套几乎相同的代码分散维护。
        RagIngestionEvent event = loadEventOrWarn(eventId);
        if (event == null) {
            return;
        }
        // 同时写入 fileStatus 和 ragStatus，并记录最终处理时间。
        event.setFileStatus(fileStatus);
        event.setRagStatus(ragStatus);
        event.setErrorMessage(detail);
        event.setProcessedAt(LocalDateTime.now());
        ragIngestionEventRepository.save(event);
    }

    /**
     * 按 eventId 加载事件；不存在时记一条 warn 并返回 null。
     *
     * <p>原实现使用 {@code ifPresent}，事件不存在时静默丢失状态变更，导致排错困难。
     * 这里改为显式 warn，方便在监控里发现"event 表被外部清理"或"主键漂移"等异常。</p>
     */
    private RagIngestionEvent loadEventOrWarn(Long eventId) {
        if (eventId == null) {
            log.warn("尝试更新状态但 eventId 为空，跳过");
            return null;
        }
        Optional<RagIngestionEvent> maybe = ragIngestionEventRepository.findById(eventId);
        if (maybe.isEmpty()) {
            log.warn("尝试更新不存在的 RagIngestionEvent: eventId={}", eventId);
            return null;
        }
        return maybe.get();
    }

    /** 按 bucket+key 查文档。 */
    public Optional<RagDocument> findDocument(S3UploadReceivedMessage message) {
        // 用 (bucketName, objectKey) 双键查找当前对象对应的文档主记录。
        return ragDocumentRepository.findByBucketNameAndObjectKey(message.bucketName(), message.objectKey());
    }

    /**
     * 创建/更新文档主记录并切到 PROCESSING（独立事务）。
     *
     * <p>对 {@code DataIntegrityViolationException} 做并发兜底：
     * 当两条不同 deduplicationKey 的 SQS 消息几乎同时指向同一个 (bucket, objectKey)
     * 时，{@code findByBucketNameAndObjectKey} 可能两边都返回空，进而都尝试 insert，
     * 一方会撞上唯一索引 {@code uk_rag_document_bucket_object_key}。
     * 这里捕获后重读一次走 update 分支即可。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagDocument upsertDocumentProcessing(S3UploadReceivedMessage message,
                                                String fileType,
                                                Long objectSize) {
        try {
            // 优先查已有文档；查不到就新建一条空实体，后面统一补元数据。
            RagDocument document = ragDocumentRepository
                    .findByBucketNameAndObjectKey(message.bucketName(), message.objectKey())
                    .orElseGet(RagDocument::new);
            applyDocumentMeta(document, message, fileType, objectSize);

            // 当前对象开始进入处理阶段时，主记录状态切为 PROCESSING，并清空旧错误信息。
            document.setStatus(RagDocument.Status.PROCESSING);
            document.setErrorMessage(null);
            RagDocument saved = ragDocumentRepository.save(document);
            log.debug("RAG 文档主记录切换为 PROCESSING: documentId={}, bucket={}, fileId={}, sessionId={}, fileType={}, objectSize={}",
                    saved.getDocumentId(),
                    message.bucketName(),
                    message.fileId(),
                    abbreviate(message.sessionId(), 32),
                    fileType,
                    objectSize);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            // 并发插入同一对象时，唯一键会兜底；这里捕获后再读一次并转为 update 分支。
            log.warn("并发插入同 bucket/objectKey 文档，重新读取后更新: bucket={}, key={}",
                    message.bucketName(), message.objectKey());
            RagDocument existing = ragDocumentRepository
                    .findByBucketNameAndObjectKey(message.bucketName(), message.objectKey())
                    .orElseThrow(() -> new IllegalStateException("唯一索引冲突后仍找不到文档", ex));
            applyDocumentMeta(existing, message, fileType, objectSize);
            existing.setStatus(RagDocument.Status.PROCESSING);
            existing.setErrorMessage(null);
            RagDocument saved = ragDocumentRepository.save(existing);
            log.debug("RAG 文档主记录冲突后重试成功: documentId={}, bucket={}, fileId={}, sessionId={}, fileType={}, objectSize={}",
                    saved.getDocumentId(),
                    message.bucketName(),
                    message.fileId(),
                    abbreviate(message.sessionId(), 32),
                    fileType,
                    objectSize);
            return saved;
        }
    }

    /**
     * 把文档切到指定终态（SKIPPED / FAILED），并保存原因（独立事务）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDocument(S3UploadReceivedMessage message,
                             String fileType,
                             Long objectSize,
                             String reason,
                             RagDocument.Status targetStatus) {
        // 不存在则新建，存在则更新，保证无论哪条路径都能给前端看到文档主记录。
        RagDocument document = ragDocumentRepository
                .findByBucketNameAndObjectKey(message.bucketName(), message.objectKey())
                .orElseGet(RagDocument::new);
        applyDocumentMeta(document, message, fileType, objectSize);
        document.setStatus(targetStatus);

        // 非 INDEXED 终态下，chunkCount / extractedCharacterCount 已经没有意义，统一清空。
        if (targetStatus != RagDocument.Status.INDEXED) {
            document.setChunkCount(null);
            document.setExtractedCharacterCount(null);
        }
        document.setErrorMessage(reason);
        RagDocument saved = ragDocumentRepository.save(document);
        log.info("RAG 文档主记录更新终态: documentId={}, bucket={}, fileId={}, sessionId={}, fileType={}, targetStatus={}, reason={}",
                saved.getDocumentId(),
                message.bucketName(),
                message.fileId(),
                abbreviate(message.sessionId(), 32),
                fileType,
                targetStatus,
                reason);
    }

    /**
     * 替换文档全量索引并标 INDEXED（独立事务）。
     *
     * <p>这一段刻意保持事务原子：要么"删旧 + 写新 + 状态切 INDEXED"一起成功，
     * 要么全部回滚，避免出现"旧索引被删但新索引没写完"的中间脏态。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceDocumentIndex(Long documentId,
                                     List<ChunkCandidate> chunks,
                                     int extractedCharacterCount) {
        // 文档主记录不存在说明链路前置状态异常，直接抛错让上游处理。
        RagDocument document = ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("文档不存在: id=" + documentId));

        // 先删掉旧 chunk，后面再整批写入新 chunk，保持文档索引与当前文本一致。
        ragDocumentChunkRepository.deleteByDocument_DocumentId(documentId);
        List<RagDocumentChunk> entities = new ArrayList<>(chunks.size());
        for (ChunkCandidate chunk : chunks) {
            // 把纯 DTO 形式的 ChunkCandidate 转成真正可落库的实体对象。
            entities.add(RagDocumentChunk.builder()
                    .document(document)
                    .chunkIndex(chunk.chunkIndex())
                    .content(chunk.content())
                    .charCount(chunk.charCount())
                    .tokenEstimate(chunk.tokenEstimate())
                    .build());
        }
        ragDocumentChunkRepository.saveAll(entities);

        // 文档主表同步更新抽取字符数、chunk 数量、终态和最后索引时间。
        document.setExtractedCharacterCount(extractedCharacterCount);
        document.setChunkCount(entities.size());
        document.setStatus(RagDocument.Status.INDEXED);
        document.setErrorMessage(null);
        document.setLastIndexedAt(LocalDateTime.now());
        ragDocumentRepository.save(document);
        log.info("RAG 文档索引替换完成: documentId={}, bucket={}, fileId={}, sessionId={}, fileType={}, chunkCount={}, extractedCharacterCount={}",
                documentId,
                document.getBucketName(),
                document.getFileId(),
                abbreviate(document.getSessionId(), 32),
                document.getFileType(),
                entities.size(),
                extractedCharacterCount);
    }


    private String abbreviate(String value, int maxLength) {
        // 日志字段太长时做保护性截断，避免 sessionId / deduplicationKey 刷爆日志。
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 3)) + "...";
    }

    private void applyDocumentMeta(RagDocument document,
                                   S3UploadReceivedMessage message,
                                   String fileType,
                                   Long objectSize) {
        // 统一把 message 中的元数据同步到文档主记录，避免多处分散 set 字段导致遗漏。
        document.setBucketName(message.bucketName());
        document.setObjectKey(message.objectKey());
        document.setObjectEtag(message.objectEtag());
        document.setFileName(message.fileName());
        document.setFileType(fileType);
        document.setFileSize(objectSize);
        document.setOwnerFolder(message.ownerFolder());
        document.setSessionId(message.sessionId());
        document.setFileId(message.fileId());
    }
}
