package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.AiCallContext;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.CostBreakdown;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.QuotaCheckResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiUsageEvent;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.PricingNotConfiguredException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.QuotaExceededException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.util.ConnectionHealthChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	/**
	 * 这个网关是所有 AI 聊天调用的统一入口。
	 *
	 * <p>它负责的事情不只是“把请求发给模型”，还包括：</p>
	 * <ul>
	 * 	<li>根据模型 provider 决定走本地 Ollama 还是 OpenAI-compatible HTTP。</li>
	 * 	<li>本地 Ollama 不可用时，按配置回退到云端模型。</li>
	 * 	<li>调用前检查个人或企业 workspace 配额。</li>
	 * 	<li>调用后根据 token usage 和价格快照计算成本。</li>
	 * 	<li>无论成功、失败、超限还是缺价格，都尽量写入 ai_usage_event 作为审计流水。</li>
	 * </ul>
	 */

	private final ObjectProvider<OllamaChatModel> ollamaChatModelProvider;
	private final OpenAiCompatibleChatClient openAiCompatibleChatClient;
	private final AiProviderProperties aiProviderProperties;
	private final AiModelAccessService aiModelAccessService;
	private final ConnectionHealthChecker connectionHealthChecker;
	private final BillingService billingService;
	private final UsageEventService usageEventService;
	private final QuotaService quotaService;

	/**
	 * 执行一次非流式聊天调用。
	 *
	 * <p>调用方只需要关心“选中了哪个模型”，不需要再区分它是本地 Ollama 还是外部 provider。</p>
	 */
	public ChatResponse chat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		return chat(model, messages, temperature, maxTokens, AiCallContext.anonymous("legacy-chat"));
	}

	public ChatResponse chat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, ToolCallbackProvider toolCallbacks) {
		// 兼容旧调用方：没有传 AiCallContext 时仍然允许传工具集合，但审计来源标记为 legacy-chat。
		return chat(model, messages, temperature, maxTokens, AiCallContext.anonymous("legacy-chat"), toolCallbacks);
	}

	/**
	 * 执行一次带上下文的非流式聊天调用。
	 *
	 * <p>执行顺序是：准备上下文 -> 解析实际执行模型 -> 检查配额 -> 调上游 ->
	 * 算费用 -> 落成功流水。异常路径也会落失败流水。</p>
	 *
	 * @param model 用户请求或路由选中的模型
	 * @param messages Spring AI 消息列表
	 * @param temperature 温度参数，可为空
	 * @param maxTokens 最大输出 token，可为空
	 * @param context 用户、workspace、session、requestId 等调用上下文
	 * @return 完整的非流式 AI 回复
	 */
	public ChatResponse chat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, AiCallContext context) {
		return chat(model, messages, temperature, maxTokens, context, null);
	}

	public ChatResponse chat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, AiCallContext context, ToolCallbackProvider toolCallbacks) {
		// safeContext 会补齐 requestId/source，后面的配额检查和 usage event 都依赖它。
		AiCallContext safeContext = prepareContext(context, "chat");
		long startNs = System.nanoTime();
		// executableModel 可能和用户选择的 model 不同，例如本地 Ollama 不可用时会切到云端回退模型。
		AiModelDefinition executableModel = resolveExecutableModel(model);
		AiProviderProperties.Provider provider = resolveProvider(executableModel.getProviderCode());
		// 把可信调用上下文（userId/sessionId/messageId/query）转成工具上下文 Map。
		// 它会随每个工具回调一起下传，是工具审计、内置上下文工具和 CLI 危险门正常工作的前提。
		Map<String, Object> toolContext = buildToolContext(safeContext);
		List<ToolCallback> normalizedToolCallbacks = normalizeToolCallbacks(toolCallbacks);
		try {
			checkQuota(safeContext, model, executableModel, startNs);
			ChatResponse response = switch (provider.getType()) {
				// 本地 Ollama 与 OpenAI-compatible 两条分支现在都接入工具调用与可信上下文。
				case OLLAMA_LOCAL -> chatWithLocalFallback(executableModel, messages, temperature, maxTokens, normalizedToolCallbacks, toolContext);
				case OPENAI_COMPATIBLE -> openAiCompatibleChatClient.chat(executableModel, provider, messages, temperature, maxTokens, normalizedToolCallbacks, toolContext);
			};
			CostBreakdown cost = billingService.calculate(response.getModel(), response.getTokenUsage(), Instant.now());
			usageEventService.recordSuccess(safeContext, model, executableModel, response.getTokenUsage(), cost, elapsedMs(startNs));
			return response;
		} catch (QuotaExceededException ex) {
			throw ex;
		} catch (PricingNotConfiguredException ex) {
			usageEventService.recordFailure(safeContext, model, executableModel, elapsedMs(startNs), AiUsageEvent.Status.BLOCKED_BY_PRICING, ex.getMessage());
			throw ex;
		} catch (RuntimeException ex) {
			usageEventService.recordFailure(safeContext, model, executableModel, elapsedMs(startNs), AiUsageEvent.Status.FAILED, ex.getMessage());
			throw ex;
		}
	}

	/**
	 * 执行一次流式聊天调用。
	 *
	 * <p>返回统一的 {@link ChatResponse} 分片流，方便上层直接复用既有 SSE 输出逻辑。</p>
	 */
	public Flux<ChatResponse> streamChat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens) {
		return streamChat(model, messages, temperature, maxTokens, AiCallContext.anonymous("legacy-stream"));
	}

	public Flux<ChatResponse> streamChat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, ToolCallbackProvider toolCallbacks) {
		// 兼容旧流式调用方：允许传工具集合，但没有显式用户上下文时按 legacy-stream 记账。
		return streamChat(model, messages, temperature, maxTokens, AiCallContext.anonymous("legacy-stream"), toolCallbacks);
	}

	/**
	 * 执行一次带上下文的流式聊天调用。
	 *
	 * <p>流式调用的 token usage 通常在最后一帧才返回，所以这里会持续收集分片内容和最后一次 usage。
	 * 流结束时统一计费并落 SUCCESS 流水；如果 provider 没有返回 usage，则用字符数估算一个兜底 usage，
	 * 避免流式调用在成本系统里变成黑洞。</p>
	 */
	public Flux<ChatResponse> streamChat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, AiCallContext context) {
		return streamChat(model, messages, temperature, maxTokens, context, null);
	}

	public Flux<ChatResponse> streamChat(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, AiCallContext context, ToolCallbackProvider toolCallbacks) {
		// 流式调用开始前就解析安全上下文，后续 doOnComplete/doOnError 都要用它落 usage event。
		AiCallContext safeContext = prepareContext(context, "stream");
		long startNs = System.nanoTime();
		AiModelDefinition executableModel = resolveExecutableModel(model);
		AiProviderProperties.Provider provider = resolveProvider(executableModel.getProviderCode());
		checkQuota(safeContext, model, executableModel, startNs);
		Map<String, Object> toolContext = buildToolContext(safeContext);
		List<ToolCallback> normalizedToolCallbacks = normalizeToolCallbacks(toolCallbacks);
		StringBuilder content = new StringBuilder();
		ChatResponse.TokenUsage[] lastUsage = new ChatResponse.TokenUsage[1];
		Flux<ChatResponse> upstream = switch (provider.getType()) {
			// 本地 Ollama 分支负责把工具集合放进 OllamaOptions。
			case OLLAMA_LOCAL -> streamWithLocalFallback(executableModel, messages, temperature, maxTokens, normalizedToolCallbacks, toolContext);
			// OpenAI-compatible 客户端现在也支持流式工具调用：解析 tool_call 增量、回环执行、再继续流式回复。
			case OPENAI_COMPATIBLE -> openAiCompatibleChatClient.streamChat(executableModel, provider, messages, temperature, maxTokens, normalizedToolCallbacks, toolContext);
		};
		return upstream
				.doOnNext(response -> {
					if (response.getContent() != null) {
						content.append(response.getContent());
					}
					if (response.getTokenUsage() != null) {
						lastUsage[0] = response.getTokenUsage();
					}
				})
				.doOnComplete(() -> {
					try {
						ChatResponse.TokenUsage usage = lastUsage[0] != null ? lastUsage[0] : estimateUsage(messages, content.toString());
						CostBreakdown cost = billingService.calculate(executableModel.getModelCode(), usage, Instant.now());
						usageEventService.recordSuccess(safeContext, model, executableModel, usage, cost, elapsedMs(startNs));
					} catch (PricingNotConfiguredException ex) {
						usageEventService.recordFailure(safeContext, model, executableModel, elapsedMs(startNs), AiUsageEvent.Status.BLOCKED_BY_PRICING, ex.getMessage());
					}
				})
				.doOnError(error -> usageEventService.recordFailure(
						safeContext,
						model,
						executableModel,
						elapsedMs(startNs),
						error instanceof QuotaExceededException ? AiUsageEvent.Status.BLOCKED_BY_QUOTA : AiUsageEvent.Status.FAILED,
						error.getMessage()));
	}

	/**
	 * 本地模型的非流式调用包装器。
	 *
	 * <p>如果运行期发现 Ollama 端口不可用，会自动切到配置好的云端回退模型，
	 * 避免把“connection refused”直接抛给上层业务。</p>
	 */
	private ChatResponse chatWithLocalFallback(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, List<ToolCallback> toolCallbacks, Map<String, Object> toolContext) {
		try {
			return chatWithLocalOllama(model, messages, temperature, maxTokens, toolCallbacks, toolContext);
		} catch (RuntimeException ex) {
			AiModelDefinition fallbackModel = resolveFallbackModelAfterLocalFailure(model, ex);
			if (fallbackModel == null) {
				throw ex;
			}
			AiProviderProperties.Provider fallbackProvider = resolveProvider(fallbackModel.getProviderCode());
			// 回退到云端时同样把工具集合和可信上下文带过去，避免“本地有工具、回退后无工具”的能力断层。
			return openAiCompatibleChatClient.chat(fallbackModel, fallbackProvider, messages, temperature, maxTokens, toolCallbacks, toolContext);
		}
	}

	/**
	 * 本地模型的流式调用包装器。
	 */
	private Flux<ChatResponse> streamWithLocalFallback(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, List<ToolCallback> toolCallbacks, Map<String, Object> toolContext) {
		return Flux.defer(() -> streamWithLocalOllama(model, messages, temperature, maxTokens, toolCallbacks, toolContext))
				.onErrorResume(ex -> {
					AiModelDefinition fallbackModel = resolveFallbackModelAfterLocalFailure(model, ex);
					if (fallbackModel == null) {
						return Flux.error(ex);
					}
					AiProviderProperties.Provider fallbackProvider = resolveProvider(fallbackModel.getProviderCode());
					// 流式回退云端时同样保留工具能力与可信上下文。
					return openAiCompatibleChatClient.streamChat(fallbackModel, fallbackProvider, messages, temperature, maxTokens, toolCallbacks, toolContext);
				});
	}

	/**
	 * 走本地 Ollama 的非流式调用。
	 */
	private ChatResponse chatWithLocalOllama(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, List<ToolCallback> toolCallbacks, Map<String, Object> toolContext) {
		OllamaChatModel ollamaChatModel = requireOllamaChatModel();
		OllamaOptions.Builder builder = OllamaOptions.builder().model(model.getApiModelName());
		if (temperature != null) {
			builder.temperature(temperature);
		}
		if (maxTokens != null) {
			builder.numPredict(maxTokens);
		}
		List<ToolCallback> normalizedToolCallbacks = toolCallbacks == null ? List.of() : toolCallbacks;
		if (!normalizedToolCallbacks.isEmpty()) {
			// toolCallbacks 告诉 Spring AI 当前 prompt 可以调用哪些工具。
			builder.toolCallbacks(normalizedToolCallbacks);
			// 开启内部工具执行后，Spring AI 会在模型发起 tool call 时自动调用 ToolCallback。
			builder.internalToolExecutionEnabled(Boolean.TRUE);
			// 可信上下文随工具调用一起下传，AuditingToolCallback 据此还原 userId/sessionId/messageId。
			if (toolContext != null && !toolContext.isEmpty()) {
				builder.toolContext(toolContext);
			}
		}
		OllamaOptions options = builder.build();
		Prompt prompt = new Prompt(messages, options);
		var response = ollamaChatModel.call(prompt);
		return ChatResponse.builder()
				.content(response.getResult().getOutput().getText())
				.model(model.getModelCode())
				.timestamp(System.currentTimeMillis())
				.success(true)
				.tokenUsage(ChatResponse.TokenUsage.builder()
						.promptTokens(response.getMetadata().getUsage().getPromptTokens().intValue())
						.completionTokens(response.getMetadata().getUsage().getCompletionTokens().intValue())
						.cachedPromptTokens(0)
						.totalTokens(response.getMetadata().getUsage().getTotalTokens().intValue())
						.build())
				.build();
	}

	/**
	 * 走本地 Ollama 的流式调用。
	 */
	private Flux<ChatResponse> streamWithLocalOllama(AiModelDefinition model, List<Message> messages, Double temperature, Integer maxTokens, List<ToolCallback> toolCallbacks, Map<String, Object> toolContext) {
		OllamaChatModel ollamaChatModel = requireOllamaChatModel();
		OllamaOptions.Builder builder = OllamaOptions.builder().model(model.getApiModelName());
		if (temperature != null) {
			builder.temperature(temperature);
		}
		if (maxTokens != null) {
			builder.numPredict(maxTokens);
		}
		List<ToolCallback> normalizedToolCallbacks = toolCallbacks == null ? List.of() : toolCallbacks;
		if (!normalizedToolCallbacks.isEmpty()) {
			// 流式模式同样注入工具集合，保证非流式和流式能力一致。
			builder.toolCallbacks(normalizedToolCallbacks);
			// 让 Spring AI 负责执行工具调用并把结果继续交还给模型。
			builder.internalToolExecutionEnabled(Boolean.TRUE);
			// 流式工具调用同样需要可信上下文，否则审计日志与上下文工具会丢身份。
			if (toolContext != null && !toolContext.isEmpty()) {
				builder.toolContext(toolContext);
			}
		}
		OllamaOptions options = builder.build();
		Prompt prompt = new Prompt(messages, options);
		return ollamaChatModel.stream(prompt)
				.map(response -> ChatResponse.builder()
						.content(response.getResult().getOutput().getText())
						.model(model.getModelCode())
						.timestamp(System.currentTimeMillis())
						.success(true)
						.tokenUsage(response.getMetadata() == null || response.getMetadata().getUsage() == null
								? null
						: ChatResponse.TokenUsage.builder()
								.promptTokens(response.getMetadata().getUsage().getPromptTokens().intValue())
								.completionTokens(response.getMetadata().getUsage().getCompletionTokens().intValue())
								.cachedPromptTokens(0)
								.totalTokens(response.getMetadata().getUsage().getTotalTokens().intValue())
								.build())
						.build());
	}

	/**
	 * 整理调用上下文，确保 requestId、attemptNo 和 source 可用。
	 *
	 * <p>requestId 是 usage event 的追踪主键之一，不能等到出错时才生成。</p>
	 */
	private AiCallContext prepareContext(AiCallContext context, String source) {
		AiCallContext safeContext = context == null ? AiCallContext.anonymous(source) : context;
		safeContext.ensureRequestId();
		if (safeContext.getSource() == null) {
			safeContext.setSource(source);
		}
		return safeContext;
	}

	/**
	 * 调用上游模型之前检查配额。
	 *
	 * <p>配额不通过时，会先写一条 BLOCKED_BY_QUOTA 流水，再抛出 {@link QuotaExceededException}。
	 * 这样前端能收到 429，后台也能看到拦截记录。</p>
	 */
	private void checkQuota(AiCallContext context, AiModelDefinition requestedModel, AiModelDefinition executableModel, long startNs) {
		QuotaCheckResult quota = quotaService.checkBeforeCall(context.getUserId(), context.getWorkspaceId());
		if (!quota.isAllowed()) {
			usageEventService.recordFailure(context, requestedModel, executableModel, elapsedMs(startNs), AiUsageEvent.Status.BLOCKED_BY_QUOTA, quota.getReason());
			throw new QuotaExceededException(quota);
		}
	}

	/**
	 * provider 没返回 usage 时的兜底估算。
	 *
	 * <p>这不是精确 tokenizer，只是为了避免流式响应完全没有 token 数据。
	 * 后续如果接入 provider 官方 tokenizer，可以替换这里。</p>
	 */
	private ChatResponse.TokenUsage estimateUsage(List<Message> messages, String completion) {
		int promptChars = messages == null ? 0 : messages.stream()
				.filter(message -> message != null && message.getText() != null)
				.mapToInt(message -> message.getText().length())
				.sum();
		int completionChars = completion == null ? 0 : completion.length();
		int promptTokens = Math.max(1, promptChars / 2);
		int completionTokens = Math.max(1, completionChars / 2);
		return ChatResponse.TokenUsage.builder()
				.promptTokens(promptTokens)
				.completionTokens(completionTokens)
				.cachedPromptTokens(0)
				.totalTokens(promptTokens + completionTokens)
				.build();
	}

	/**
	 * 计算一次调用已经耗费的毫秒数。
	 *
	 * <p>统一在这里做纳秒到毫秒的换算，避免成功和失败分支各自重复写一套时间计算。</p>
	 */
	private long elapsedMs(long startNs) {
		return (System.nanoTime() - startNs) / 1_000_000L;
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

	/**
	 * 当“预判健康检查没发现问题，但真正调用仍然失败”时，尝试切云端回退模型。
	 *
	 * <p>这一步只在明确识别为本地连接异常时才触发，避免把模型业务异常误判成“应该回退”。</p>
	 */
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

	/**
	 * 从配置中解析真正可用的云端回退模型。
	 *
	 * <p>这里会顺手过滤几类无效配置：</p>
	 * <ul>
	 * 	<li>没开回退功能</li>
	 * 	<li>没配 fallbackModel</li>
	 * 	<li>fallback 模型不存在或被禁用</li>
	 * 	<li>fallback 仍然指向本地 provider，导致“回退后还是本地”</li>
	 * </ul>
	 */
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

	/**
	 * 获取已初始化的 OllamaChatModel Bean。
	 *
	 * <p>如果当前应用根本没有启用本地 Ollama，这里会直接抛出清晰错误，提醒去检查配置。</p>
	 */
	private OllamaChatModel requireOllamaChatModel() {
		OllamaChatModel ollamaChatModel = ollamaChatModelProvider.getIfAvailable();
		if (ollamaChatModel == null) {
			throw new IllegalStateException("当前未初始化 OllamaChatModel，请检查 spring.ai.ollama 配置或直接使用云端 provider");
		}
		return ollamaChatModel;
	}

	private List<ToolCallback> normalizeToolCallbacks(ToolCallbackProvider toolCallbacks) {
		if (toolCallbacks == null || toolCallbacks.getToolCallbacks() == null) {
			// 没有工具时返回空列表，调用方就不会开启 internalToolExecutionEnabled。
			return List.of();
		}
		// ToolCallbackProvider 暴露的是数组，这里转成 List 便于判空和交给 OllamaOptions。
		return Arrays.asList(toolCallbacks.getToolCallbacks());
	}

	/**
	 * 把平台内部调用上下文转换成工具可信上下文 Map。
	 *
	 * <p>模型只允许控制工具参数；userId / sessionId / messageId / query 这类安全敏感字段
	 * 必须由后端通过 ToolContext 传入，再由 AuditingToolCallback 还原成
	 * ToolExecutionContext 供内置工具读取。这里只放入有值的字段，避免污染上下文。</p>
	 *
	 * @param context 已补齐的安全调用上下文
	 * @return 工具上下文 Map，可能为空
	 */
	private Map<String, Object> buildToolContext(AiCallContext context) {
		Map<String, Object> toolContext = new LinkedHashMap<>();
		if (context == null) {
			return toolContext;
		}
		if (StringUtils.hasText(context.getUserId())) {
			toolContext.put("userId", context.getUserId());
		}
		if (StringUtils.hasText(context.getSessionId())) {
			toolContext.put("sessionId", context.getSessionId());
		}
		if (context.getChatId() != null) {
			// 工具审计/上下文统一用 messageId 表示触发本次调用的用户消息 ID。
			toolContext.put("messageId", context.getChatId());
		}
		if (StringUtils.hasText(context.getQuery())) {
			toolContext.put("query", context.getQuery());
		}
		return toolContext;
	}

	/**
	 * 判断异常链是否属于“本地连接失败”。
	 *
	 * <p>这里既看异常类型，也看 message 关键字，是因为不同 HTTP 客户端/底层实现抛出来的异常包装层次不一致。</p>
	 */
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



