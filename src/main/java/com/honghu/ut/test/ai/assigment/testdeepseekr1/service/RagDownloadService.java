package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * RAG 附件下载服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDownloadService {

    private static final EnumSet<RagDocument.Status> DOWNLOADABLE_STATUSES =
            EnumSet.of(RagDocument.Status.RECEIVED, RagDocument.Status.PROCESSING, RagDocument.Status.INDEXED);

    private final RagDocumentRepository ragDocumentRepository;
    private final RagAccessGuard ragAccessGuard;
    private final AwsManager awsManager;
    private final AwsProperties awsProperties;

    @Transactional(readOnly = true)
    public String generatePresignedDownloadUrl(String callerUserId, String fileId, Duration ttl) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        User caller = ragAccessGuard.requireUser(callerUserId);
        RagDocument document = ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc(fileId).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("未找到文件，fileId=" + fileId));
        ensureCallerOwnsDocument(caller, document);
        if (document.getStatus() != null && !DOWNLOADABLE_STATUSES.contains(document.getStatus())) {
            throw new IllegalStateException("当前文件状态不允许下载: " + document.getStatus());
        }
        Duration effectiveTtl = ttl != null ? ttl : Duration.ofMinutes(awsProperties.getS3().getPresignedUrlExpirationMinutes());
        return awsManager.generatePresignedGetUrl(document.getBucketName(), document.getObjectKey(), effectiveTtl);
    }

    private void ensureCallerOwnsDocument(User caller, RagDocument document) {
        if (caller == null || document == null) {
            throw new RagAccessDeniedException("无权访问该文件");
        }
        if (!Objects.equals(document.getOwnerFolder(), caller.getUsername())) {
            log.warn("拒绝为非本人文件签发下载链接: callerUserId={}, fileId={}, ownerFolder={}",
                    caller.getUserId(), document.getFileId(), document.getOwnerFolder());
            throw new RagAccessDeniedException("无权访问该文件");
        }
    }
}

