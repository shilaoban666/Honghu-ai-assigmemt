package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.RagSnippetFormatter;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retiriever.VectorRagRetrievalService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorRagRetrievalServiceTest {

    private static final String USER_ID = "user-001";
    private static final String USERNAME = "alice";
    private static final String SESSION_ID = "sess-001";

    @Mock
    private VectorStore vectorStore;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private UserRepository userRepository;

    private VectorRagRetrievalService service;

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRetrieval().setMode("vector");
        service = new VectorRagRetrievalService(
                vectorStore,
                chatSessionRepository,
                userRepository,
                ragProperties,
                new RagSnippetFormatter());

        lenient().when(chatSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(ChatSession.builder().sessionId(SESSION_ID).userId(USER_ID).build()));
        lenient().when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().userId(USER_ID).username(USERNAME).build()));
    }

    @Test
    void shouldBuildContextFromVectorHits() {
        Document hit = new Document(
                "1:0",
                "项目部署步骤：先初始化数据库，再启动服务。",
                Map.of(
                        "documentId", "1",
                        "fileName", "manual.txt",
                        "fileType", "txt",
                        "chunkIndex", 0,
                        "distance", 0.12
                ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        String context = service.buildContextBlock(USER_ID, SESSION_ID, "怎么部署项目");

        assertThat(context).contains("<<<RAG_DOC_BEGIN>>>");
        assertThat(context).contains("manual.txt");
        assertThat(context).contains("项目部署步骤");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
    }

    @Test
    void shouldReturnEmptyWhenSessionBelongsToOtherUser() {
        when(chatSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(ChatSession.builder().sessionId(SESSION_ID).userId("other-user").build()));

        String context = service.buildContextBlock(USER_ID, SESSION_ID, "测试问题");

        assertThat(context).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void shouldReturnEmptyWhenFilterValueContainsIllegalCharacters() {
        String badSessionId = "sess'001";
        when(chatSessionRepository.findById(badSessionId))
                .thenReturn(Optional.of(ChatSession.builder().sessionId(badSessionId).userId(USER_ID).build()));

        String context = service.buildContextBlock(USER_ID, badSessionId, "测试问题");

        assertThat(context).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void shouldDegradeGracefullyWhenVectorStoreFails() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("milvus down"));

        String context = service.buildContextBlock(USER_ID, SESSION_ID, "测试问题");

        assertThat(context).isEmpty();
    }
}


