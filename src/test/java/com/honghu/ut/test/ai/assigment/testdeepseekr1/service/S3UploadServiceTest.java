package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.PresignedUploadResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.security.RagAccessGuard;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3UploadServiceTest {

    private static final String USER_ID = "user-001";
    private static final String USERNAME = "alice";
    private static final String SESSION_ID = "sess-001";
    private static final String BUCKET = "honghu-ai-document-upload";

    @Mock
    private AwsManager awsManager;
    @Mock
    private AwsProperties awsProperties;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RagDocumentRepository ragDocumentRepository;
    @Mock
    private RagAccessGuard ragAccessGuard;

    @InjectMocks
    private RagDocumentProcessService s3UploadService;

    @BeforeEach
    void setUp() {
        AwsProperties.S3 s3 = new AwsProperties.S3();
        s3.setUploadedBucket(BUCKET);
        s3.setPresignedUrlExpirationMinutes(15);
        lenient().when(awsProperties.getS3()).thenReturn(s3);
    }

    @Test
    void shouldRequireOrCreateOwnedSessionBeforeGeneratingUploadUrl() {
        User user = User.builder().userId(USER_ID).username(USERNAME).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ragAccessGuard.requireOrCreateSession(user, SESSION_ID))
                .thenReturn(ChatSession.builder().sessionId(SESSION_ID).userId(USER_ID).build());
        when(awsManager.generatePresignedPutUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("https://s3/upload-url");

        PresignedUploadResult result = s3UploadService.generatePresignedUploadUrl(USER_ID, SESSION_ID, "pdf", "report.pdf");

        verify(ragAccessGuard).requireOrCreateSession(user, SESSION_ID);
        verify(awsManager).ensureBucketExists(BUCKET);
        assertEquals("https://s3/upload-url", result.uploadUrl());
        assertEquals("application/pdf", result.contentType());
    }

    @Test
    void shouldRejectWhenSessionBelongsToAnotherUser() {
        User user = User.builder().userId(USER_ID).username(USERNAME).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ragAccessGuard.requireOrCreateSession(user, SESSION_ID))
                .thenThrow(new RagAccessDeniedException("无权访问该会话"));

        assertThrows(RagAccessDeniedException.class,
                () -> s3UploadService.generatePresignedUploadUrl(USER_ID, SESSION_ID, "pdf", "report.pdf"));
    }

    @Test
    void shouldRejectUnsupportedFileTypeBeforeTouchingSession() {
        assertThrows(IllegalArgumentException.class,
                () -> s3UploadService.generatePresignedUploadUrl(USER_ID, SESSION_ID, "exe", "virus.exe"));
    }
}



