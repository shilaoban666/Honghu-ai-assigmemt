package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

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

import java.util.Optional;

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

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                ollamaChatModel,
                chatClientBuilder,
                chatSessionRepository,
                chatMessageRepository,
                userRepository,
                aiTaskKeywordService
        );
    }

    @Test
    void shouldKeepOriginalModelWhenUserSpecified() {
        String routedModel = ReflectionTestUtils.invokeMethod(
                chatService,
                "routeModelByQuestionLength",
                "请帮我分析一下这段代码",
                "custom-model"
        );

        assertThat(routedModel).isEqualTo("custom-model");
    }

    @Test
    void shouldRouteToComplexModelWhenComplexTaskKeywordMatched() {
        when(aiTaskKeywordService.findFirstMatch("请帮我重构这段代码")).thenReturn(
                Optional.of(new AiTaskKeywordService.TaskKeywordMatch(AiTaskKeywordService.TASK_TYPE_CODE, "重构"))
        );
        when(aiTaskKeywordService.isComplexTaskType(AiTaskKeywordService.TASK_TYPE_CODE)).thenReturn(true);

        String routedModel = ReflectionTestUtils.invokeMethod(
                chatService,
                "routeModelByQuestionLength",
                "请帮我重构这段代码",
                null
        );

        assertThat(routedModel).isEqualTo("deepseek-r1:32b");
    }

    @Test
    void shouldKeepShortTextTaskOnSimpleModelWhenMatchedTypeIsText() {
        when(aiTaskKeywordService.findFirstMatch("帮我润色")).thenReturn(
                Optional.of(new AiTaskKeywordService.TaskKeywordMatch(AiTaskKeywordService.TASK_TYPE_TEXT, "润色"))
        );
        when(aiTaskKeywordService.isComplexTaskType(AiTaskKeywordService.TASK_TYPE_TEXT)).thenReturn(false);

        String routedModel = ReflectionTestUtils.invokeMethod(
                chatService,
                "routeModelByQuestionLength",
                "帮我润色",
                null
        );

        assertThat(routedModel).isEqualTo("deepseek-r1:8b");
    }

    @Test
    void shouldFallbackToLengthRuleWhenNoKeywordMatched() {
        String longQuestion = "请详细说明在 Spring Boot 项目中如何设计一个支持会话记忆和流式输出的聊天服务";
        when(aiTaskKeywordService.findFirstMatch(longQuestion)).thenReturn(Optional.empty());

        String routedModel = ReflectionTestUtils.invokeMethod(
                chatService,
                "routeModelByQuestionLength",
                longQuestion,
                null
        );

        assertThat(routedModel).isEqualTo("deepseek-r1:32b");
    }
}

