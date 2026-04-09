package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 统一 AI 模型调用网关。
 *
 * <p>本地 localhost / Ollama 模型继续复用 Spring AI；
 * 其他外部模型则走 OpenAI 兼容 HTTP 客户端。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatModelGatewayService {

	private final OllamaChatModel ollamaChatModel;
	private final OpenAiCompatibleChatClient openAiCompatibleChatClient;
	private final AiProviderProperties aiProviderProperties;

	/**
	 * 执行一次非流式聊天调用。
	 *
	 * <p>调用方只需要关心“选中了哪个模型”，不需要再区分它是本地 Ollama 还是外部 provider。</p>
	 */
	public ChatResponse chat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		AiProviderProperties.Provider provider = resolveProvider(model.getProviderCode());
		return switch (provider.getType()) {
			case OLLAMA_LOCAL -> chatWithLocalOllama(model, messages, temperature, maxTokens);
			case OPENAI_COMPATIBLE -> openAiCompatibleChatClient.chat(model, provider, messages, temperature, maxTokens);
		};
	}

	/**
	 * 执行一次流式聊天调用。
	 *
	 * <p>返回统一的 {@link ChatResponse} 分片流，方便上层直接复用既有 SSE 输出逻辑。</p>
	 */
	public Flux<ChatResponse> streamChat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		AiProviderProperties.Provider provider = resolveProvider(model.getProviderCode());
		return switch (provider.getType()) {
			case OLLAMA_LOCAL -> streamWithLocalOllama(model, messages, temperature, maxTokens);
			case OPENAI_COMPATIBLE -> openAiCompatibleChatClient.streamChat(model, provider, messages, temperature, maxTokens);
		};
	}

	/**
	 * 走本地 Ollama 的非流式调用。
	 */
	private ChatResponse chatWithLocalOllama(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		OllamaOptions options = OllamaOptions.create().withModel(model.getApiModelName());
		if (temperature != null) {
			options = options.withTemperature(temperature);
		}
		if (maxTokens != null) {
			options = options.withNumPredict(maxTokens);
		}
		Prompt prompt = new Prompt(messages, options);
		var response = ollamaChatModel.call(prompt);
		return ChatResponse.builder()
				.content(response.getResult().getOutput().getContent())
				.model(model.getModelCode())
				.timestamp(System.currentTimeMillis())
				.success(true)
				.tokenUsage(ChatResponse.TokenUsage.builder()
						.promptTokens(response.getMetadata().getUsage().getPromptTokens().intValue())
						.completionTokens(response.getMetadata().getUsage().getGenerationTokens().intValue())
						.totalTokens(response.getMetadata().getUsage().getTotalTokens().intValue())
						.build())
				.build();
	}

	/**
	 * 走本地 Ollama 的流式调用。
	 */
	private Flux<ChatResponse> streamWithLocalOllama(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		OllamaOptions options = OllamaOptions.create().withModel(model.getApiModelName());
		if (temperature != null) {
			options = options.withTemperature(temperature);
		}
		if (maxTokens != null) {
			options = options.withNumPredict(maxTokens);
		}
		Prompt prompt = new Prompt(messages, options);
		return ollamaChatModel.stream(prompt)
				.map(response -> ChatResponse.builder()
						.content(response.getResult().getOutput().getContent())
						.model(model.getModelCode())
						.timestamp(System.currentTimeMillis())
						.success(true)
						.build());
	}

	/**
	 * 解析 provider 配置。
	 *
	 * <p>模型目录表中只保存 provider_code，真正的 URL / key / header 都来自配置文件。</p>
	 */
	private AiProviderProperties.Provider resolveProvider(String providerCode) {
		AiProviderProperties.Provider provider = aiProviderProperties.getProviders().get(providerCode);
		if (provider == null) {
			throw new IllegalStateException("未找到 provider 配置: " + providerCode);
		}
		if (!provider.isEnabled()) {
			throw new IllegalStateException("provider 已禁用: " + providerCode);
		}
		return provider;
	}
}



