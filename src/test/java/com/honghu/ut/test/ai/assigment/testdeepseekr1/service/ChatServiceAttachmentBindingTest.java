package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.ChatMemoryConfig;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.DefaultSystemPromptProvider;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.util.ConnectionHealthChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceAttachmentBindingTest {

    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private RagDocumentRepository ragDocumentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AiTaskKeywordService aiTaskKeywordService;
    @Mock private ChatMemoryService chatMemoryService;
    @Mock private DefaultSystemPromptProvider defaultSystemPromptProvider;
    @Mock private ChatSummaryService chatSummaryService;
    @Mock private RagRetrievalService ragRetrievalService;
    @Mock private AiModelAccessService aiModelAccessService;
    @Mock private AiChatModelGatewayService aiChatModelGatewayService;
    @Mock private ConnectionHealthChecker connectionHealthChecker;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                chatSessionRepository,
                chatMessageRepository,
                ragDocumentRepository,
                userRepository,
                aiTaskKeywordService,
                chatMemoryService,
                new ChatMemoryConfig(),
                defaultSystemPromptProvider,
                chatSummaryService,
                ragRetrievalService,
                aiModelAccessService,
                aiChatModelGatewayService,
                new AiProviderProperties(),
                connectionHealthChecker
        );
    }

    @Test
    void shouldBindAttachmentToSavedUserMessageWhenChatIdIsNull() {
        ChatRequest request = new ChatRequest();
        request.setAttachmentFileIds(List.of("file-1"));
        ChatMessage userMsg = ChatMessage.builder().chatId(10086L).sessionId("sess-1").build();
        User caller = User.builder().userId("u-1").username("alice").build();
        RagDocument document = RagDocument.builder()
                .fileId("file-1")
                .ownerFolder("alice")
                .sessionId("sess-1")
                .chatId(null)
                .build();
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc("file-1")).thenReturn(List.of(document));
        when(ragDocumentRepository.save(any(RagDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(chatService, "bindAttachmentsToUserMessage", request, userMsg, caller, "sess-1");

        verify(ragDocumentRepository, times(1)).save(document);
    }

    @Test
    void shouldSkipAttachmentWhenSessionOrOwnerDoesNotMatch() {
        ChatRequest request = new ChatRequest();
        request.setAttachmentFileIds(List.of("file-1"));
        ChatMessage userMsg = ChatMessage.builder().chatId(10086L).sessionId("sess-1").build();
        User caller = User.builder().userId("u-1").username("alice").build();
        RagDocument document = RagDocument.builder()
                .fileId("file-1")
                .ownerFolder("bob")
                .sessionId("sess-other")
                .chatId(null)
                .build();
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc("file-1")).thenReturn(List.of(document));

        ReflectionTestUtils.invokeMethod(chatService, "bindAttachmentsToUserMessage", request, userMsg, caller, "sess-1");

        verify(ragDocumentRepository, never()).save(any(RagDocument.class));
    }

    @Test
    void shouldRefuseToRebindAttachmentAlreadyBoundToAnotherChat() {
        ChatRequest request = new ChatRequest();
        request.setAttachmentFileIds(List.of("file-1"));
        ChatMessage userMsg = ChatMessage.builder().chatId(10087L).sessionId("sess-1").build();
        User caller = User.builder().userId("u-1").username("alice").build();
        RagDocument document = RagDocument.builder()
                .fileId("file-1")
                .ownerFolder("alice")
                .sessionId("sess-1")
                .chatId(10086L)
                .build();
        when(ragDocumentRepository.findByFileIdOrderByUpdatedAtDesc("file-1")).thenReturn(List.of(document));

        ReflectionTestUtils.invokeMethod(chatService, "bindAttachmentsToUserMessage", request, userMsg, caller, "sess-1");

        verify(ragDocumentRepository, never()).save(any(RagDocument.class));
    }
}

