package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.RagFileStatusResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagIngestionEvent;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagIngestionEventRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * RAG 摄取状态查询服务。
 *
 * <p>职责非常单一：面向前端，把文档主表和摄取事件表聚合成稳定的状态响应。</p>
 *
 * <p>这样控制器只负责参数与 HTTP 语义，具体"哪个状态优先展示、怎么判断已完成、怎么脱敏"
 * 都统一放在这里。</p>
 *
 * <p><strong>安全要点：</strong></p>
 * <ul>
 *     <li>所有查询入口都强制要求 callerUserId（来自 controller 的 X-User-Id 头校验后传入），
 *         并通过 {@code RagDocument.ownerFolder} 或 {@code ChatSession.userId} 做归属校验，
 *         拒绝跨用户访问。</li>
 *     <li>对外返回的 {@code detailMessage} 在<strong>失败态</strong>下做泛化处理，
 *         不直接暴露原始异常信息（如 SQL 报错、S3 内部 endpoint），避免内部细节泄露。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestionStatusService {

    /** 失败态对外的泛化提示，避免回显内部异常细节。 */
    private static final String FAILURE_PUBLIC_MESSAGE = "文件处理失败，请稍后重试或联系管理员";
    /** 跳过态的中性描述。 */
    private static final String SKIPPED_PUBLIC_MESSAGE = "文件已被跳过，未进入 RAG 索引";

    private final RagDocumentRepository ragDocumentRepository;
    private final RagIngestionEventRepository ragIngestionEventRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final UserRepository userRepository;

    /**
     * 根据 fileId 查询单文件的最新状态。
     *
     * @param callerUserId 调用者 user_id；必填，用来做归属校验
     * @param fileId       文件 ID
     */
    @Transactional(readOnly = true)
    public RagFileStatusResponse getFileStatus(String callerUserId, String fileId) {
        // 先确认调用者 userId 非空，避免匿名请求直接进入查询逻辑。
        requireUser(callerUserId);

        // 按 fileId 查最新一条文档主记录；同一 fileId 理论上可能存在历史更新，因此按 updatedAt 倒序取第一条。
        RagDocument document = ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc(fileId).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("未找到文件状态，fileId=" + fileId));

        // 再校验这条文档是否真正属于当前调用者。
        ensureCallerOwnsDocument(callerUserId, document);
        return toResponse(document);
    }

    /**
     * 查询某个会话下所有文件的最新状态列表。
     */
    @Transactional(readOnly = true)
    public List<RagFileStatusResponse> listSessionFiles(String callerUserId, String sessionId) {
        // 会话列表查询同样必须先过调用者校验与会话归属校验。
        requireUser(callerUserId);
        ensureCallerOwnsSession(callerUserId, sessionId);

        // 查出该会话下所有文档，再统一映射成前端可消费的状态 DTO。
        return ragDocumentRepository.findBySessionIdOrderByUpdatedAtDescCreatedAtDesc(sessionId).stream()
                .map(this::toResponse)
                .toList();
    }

    private void requireUser(String callerUserId) {
        // 当前项目的身份边界基于 X-User-Id 约定，因此 callerUserId 缺失时直接拒绝。
        if (!StringUtils.hasText(callerUserId)) {
            throw new RagAccessDeniedException("缺少调用者 userId");
        }
    }

    /**
     * 校验 fileId 对应文档的 ownerFolder 是否等于调用者 username。
     */
    private void ensureCallerOwnsDocument(String callerUserId, RagDocument document) {
        // 先把 callerUserId 映射成真实用户实体，拿到 username 供 ownerFolder 比较。
        Optional<User> userOpt = userRepository.findById(callerUserId);
        if (userOpt.isEmpty()) {
            throw new RagAccessDeniedException("调用者 userId 无效");
        }

        // 文档归属是按 ownerFolder=username 落盘的，所以这里比较 username 而不是 userId。
        if (!Objects.equals(document.getOwnerFolder(), userOpt.get().getUsername())) {
            log.warn("拒绝访问非本人文件状态: callerUserId={}, fileId={}, ownerFolder={}",
                    callerUserId, document.getFileId(), document.getOwnerFolder());
            throw new RagAccessDeniedException("无权访问该文件");
        }
    }

    /**
     * 校验 sessionId 是否归属调用者。
     */
    private void ensureCallerOwnsSession(String callerUserId, String sessionId) {
        // 会话 ID 为空属于调用参数错误，不进入数据库查询。
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        // 会话必须存在且 userId 与调用者一致，否则拒绝跨会话窥探文件列表。
        Optional<ChatSession> session = chatSessionRepository.findById(sessionId);
        if (session.isEmpty() || !Objects.equals(session.get().getUserId(), callerUserId)) {
            log.warn("拒绝访问非本人会话文件列表: callerUserId={}, sessionId={}", callerUserId, sessionId);
            throw new RagAccessDeniedException("无权访问该会话");
        }
    }

    private RagFileStatusResponse toResponse(RagDocument document) {
        // 对 document 做非空保护，避免后面状态映射逻辑隐式 NPE。
        RagDocument safeDocument = Objects.requireNonNull(document, "document must not be null");
        // 这里改用 (bucketName, objectKey) 双键，避免不同 bucket 同 key 错位拿到别人事件。
        RagIngestionEvent event = null;
        if (StringUtils.hasText(safeDocument.getObjectKey()) && StringUtils.hasText(safeDocument.getBucketName())) {
            event = ragIngestionEventRepository
                    .findFirstByBucketNameAndObjectKeyOrderByCreatedAtDesc(safeDocument.getBucketName(), safeDocument.getObjectKey())
                    .orElse(null);
        }
        RagDocument.Status documentStatus = safeDocument.getStatus();
        RagIngestionEvent.FileStatus ingestionStatus = event != null ? event.getFileStatus() : null;
        RagIngestionEvent.RagStatus ragStatus = event != null ? event.getRagStatus() : null;

        // completed 表示这条文件处理是否已经到终态，不代表一定成功。
        boolean completed = isCompleted(documentStatus, ingestionStatus);
        // availableForChat 必须同时满足 document INDEXED + event SUCCESS 才置 true，
        // 防止单边状态错位导致前端"以为可以聊天"。
        boolean availableForChat = documentStatus == RagDocument.Status.INDEXED
                && (ingestionStatus == null || ingestionStatus == RagIngestionEvent.FileStatus.SUCCESS);
        // detailMessage 是对前端友好的状态描述，内部会做失败信息脱敏。
        String detailMessage = resolveDetailMessage(safeDocument, ingestionStatus);
        return RagFileStatusResponse.builder()
                .fileId(safeDocument.getFileId())
                .sessionId(safeDocument.getSessionId())
                .fileName(safeDocument.getFileName())
                .fileType(safeDocument.getFileType())
                .bucketName(safeDocument.getBucketName())
                .objectKey(safeDocument.getObjectKey())
                .documentStatus(documentStatus != null ? documentStatus.name() : null)
                .ingestionStatus(ingestionStatus != null ? ingestionStatus.name() : null)
                .ragStatus(ragStatus != null ? ragStatus.name() : null)
                .completed(completed)
                .availableForChat(availableForChat)
                .chunkCount(safeDocument.getChunkCount())
                .extractedCharacterCount(safeDocument.getExtractedCharacterCount())
                .fileSize(safeDocument.getFileSize())
                .detailMessage(detailMessage)
                .lastIndexedAt(safeDocument.getLastIndexedAt())
                .updatedAt(safeDocument.getUpdatedAt())
                .createdAt(safeDocument.getCreatedAt())
                .build();
    }

    private boolean isCompleted(RagDocument.Status documentStatus, RagIngestionEvent.FileStatus ingestionStatus) {
        // 只要文档主表已经是终态，就直接视为处理完成。
        if (documentStatus == RagDocument.Status.INDEXED
                || documentStatus == RagDocument.Status.FAILED
                || documentStatus == RagDocument.Status.SKIPPED) {
            return true;
        }

        // 如果文档主表还没走到终态，则再参考 ingestion event 的 fileStatus。
        return ingestionStatus == RagIngestionEvent.FileStatus.SUCCESS
                || ingestionStatus == RagIngestionEvent.FileStatus.FAILED
                || ingestionStatus == RagIngestionEvent.FileStatus.SKIPPED;
    }

    /**
     * 计算对外展示的状态描述。
     *
     * <p>关键安全点：失败态<strong>不</strong>回显内部 errorMessage（可能含 SQL/S3 端点等敏感细节），
     * 统一用泛化提示；详细原因留在服务端日志里供运维排错。</p>
     */
    private String resolveDetailMessage(RagDocument document, RagIngestionEvent.FileStatus ingestionStatus) {
        RagDocument.Status status = document != null ? document.getStatus() : null;
        // 失败态统一返回泛化文案，避免把底层异常细节直接暴露给前端。
        if (status == RagDocument.Status.FAILED || ingestionStatus == RagIngestionEvent.FileStatus.FAILED) {
            return FAILURE_PUBLIC_MESSAGE;
        }
        // 跳过态也统一使用中性提示，不把内部 skip reason 全量透出。
        if (status == RagDocument.Status.SKIPPED || ingestionStatus == RagIngestionEvent.FileStatus.SKIPPED) {
            return SKIPPED_PUBLIC_MESSAGE;
        }
        if (status == null) {
            return null;
        }
        if (status == RagDocument.Status.RECEIVED) {
            return "文件已登记，等待 SQS 异步摄取";
        }
        if (status == RagDocument.Status.PROCESSING) {
            return "文件正在处理中";
        }
        if (status == RagDocument.Status.INDEXED) {
            return "文档已建立索引，可用于聊天检索";
        }
        return null;
    }
}
