package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.security.RagAccessGuard;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagFileRegistrationService} 单元测试。
 *
 * <p>覆盖场景：</p>
 * <ul>
 *     <li>正常路径：调用者 username = ownerFolder → 成功登记 RECEIVED</li>
 *     <li>已有 INDEXED 记录：保留终态，不回退</li>
 *     <li>越权：调用者 username ≠ ownerFolder → RagAccessDeniedException</li>
 *     <li>调用者 userId 缺失</li>
 *     <li>fileType 不在 RAG 白名单</li>
 *     <li>fileName 含恶意控制字符 / 路径分隔符 → 落库前被清洗</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RagFileRegistrationServiceTest {

    private static final String BUCKET = "honghu-ai-document-upload";
    private static final String CALLER_USER_ID = "user-001";
    private static final String CALLER_USERNAME = "admin";
    private static final String OBJECT_KEY = "admin/sess-001/pdf/file-001/report.pdf";

    @Mock private AwsProperties awsProperties;
    @Mock private RagProperties ragProperties;
    @Mock private RagDocumentRepository ragDocumentRepository;
    @Mock private UserRepository userRepository;
    @Mock private RagAccessGuard ragAccessGuard;

    @InjectMocks private RagFileRegistrationService ragFileRegistrationService;

    @BeforeEach
    void setUp() {
        AwsProperties.S3 s3 = new AwsProperties.S3();
        s3.setUploadedBucket(BUCKET);
        lenient().when(awsProperties.getS3()).thenReturn(s3);

        // 默认配置已经包含 pdf
        lenient().when(ragProperties.getIngestion()).thenReturn(new RagProperties.Ingestion());

        lenient().when(userRepository.findById(CALLER_USER_ID))
                .thenReturn(Optional.of(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build()));
    }

    @Test
    void shouldRegisterUploadedFileAsReceivedWhenCallerOwnsTheObjectKey() {
        when(ragAccessGuard.requireOwnedSession(CALLER_USER_ID, "sess-001"))
                .thenReturn(ChatSession.builder().sessionId("sess-001").userId(CALLER_USER_ID).build());
        when(ragDocumentRepository.findByBucketNameAndObjectKey(BUCKET, OBJECT_KEY))
                .thenReturn(Optional.empty());
        when(ragDocumentRepository.save(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String fileId = ragFileRegistrationService.registerUploadedFile(CALLER_USER_ID, OBJECT_KEY, "report.pdf");

        assertEquals("file-001", fileId);
        ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
        verify(ragDocumentRepository).save(captor.capture());
        RagDocument saved = captor.getValue();
        assertEquals(RagDocument.Status.RECEIVED, saved.getStatus());
        assertEquals("sess-001", saved.getSessionId());
        assertEquals("pdf", saved.getFileType());
        assertEquals("file-001", saved.getFileId());
        assertEquals(CALLER_USERNAME, saved.getOwnerFolder());
        assertEquals("文件已上传，等待 SQS 异步摄取", saved.getErrorMessage());
    }

    @Test
    void shouldKeepIndexedDocumentInsteadOfRollingBackToReceived() {
        when(ragAccessGuard.requireOwnedSession(CALLER_USER_ID, "sess-001"))
                .thenReturn(ChatSession.builder().sessionId("sess-001").userId(CALLER_USER_ID).build());
        RagDocument existing = RagDocument.builder()
                .documentId(1L)
                .bucketName(BUCKET)
                .objectKey(OBJECT_KEY)
                .ownerFolder(CALLER_USERNAME)
                .fileId("file-001")
                .status(RagDocument.Status.INDEXED)
                .build();
        when(ragDocumentRepository.findByBucketNameAndObjectKey(BUCKET, OBJECT_KEY))
                .thenReturn(Optional.of(existing));
        when(ragDocumentRepository.save(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ragFileRegistrationService.registerUploadedFile(CALLER_USER_ID, OBJECT_KEY, "report.pdf");

        ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
        verify(ragDocumentRepository).save(captor.capture());
        assertEquals(RagDocument.Status.INDEXED, captor.getValue().getStatus());
    }

    @Test
    void shouldRejectWhenCallerUsernameDiffersFromOwnerFolder() {
        when(userRepository.findById("attacker-id"))
                .thenReturn(Optional.of(User.builder().userId("attacker-id").username("attacker").build()));

        assertThrows(RagAccessDeniedException.class,
                () -> ragFileRegistrationService.registerUploadedFile("attacker-id", OBJECT_KEY, "report.pdf"));
        verify(ragDocumentRepository, never()).save(any());
    }

    @Test
    void shouldRejectWhenCallerUserIdIsBlank() {
        assertThrows(RagAccessDeniedException.class,
                () -> ragFileRegistrationService.registerUploadedFile("", OBJECT_KEY, "report.pdf"));
        verify(ragDocumentRepository, never()).save(any());
    }

    @Test
    void shouldRejectWhenFileTypeNotInWhitelist() {
        when(ragAccessGuard.requireOwnedSession(CALLER_USER_ID, "sess-001"))
                .thenReturn(ChatSession.builder().sessionId("sess-001").userId(CALLER_USER_ID).build());
        RagProperties.Ingestion ingestion = new RagProperties.Ingestion();
        ingestion.setSupportedExtensions(List.of("txt"));
        when(ragProperties.getIngestion()).thenReturn(ingestion);

        assertThrows(IllegalArgumentException.class,
                () -> ragFileRegistrationService.registerUploadedFile(CALLER_USER_ID, OBJECT_KEY, "report.pdf"));
        verify(ragDocumentRepository, never()).save(any());
    }

    @Test
    void shouldSanitizeFileNameRemovingControlCharsAndPathSeparators() {
        when(ragAccessGuard.requireOwnedSession(CALLER_USER_ID, "sess-001"))
                .thenReturn(ChatSession.builder().sessionId("sess-001").userId(CALLER_USER_ID).build());
        when(ragDocumentRepository.findByBucketNameAndObjectKey(BUCKET, OBJECT_KEY))
                .thenReturn(Optional.empty());
        when(ragDocumentRepository.save(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // CR/LF/NUL 用 String.format("%c", code) 显式构造，避免源码字面量被工具链处理
        String malicious = "evil" + (char) 13 + (char) 10 + "name/slash" + (char) 92 + "bs.pdf";
        ragFileRegistrationService.registerUploadedFile(CALLER_USER_ID, OBJECT_KEY, malicious);

        ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
        verify(ragDocumentRepository).save(captor.capture());
        String stored = captor.getValue().getFileName();
        assertFalse(stored.indexOf((char) 13) >= 0, "不应包含 CR");
        assertFalse(stored.indexOf((char) 10) >= 0, "不应包含 LF");
        assertFalse(stored.indexOf('/') >= 0, "不应包含 /");
        assertFalse(stored.indexOf((char) 92) >= 0, "不应包含 \\");
        assertEquals("evilname_slash_bs.pdf", stored);
    }

    @Test
    void shouldRejectWhenSessionDoesNotBelongToCaller() {
        when(ragAccessGuard.requireOwnedSession(CALLER_USER_ID, "sess-001"))
                .thenThrow(new RagAccessDeniedException("无权访问该会话"));

        assertThrows(RagAccessDeniedException.class,
                () -> ragFileRegistrationService.registerUploadedFile(CALLER_USER_ID, OBJECT_KEY, "report.pdf"));
        verify(ragDocumentRepository, never()).save(any());
    }
}
