package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;
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
        requireUser(callerUserId);
        RagDocument document = ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc(fileId).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("未找到文件状态，fileId=" + fileId));
        ensureCallerOwnsDocument(callerUserId, document);
        return toResponse(document);
    }

    /**
     * 查询某个会话下所有文件的最新状态列表。
     */
    @Transactional(readOnly = true)
    public List<RagFileStatusResponse> listSessionFiles(String callerUserId, String sessionId) {
        requireUser(callerUserId);
        ensureCallerOwnsSession(callerUserId, sessionId);
        return ragDocumentRepository.findBySessionIdOrderByUpdatedAtDescCreatedAtDesc(sessionId).stream()
                .map(this::toResponse)
                .toList();
    }

    private void requireUser(String callerUserId) {
        if (!StringUtils.hasText(callerUserId)) {
            throw new RagAccessDeniedException("缺少调用者 userId");
        }
    }

    /**
     * 校验 fileId 对应文档的 ownerFolder 是否等于调用者 username。
     */
    private void ensureCallerOwnsDocument(String callerUserId, RagDocument document) {
        Optional<User> userOpt = userRepository.findById(callerUserId);
        if (userOpt.isEmpty()) {
            throw new RagAccessDeniedException("调用者 userId 无效");
        }
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
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        Optional<ChatSession> session = chatSessionRepository.findById(sessionId);
        if (session.isEmpty() || !Objects.equals(session.get().getUserId(), callerUserId)) {
            log.warn("拒绝访问非本人会话文件列表: callerUserId={}, sessionId={}", callerUserId, sessionId);
            throw new RagAccessDeniedException("无权访问该会话");
        }
    }

    private RagFileStatusResponse toResponse(RagDocument document) {
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
        boolean completed = isCompleted(documentStatus, ingestionStatus);
        // availableForChat 必须同时满足 document INDEXED + event SUCCESS 才置 true，
        // 防止单边状态错位导致前端"以为可以聊天"。
        boolean availableForChat = documentStatus == RagDocument.Status.INDEXED
                && (ingestionStatus == null || ingestionStatus == RagIngestionEvent.FileStatus.SUCCESS);
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
        if (documentStatus == RagDocument.Status.INDEXED
                || documentStatus == RagDocument.Status.FAILED
                || documentStatus == RagDocument.Status.SKIPPED) {
            return true;
        }
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
        if (status == RagDocument.Status.FAILED || ingestionStatus == RagIngestionEvent.FileStatus.FAILED) {
            return FAILURE_PUBLIC_MESSAGE;
        }
        if (status == RagDocument.Status.SKIPPED || ingestionStatus == RagIngestionEvent.FileStatus.SKIPPED) {
            return SKIPPED_PUBLIC_MESSAGE;
        }
        if (status == null) {
            return null;
        }
        return switch (status) {
            case RECEIVED -> "文件已登记，等待 SQS 异步摄取";
            case PROCESSING -> "文件正在处理中";
            case INDEXED -> "文档已建立索引，可用于聊天检索";
            case FAILED -> FAILURE_PUBLIC_MESSAGE;
            case SKIPPED -> SKIPPED_PUBLIC_MESSAGE;
        };
    }
}
