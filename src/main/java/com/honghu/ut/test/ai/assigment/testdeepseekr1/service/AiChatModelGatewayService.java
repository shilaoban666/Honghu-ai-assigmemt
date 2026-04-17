package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.util.ConnectionHealthChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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

	private final ObjectProvider<OllamaChatModel> ollamaChatModelProvider;
	private final OpenAiCompatibleChatClient openAiCompatibleChatClient;
	private final AiProviderProperties aiProviderProperties;
	private final AiModelAccessService aiModelAccessService;
	private final ConnectionHealthChecker connectionHealthChecker;

	/**
	 * 执行一次非流式聊天调用。
	 *
	 * <p>调用方只需要关心“选中了哪个模型”，不需要再区分它是本地 Ollama 还是外部 provider。</p>
	 */
	public ChatResponse chat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		AiModelDefinition executableModel = resolveExecutableModel(model);
		AiProviderProperties.Provider provider = resolveProvider(executableModel.getProviderCode());
		return switch (provider.getType()) {
			case OLLAMA_LOCAL -> chatWithLocalFallback(executableModel, messages, temperature, maxTokens);
			case OPENAI_COMPATIBLE -> openAiCompatibleChatClient.chat(executableModel, provider, messages, temperature, maxTokens);
		};
	}

	/**
	 * 执行一次流式聊天调用。
	 *
	 * <p>返回统一的 {@link ChatResponse} 分片流，方便上层直接复用既有 SSE 输出逻辑。</p>
	 */
	public Flux<ChatResponse> streamChat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		AiModelDefinition executableModel = resolveExecutableModel(model);
		AiProviderProperties.Provider provider = resolveProvider(executableModel.getProviderCode());
		return switch (provider.getType()) {
			case OLLAMA_LOCAL -> streamWithLocalFallback(executableModel, messages, temperature, maxTokens);
			case OPENAI_COMPATIBLE -> openAiCompatibleChatClient.streamChat(executableModel, provider, messages, temperature, maxTokens);
		};
	}

	/**
	 * 本地模型的非流式调用包装器。
	 *
	 * <p>如果运行期发现 Ollama 端口不可用，会自动切到配置好的云端回退模型，
	 * 避免把“connection refused”直接抛给上层业务。</p>
	 */
	private ChatResponse chatWithLocalFallback(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		try {
			return chatWithLocalOllama(model, messages, temperature, maxTokens);
		} catch (RuntimeException ex) {
			AiModelDefinition fallbackModel = resolveFallbackModelAfterLocalFailure(model, ex);
			if (fallbackModel == null) {
				throw ex;
			}
			AiProviderProperties.Provider fallbackProvider = resolveProvider(fallbackModel.getProviderCode());
			return openAiCompatibleChatClient.chat(fallbackModel, fallbackProvider, messages, temperature, maxTokens);
		}
	}

	/**
	 * 本地模型的流式调用包装器。
	 */
	private Flux<ChatResponse> streamWithLocalFallback(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		return Flux.defer(() -> streamWithLocalOllama(model, messages, temperature, maxTokens))
				.onErrorResume(ex -> {
					AiModelDefinition fallbackModel = resolveFallbackModelAfterLocalFailure(model, ex);
					if (fallbackModel == null) {
						return Flux.error(ex);
					}
					AiProviderProperties.Provider fallbackProvider = resolveProvider(fallbackModel.getProviderCode());
					return openAiCompatibleChatClient.streamChat(fallbackModel, fallbackProvider, messages, temperature, maxTokens);
				});
	}

	/**
	 * 走本地 Ollama 的非流式调用。
	 */
	private ChatResponse chatWithLocalOllama(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		OllamaChatModel ollamaChatModel = requireOllamaChatModel();
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
		OllamaChatModel ollamaChatModel = requireOllamaChatModel();
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

	/**
	 * 在真正调用模型前，先根据本地 Ollama 健康状态决定是否直接切换到云端回退模型。
	 */
	private AiModelDefinition resolveExecutableModel(AiModelDefinition requestedModel) {
		if (requestedModel == null) {
			throw new IllegalArgumentException("模型定义不能为空");
		}

		AiProviderProperties.LocalModelFallback fallbackConfig = aiProviderProperties.getLocalModelFallback();
		if (fallbackConfig == null || !fallbackConfig.isEnabled()) {
			return requestedModel;
		}
		if (!fallbackConfig.getLocalProviderCode().equals(requestedModel.getProviderCode())) {
			return requestedModel;
		}
		if (connectionHealthChecker.isOllamaAvailable()) {
			return requestedModel;
		}

		AiModelDefinition fallbackModel = resolveConfiguredFallbackModel(requestedModel);
		if (fallbackModel == null) {
			return requestedModel;
		}
		log.warn("本地 Ollama 当前不可用，模型从 {} 自动回退到 {}", requestedModel.getModelCode(), fallbackModel.getModelCode());
		return fallbackModel;
	}

	private AiModelDefinition resolveFallbackModelAfterLocalFailure(AiModelDefinition requestedModel, Throwable throwable) {
		if (!isLocalConnectionFailure(throwable)) {
			return null;
		}
		AiModelDefinition fallbackModel = resolveConfiguredFallbackModel(requestedModel);
		if (fallbackModel != null) {
			log.warn("本地 Ollama 调用失败，模型从 {} 自动回退到 {}，原因：{}",
					requestedModel.getModelCode(),
					fallbackModel.getModelCode(),
					throwable.getMessage());
		}
		return fallbackModel;
	}

	private AiModelDefinition resolveConfiguredFallbackModel(AiModelDefinition requestedModel) {
		AiProviderProperties.LocalModelFallback fallbackConfig = aiProviderProperties.getLocalModelFallback();
		if (fallbackConfig == null || !fallbackConfig.isEnabled() || !StringUtils.hasText(fallbackConfig.getFallbackModel())) {
			return null;
		}

		AiModelDefinition fallbackModel;
		try {
			fallbackModel = aiModelAccessService.requireEnabledModel(fallbackConfig.getFallbackModel());
		} catch (IllegalArgumentException ex) {
			log.warn("配置的云端回退模型不存在或未启用：{}", fallbackConfig.getFallbackModel());
			return null;
		}

		if (requestedModel.getModelCode().equals(fallbackModel.getModelCode())) {
			return null;
		}
		if (fallbackConfig.getLocalProviderCode().equals(fallbackModel.getProviderCode())) {
			log.warn("云端回退模型 {} 仍然指向本地 provider，已忽略该回退配置", fallbackModel.getModelCode());
			return null;
		}
		return fallbackModel;
	}

	private OllamaChatModel requireOllamaChatModel() {
		OllamaChatModel ollamaChatModel = ollamaChatModelProvider.getIfAvailable();
		if (ollamaChatModel == null) {
			throw new IllegalStateException("当前未初始化 OllamaChatModel，请检查 spring.ai.ollama 配置或直接使用云端 provider");
		}
		return ollamaChatModel;
	}

	private boolean isLocalConnectionFailure(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			String message = current.getMessage();
			if (message != null) {
				String lowerCaseMessage = message.toLowerCase();
				if (lowerCaseMessage.contains("connection refused")
						|| lowerCaseMessage.contains("connect timed out")
						|| lowerCaseMessage.contains("failed to connect")
						|| lowerCaseMessage.contains("closedchannel")
						|| lowerCaseMessage.contains("connection reset")) {
					return true;
				}
			}
			if (current instanceof java.net.ConnectException || current instanceof java.net.SocketTimeoutException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}



