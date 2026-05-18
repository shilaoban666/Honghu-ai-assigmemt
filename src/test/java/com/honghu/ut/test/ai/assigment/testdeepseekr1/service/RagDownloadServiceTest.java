package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.security.RagAccessGuard;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagDownloadServiceTest {

    @Mock
    private RagDocumentRepository ragDocumentRepository;
    @Mock
    private RagAccessGuard ragAccessGuard;
    @Mock
    private AwsManager awsManager;
    @Mock
    private AwsProperties awsProperties;

    @InjectMocks
    private RagDocumentProcessService ragDownloadService;

    @BeforeEach
    void setUp() {
        AwsProperties.S3 s3 = new AwsProperties.S3();
        s3.setPresignedUrlExpirationMinutes(10);
        lenient().when(awsProperties.getS3()).thenReturn(s3);
    }

    @Test
    void shouldGenerateDownloadUrlForOwnedDocument() {
        User caller = User.builder().userId("u-1").username("alice").build();
        RagDocument document = RagDocument.builder()
                .fileId("file-1")
                .ownerFolder("alice")
                .bucketName("bucket-a")
                .objectKey("alice/sess/pdf/file-1/report.pdf")
                .status(RagDocument.Status.INDEXED)
                .build();
        when(ragAccessGuard.requireUser("u-1")).thenReturn(caller);
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc("file-1")).thenReturn(List.of(document));
        when(awsManager.generatePresignedGetUrl("bucket-a", "alice/sess/pdf/file-1/report.pdf", Duration.ofMinutes(10)))
                .thenReturn("https://s3/download");

        String url = ragDownloadService.generatePresignedDownloadUrl("u-1", "file-1", null);

        assertEquals("https://s3/download", url);
        verify(awsManager).generatePresignedGetUrl("bucket-a", "alice/sess/pdf/file-1/report.pdf", Duration.ofMinutes(10));
    }

    @Test
    void shouldRejectCrossUserDownload() {
        User caller = User.builder().userId("u-1").username("alice").build();
        RagDocument document = RagDocument.builder()
                .fileId("file-1")
                .ownerFolder("bob")
                .bucketName("bucket-a")
                .objectKey("bob/sess/pdf/file-1/report.pdf")
                .status(RagDocument.Status.INDEXED)
                .build();
        when(ragAccessGuard.requireUser("u-1")).thenReturn(caller);
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc("file-1")).thenReturn(List.of(document));

        assertThrows(RagAccessDeniedException.class,
                () -> ragDownloadService.generatePresignedDownloadUrl("u-1", "file-1", null));
    }

    @Test
    void shouldThrowWhenFileDoesNotExist() {
        when(ragAccessGuard.requireUser("u-1")).thenReturn(User.builder().userId("u-1").username("alice").build());
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc("missing")).thenReturn(List.of());

        assertThrows(NoSuchElementException.class,
                () -> ragDownloadService.generatePresignedDownloadUrl("u-1", "missing", null));
    }

    @Test
    void shouldRefuseDownloadWhenDocumentStatusFailed() {
        when(ragAccessGuard.requireUser("u-1")).thenReturn(User.builder().userId("u-1").username("alice").build());
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc("file-1")).thenReturn(List.of(
                RagDocument.builder()
                        .fileId("file-1")
                        .ownerFolder("alice")
                        .bucketName("bucket-a")
                        .objectKey("alice/sess/pdf/file-1/report.pdf")
                        .status(RagDocument.Status.FAILED)
                        .build()
        ));

        assertThrows(IllegalStateException.class,
                () -> ragDownloadService.generatePresignedDownloadUrl("u-1", "file-1", null));
    }
}


