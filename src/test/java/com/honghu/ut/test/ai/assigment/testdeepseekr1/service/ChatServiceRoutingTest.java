package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.ChatMemoryConfig;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.DefaultSystemPromptProvider;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceRoutingTest {

    @Mock
    private OllamaChatModel ollamaChatModel;
    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AiTaskKeywordService aiTaskKeywordService;
    @Mock
    private ChatMemoryService chatMemoryService;
    @Mock
    private ChatMemoryConfig chatMemoryConfig;
    @Mock
    private DefaultSystemPromptProvider defaultSystemPromptProvider;
    @Mock
    private ChatSummaryService chatSummaryService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        ChatMemoryConfig chatMemoryConfig = new ChatMemoryConfig();
        chatService = new ChatService(
                ollamaChatModel,
                chatClientBuilder,
                chatSessionRepository,
                chatMessageRepository,
                userRepository,
                aiTaskKeywordService,
                chatMemoryService,
                chatMemoryConfig,
                defaultSystemPromptProvider,
                chatSummaryService
        );
    }

    @Test
    void shouldKeepOriginalModelWhenUserSpecified() {
        String routedModel = ReflectionTestUtils.invokeMethod(
                chatService,
                "routeModelByQuestionLength",
                "请帮我分析一下这段代码",
                "custom-model",
                null
        );

        assertThat(routedModel).isEqualTo("custom-model");
    }

    @Test
    void shouldRouteToComplexModelWhenComplexTaskKeywordMatched() {
        org.mockito.Mockito.when(aiTaskKeywordService.isComplexTaskType(AiTaskKeywordService.TASK_TYPE_CODE)).thenReturn(true);

        String routedModel = ReflectionTestUtils.invokeMethod(
                chatService,
                "routeModelByQuestionLength",
                "请帮我重构这段代码",
                null,
                new AiTaskKeywordService.TaskKeywordMatch(AiTaskKeywordService.TASK_TYPE_CODE, "重构")
        );

        assertThat(routedModel).isEqualTo("deepseek-r1:32b");
    }

    @Test
    void shouldKeepShortTextTaskOnSimpleModelWhenMatchedTypeIsText() {
        org.mockito.Mockito.when(aiTaskKeywordService.isComplexTaskType(AiTaskKeywordService.TASK_TYPE_TEXT)).thenReturn(false);

        String routedModel = ReflectionTestUtils.invokeMethod(
                chatService,
                "routeModelByQuestionLength",
                "帮我润色",
                null,
                new AiTaskKeywordService.TaskKeywordMatch(AiTaskKeywordService.TASK_TYPE_TEXT, "润色")
        );

        assertThat(routedModel).isEqualTo("deepseek-r1:8b");
    }

    @Test
    void shouldFallbackToLengthRuleWhenNoKeywordMatched() {
        String longQuestion = "请详细说明在 Spring Boot 项目中如何设计一个支持会话记忆和流式输出的聊天服务";

        String routedModel = ReflectionTestUtils.invokeMethod(
                chatService,
                "routeModelByQuestionLength",
                longQuestion,
                null,
                null
        );

        assertThat(routedModel).isEqualTo("deepseek-r1:32b");
    }

    @Test
    void shouldUseInjectedDefaultSystemPromptWhenRequestPromptMissing() {
        when(defaultSystemPromptProvider.getPrompt()).thenReturn("默认系统提示词");

        String resolvedPrompt = ReflectionTestUtils.invokeMethod(
                chatService,
                "resolveSystemPrompt",
                null,
                null
        );

        assertThat(resolvedPrompt).isEqualTo("默认系统提示词");
    }

    @Test
    void shouldUseTaskTypePromptWhenMatchedAndRequestPromptMissing() {
        when(defaultSystemPromptProvider.getPromptByTaskType(AiTaskKeywordService.TASK_TYPE_CODE)).thenReturn("代码任务提示词");

        String resolvedPrompt = ReflectionTestUtils.invokeMethod(
                chatService,
                "resolveSystemPrompt",
                null,
                new AiTaskKeywordService.TaskKeywordMatch(AiTaskKeywordService.TASK_TYPE_CODE, "重构")
        );

        assertThat(resolvedPrompt).isEqualTo("代码任务提示词");
    }

    @Test
    void shouldStopTracingOlderMessagesImmediatelyWhenTokenLimitReached() {
        List<ChatMessage> history = List.of(
                ChatMessage.builder().chatRole("user").content("old").build(),
                ChatMessage.builder().chatRole("assistant").content("very long message ".repeat(100)).build(),
                ChatMessage.builder().chatRole("user").content("latest").build()
        );

        List<ChatMessage> selected = ReflectionTestUtils.invokeMethod(
                chatService,
                "selectHistoryMessagesByTokenLimit",
                history,
                20
        );

        assertThat(selected)
                .extracting(ChatMessage::getContent)
                .containsExactly("latest");
    }

    @Test
    void shouldKeepLatestMessageWhenSingleMessageAlreadyExceedsLimit() {
        List<ChatMessage> history = List.of(
                ChatMessage.builder().chatRole("user").content("oversized ".repeat(200)).build()
        );

        List<ChatMessage> selected = ReflectionTestUtils.invokeMethod(
                chatService,
                "selectHistoryMessagesByTokenLimit",
                history,
                10
        );

        assertThat(selected)
                .hasSize(1)
                .extracting(ChatMessage::getContent)
                .containsExactly("oversized ".repeat(200));
    }

    @Test
    void shouldMergeCurrentUserMessageIntoHistoryWhenRedisNotYetUpdated() {
        ChatMessage existingAssistant = ChatMessage.builder()
                .chatId(10L)
                .chatRole("assistant")
                .content("上一轮回复")
                .build();
        ChatMessage currentUser = ChatMessage.builder()
                .chatId(11L)
                .chatRole("user")
                .content("当前问题")
                .build();

        List<ChatMessage> merged = ReflectionTestUtils.invokeMethod(
                chatService,
                "mergeHistoryWithMessage",
                List.of(existingAssistant),
                currentUser
        );

        assertThat(merged)
                .extracting(ChatMessage::getContent)
                .containsExactly("上一轮回复", "当前问题");
    }
}

