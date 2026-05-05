package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocumentChunk;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentChunkRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagRetrievalService} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>正常召回：sessionId 归属正确 + 关键字命中 → 返回带 prompt-injection 防护包裹的 context block</li>
 *     <li>跨用户访问 sessionId：返回空字符串，且不调用 chunk repository</li>
 *     <li>userId 不存在：返回空</li>
 *     <li>极小 maxContextCharacters：仍至少塞下第一段截断后的资料</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTest {

    private static final String USER_ID = "user-001";
    private static final String USERNAME = "admin";
    private static final String SESSION_ID = "sess-001";

    @Mock private RagDocumentChunkRepository ragDocumentChunkRepository;
    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private UserRepository userRepository;

    private RagProperties ragProperties;
    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        service = new RagRetrievalService(ragProperties, ragDocumentChunkRepository, chatSessionRepository, userRepository);

        lenient().when(chatSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(ChatSession.builder().sessionId(SESSION_ID).userId(USER_ID).build()));
        lenient().when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().userId(USER_ID).username(USERNAME).build()));
    }

    @Test
    void shouldReturnContextBlockWrappedWithInjectionGuardWhenChunksHit() {
        RagDocument document = RagDocument.builder()
                .documentId(1L)
                .sessionId(SESSION_ID)
                .ownerFolder(USERNAME)
                .fileName("manual.txt")
                .fileType("txt")
                .status(RagDocument.Status.INDEXED)
                .build();
        RagDocumentChunk chunk = RagDocumentChunk.builder()
                .document(document)
                .chunkIndex(0)
                .content("项目部署步骤如下：第一步初始化数据库；第二步启动后端服务。")
                .build();
        when(ragDocumentChunkRepository.findCandidateChunksBySessionIdAndOwner(
                eq(SESSION_ID), eq(USERNAME), eq(RagDocument.Status.INDEXED), any(Pageable.class)))
                .thenReturn(List.of(chunk));

        String contextBlock = service.buildContextBlock(USER_ID, SESSION_ID, "项目部署步骤");

        assertFalse(contextBlock.isBlank());
        assertTrue(contextBlock.contains("<<<RAG_DOC_BEGIN>>>"));
        assertTrue(contextBlock.contains("<<<RAG_DOC_END>>>"));
        assertTrue(contextBlock.contains("不可信文本"));
        assertTrue(contextBlock.contains("manual.txt"));
        assertTrue(contextBlock.contains("项目部署步骤"));
    }

    @Test
    void shouldReturnEmptyAndNeverQueryChunksWhenSessionBelongsToOtherUser() {
        when(chatSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(ChatSession.builder().sessionId(SESSION_ID).userId("other").build()));

        String contextBlock = service.buildContextBlock(USER_ID, SESSION_ID, "任意问题");

        assertEquals("", contextBlock);
        verify(ragDocumentChunkRepository, never()).findCandidateChunksBySessionIdAndOwner(
                any(), any(), any(), any());
    }

    @Test
    void shouldReturnEmptyWhenUserIdNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        String contextBlock = service.buildContextBlock(USER_ID, SESSION_ID, "问题");

        assertEquals("", contextBlock);
        verify(ragDocumentChunkRepository, never()).findCandidateChunksBySessionIdAndOwner(
                any(), any(), any(), any());
    }

    @Test
    void shouldStillIncludeFirstSnippetWhenContextWindowIsVerySmall() {
        ragProperties.getRetrieval().setMaxContextCharacters(520);

        RagDocument document = RagDocument.builder()
                .documentId(1L)
                .sessionId(SESSION_ID)
                .ownerFolder(USERNAME)
                .fileName("manual.txt")
                .fileType("txt")
                .status(RagDocument.Status.INDEXED)
                .build();
        String longContent = "这是一个非常长的资料片段。".repeat(80) + "重点问题答案在这里。";
        RagDocumentChunk chunk = RagDocumentChunk.builder()
                .document(document)
                .chunkIndex(0)
                .content(longContent)
                .build();
        when(ragDocumentChunkRepository.findCandidateChunksBySessionIdAndOwner(
                eq(SESSION_ID), eq(USERNAME), eq(RagDocument.Status.INDEXED), any(Pageable.class)))
                .thenReturn(List.of(chunk));

        String contextBlock = service.buildContextBlock(USER_ID, SESSION_ID, "重点问题答案");

        assertFalse(contextBlock.isBlank());
        assertTrue(contextBlock.contains("[资料1]"));
        assertTrue(contextBlock.contains("manual.txt"));
        assertTrue(contextBlock.contains("<<<RAG_DOC_END>>>"));
    }
}
