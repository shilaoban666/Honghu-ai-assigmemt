package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.util.ConnectionHealthChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.net.ConnectException;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatModelGatewayServiceTest {

    @Mock
    private ObjectProvider<OllamaChatModel> ollamaChatModelProvider;
    @Mock
    private OllamaChatModel ollamaChatModel;
    @Mock
    private OpenAiCompatibleChatClient openAiCompatibleChatClient;
    @Mock
    private AiModelAccessService aiModelAccessService;
    @Mock
    private ConnectionHealthChecker connectionHealthChecker;

    private AiChatModelGatewayService gatewayService;
    private AiProviderProperties aiProviderProperties;
    private AiModelDefinition localModel;
    private AiModelDefinition fallbackModel;
    private List<Message> messages;

    @BeforeEach
    void setUp() {
        aiProviderProperties = new AiProviderProperties();
        aiProviderProperties.setProviders(new LinkedHashMap<>());

        AiProviderProperties.Provider localProvider = new AiProviderProperties.Provider();
        localProvider.setType(AiProviderProperties.ProviderType.OLLAMA_LOCAL);
        localProvider.setEnabled(true);

        AiProviderProperties.Provider deepseekProvider = new AiProviderProperties.Provider();
        deepseekProvider.setType(AiProviderProperties.ProviderType.OPENAI_COMPATIBLE);
        deepseekProvider.setEnabled(true);
        deepseekProvider.setBaseUrl("https://api.deepseek.com");
        deepseekProvider.setChatCompletionsPath("/v1/chat/completions");
        deepseekProvider.setUseApiKey(false);

        aiProviderProperties.getProviders().put("ollama-local", localProvider);
        aiProviderProperties.getProviders().put("deepseek-cloud", deepseekProvider);
        aiProviderProperties.getLocalModelFallback().setEnabled(true);
        aiProviderProperties.getLocalModelFallback().setLocalProviderCode("ollama-local");
        aiProviderProperties.getLocalModelFallback().setFallbackModel("deepseek-v3.2");

        gatewayService = new AiChatModelGatewayService(
                ollamaChatModelProvider,
                openAiCompatibleChatClient,
                aiProviderProperties,
                aiModelAccessService,
                connectionHealthChecker
        );

        localModel = AiModelDefinition.builder()
                .modelCode("deepseek-r1:8b")
                .providerCode("ollama-local")
                .apiModelName("deepseek-r1:8b")
                .localModel(true)
                .enabled(true)
                .build();
        fallbackModel = AiModelDefinition.builder()
                .modelCode("deepseek-v3.2")
                .providerCode("deepseek-cloud")
                .apiModelName("deepseek-v3.2")
                .localModel(false)
                .enabled(true)
                .build();
        messages = List.of(new UserMessage("你好"));
    }

    @Test
    void shouldUseDeepseekFallbackBeforeCallingLocalModelWhenOllamaUnavailable() {
        when(connectionHealthChecker.isOllamaAvailable()).thenReturn(false);
        when(aiModelAccessService.requireEnabledModel("deepseek-v3.2")).thenReturn(fallbackModel);
        when(openAiCompatibleChatClient.chat(eq(fallbackModel), any(), eq(messages), eq(null), eq(null)))
                .thenReturn(ChatResponse.success("cloud", "deepseek-v3.2"));

        ChatResponse response = gatewayService.chat(localModel, messages, null, null);

        assertThat(response.getModel()).isEqualTo("deepseek-v3.2");
        verify(openAiCompatibleChatClient).chat(eq(fallbackModel), any(), eq(messages), eq(null), eq(null));
    }

    @Test
    void shouldFallbackToDeepseekWhenLocalInvocationFailsAtRuntime() {
        when(connectionHealthChecker.isOllamaAvailable()).thenReturn(true);
        when(ollamaChatModelProvider.getIfAvailable()).thenReturn(ollamaChatModel);
        when(aiModelAccessService.requireEnabledModel("deepseek-v3.2")).thenReturn(fallbackModel);
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("connection refused", new ConnectException("connection refused")));
        when(openAiCompatibleChatClient.chat(eq(fallbackModel), any(), eq(messages), eq(null), eq(null)))
                .thenReturn(ChatResponse.success("cloud-after-fail", "deepseek-v3.2"));

        ChatResponse response = gatewayService.chat(localModel, messages, null, null);

        assertThat(response.getContent()).isEqualTo("cloud-after-fail");
        assertThat(response.getModel()).isEqualTo("deepseek-v3.2");
    }
}

