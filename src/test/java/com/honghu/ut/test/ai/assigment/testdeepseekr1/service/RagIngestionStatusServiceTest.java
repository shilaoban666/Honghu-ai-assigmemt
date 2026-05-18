package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.RagFileStatusResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagIngestionEvent;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStatusService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagIngestionEventRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link RagIngestionStatusService} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>正常聚合：document INDEXED + event SUCCESS → availableForChat=true</li>
 *     <li>RECEIVED 但无 event：detailMessage 给出"等待异步摄取"提示</li>
 *     <li>FAILED 状态：detailMessage 走泛化文案，不回显原始 errorMessage</li>
 *     <li>越权（不同 owner）：RagAccessDeniedException</li>
 *     <li>不存在的 fileId：NoSuchElementException</li>
 *     <li>list 接口跨用户调用：RagAccessDeniedException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RagIngestionStatusServiceTest {

    private static final String CALLER_USER_ID = "user-001";
    private static final String CALLER_USERNAME = "admin";
    private static final String SESSION_ID = "sess-001";
    private static final String FILE_ID = "file-001";
    private static final String OBJECT_KEY = "admin/sess-001/pdf/file-001/report.pdf";
    private static final String BUCKET = "bucket-a";

    @Mock private RagDocumentRepository ragDocumentRepository;
    @Mock private RagIngestionEventRepository ragIngestionEventRepository;
    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private RagIngestionStatusService ragIngestionStatusService;

    @BeforeEach
    void setUp() {
        lenient().when(userRepository.findById(CALLER_USER_ID))
                .thenReturn(Optional.of(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build()));
    }

    @Test
    void shouldAggregateIndexedDocumentAndSuccessEventToAvailableForChat() {
        RagDocument document = baseDocument().status(RagDocument.Status.INDEXED).chunkCount(8).build();
        RagIngestionEvent event = RagIngestionEvent.builder()
                .eventId(10L)
                .bucketName(BUCKET)
                .objectKey(OBJECT_KEY)
                .fileStatus(RagIngestionEvent.FileStatus.SUCCESS)
                .ragStatus(RagIngestionEvent.RagStatus.SUCCESS)
                .errorMessage("文档索引完成")
                .build();
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc(FILE_ID)).thenReturn(List.of(document));
        when(ragIngestionEventRepository.findFirstByBucketNameAndObjectKeyOrderByCreatedAtDesc(BUCKET, OBJECT_KEY))
                .thenReturn(Optional.of(event));

        RagFileStatusResponse response = ragIngestionStatusService.getFileStatus(CALLER_USER_ID, FILE_ID);

        assertEquals("INDEXED", response.getDocumentStatus());
        assertEquals("SUCCESS", response.getIngestionStatus());
        assertEquals("SUCCESS", response.getRagStatus());
        assertTrue(response.isCompleted());
        assertTrue(response.isAvailableForChat());
        assertEquals(8, response.getChunkCount());
    }

    @Test
    void shouldReturnPendingDetailWhenDocumentIsReceivedAndEventIsAbsent() {
        RagDocument document = baseDocument().status(RagDocument.Status.RECEIVED).build();
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc(FILE_ID)).thenReturn(List.of(document));
        when(ragIngestionEventRepository.findFirstByBucketNameAndObjectKeyOrderByCreatedAtDesc(BUCKET, OBJECT_KEY))
                .thenReturn(Optional.empty());

        RagFileStatusResponse response = ragIngestionStatusService.getFileStatus(CALLER_USER_ID, FILE_ID);

        assertEquals("RECEIVED", response.getDocumentStatus());
        assertEquals("文件已登记，等待 SQS 异步摄取", response.getDetailMessage());
        assertFalse(response.isAvailableForChat());
    }

    @Test
    void shouldMaskInternalErrorWhenDocumentFailed() {
        RagDocument document = baseDocument()
                .status(RagDocument.Status.FAILED)
                .errorMessage("ORA-00942: table or view does not exist")
                .build();
        RagIngestionEvent event = RagIngestionEvent.builder()
                .bucketName(BUCKET).objectKey(OBJECT_KEY)
                .fileStatus(RagIngestionEvent.FileStatus.FAILED)
                .ragStatus(RagIngestionEvent.RagStatus.FAILED)
                .errorMessage("Caused by: java.sql.SQLException: connection refused @ rds.internal:5432")
                .build();
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc(FILE_ID)).thenReturn(List.of(document));
        when(ragIngestionEventRepository.findFirstByBucketNameAndObjectKeyOrderByCreatedAtDesc(BUCKET, OBJECT_KEY))
                .thenReturn(Optional.of(event));

        RagFileStatusResponse response = ragIngestionStatusService.getFileStatus(CALLER_USER_ID, FILE_ID);

        // 关键：detailMessage 不应回显原始 SQL 报错或内部 endpoint
        assertFalse(response.getDetailMessage().contains("ORA-"));
        assertFalse(response.getDetailMessage().contains("rds.internal"));
        assertFalse(response.isAvailableForChat());
        assertTrue(response.isCompleted());
    }

    @Test
    void shouldRejectWhenCallerUsernameDiffersFromOwnerFolder() {
        when(userRepository.findById("attacker-id"))
                .thenReturn(Optional.of(User.builder().userId("attacker-id").username("attacker").build()));
        RagDocument document = baseDocument().build();
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc(FILE_ID)).thenReturn(List.of(document));

        assertThrows(RagAccessDeniedException.class,
                () -> ragIngestionStatusService.getFileStatus("attacker-id", FILE_ID));
    }

    @Test
    void shouldThrow404WhenFileIdNotFound() {
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc("missing")).thenReturn(List.of());
        assertThrows(NoSuchElementException.class,
                () -> ragIngestionStatusService.getFileStatus(CALLER_USER_ID, "missing"));
    }

    @Test
    void shouldRejectListSessionFilesWhenSessionDoesNotBelongToCaller() {
        when(chatSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(ChatSession.builder().sessionId(SESSION_ID).userId("other").build()));
        assertThrows(RagAccessDeniedException.class,
                () -> ragIngestionStatusService.listSessionFiles(CALLER_USER_ID, SESSION_ID));
    }

    @Test
    void shouldReturnSessionFilesWhenCallerOwnsSession() {
        when(chatSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(ChatSession.builder().sessionId(SESSION_ID).userId(CALLER_USER_ID).build()));
        RagDocument document = baseDocument().status(RagDocument.Status.INDEXED).build();
        when(ragDocumentRepository.findBySessionIdOrderByUpdatedAtDescCreatedAtDesc(SESSION_ID))
                .thenReturn(List.of(document));
        when(ragIngestionEventRepository.findFirstByBucketNameAndObjectKeyOrderByCreatedAtDesc(BUCKET, OBJECT_KEY))
                .thenReturn(Optional.empty());

        List<RagFileStatusResponse> list = ragIngestionStatusService.listSessionFiles(CALLER_USER_ID, SESSION_ID);

        assertEquals(1, list.size());
        assertEquals("INDEXED", list.get(0).getDocumentStatus());
    }

    private RagDocument.RagDocumentBuilder baseDocument() {
        return RagDocument.builder()
                .documentId(1L)
                .fileId(FILE_ID)
                .sessionId(SESSION_ID)
                .fileName("report.pdf")
                .fileType("pdf")
                .bucketName(BUCKET)
                .objectKey(OBJECT_KEY)
                .ownerFolder(CALLER_USERNAME)
                .updatedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now().minusMinutes(2));
    }
}
