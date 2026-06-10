package com.honghu.ai.assigment.rag;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.dto.record.S3UploadReceivedMessage;
import com.honghu.ai.assigment.entity.RagDocument;
import com.honghu.ai.assigment.entity.RagIngestionEvent;
import com.honghu.ai.assigment.rag.index.DocumentIngestionService;
import com.honghu.ai.assigment.rag.index.RagEventMessageParser;
import com.honghu.ai.assigment.repository.RagDocumentRepository;
import com.honghu.ai.assigment.repository.RagIngestionEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock
    private RagEventMessageParser ragEventMessageParser;

    @Mock
    private RagDocumentRepository ragDocumentRepository;

    @Mock
    private RagIngestionEventRepository ragIngestionEventRepository;

    @Test
    void shouldPersistSkippedStatusWhenNoHandlerExists() throws IOException {
        RagProperties ragProperties = new RagProperties();
        S3UploadReceivedMessage message = new S3UploadReceivedMessage(
                "bucket-a",
                "admin/sess-1/png/file-1/image.png",
                "etag-001",
                128L,
                "ObjectCreated:Put",
                "seq-001",
                "bucket-a|admin/sess-1/png/file-1/image.png|seq-001"
        );

        DocumentIngestionService service = new DocumentIngestionService(
                List.of(),
                ragEventMessageParser,
                ragProperties,
                ragDocumentRepository,
                ragIngestionEventRepository
        );

        when(ragEventMessageParser.parse("raw-body")).thenReturn(List.of(message));
        when(ragIngestionEventRepository.findByDeduplicationKey(message.deduplicationKey())).thenReturn(Optional.empty());
        when(ragDocumentRepository.findByBucketNameAndObjectKey(message.bucketName(), message.objectKey())).thenReturn(Optional.empty());
        when(ragIngestionEventRepository.save(any(RagIngestionEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragDocumentRepository.save(any(RagDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestMessages("raw-body", "msg-001");

        ArgumentCaptor<RagIngestionEvent> eventCaptor = ArgumentCaptor.forClass(RagIngestionEvent.class);
        verify(ragIngestionEventRepository).save(eventCaptor.capture());
        assertEquals(RagIngestionEvent.FileStatus.SKIPPED, eventCaptor.getValue().getFileStatus());
        assertEquals(RagIngestionEvent.RagStatus.SKIPPED, eventCaptor.getValue().getRagStatus());

        ArgumentCaptor<RagDocument> documentCaptor = ArgumentCaptor.forClass(RagDocument.class);
        verify(ragDocumentRepository).save(documentCaptor.capture());
        assertEquals(RagDocument.Status.SKIPPED, documentCaptor.getValue().getStatus());
        assertEquals("png", documentCaptor.getValue().getFileType());
    }
}
