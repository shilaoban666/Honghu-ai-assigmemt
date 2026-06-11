package com.honghu.ai.assigment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.config.properties.AiProviderProperties;
import com.honghu.ai.assigment.dto.ChatResponse;
import com.honghu.ai.assigment.entity.AiModelDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAI 兼容协议客户端。
 *
 * <p>除本地 localhost / Ollama 之外的模型统一走这层，
 * 通过配置文件控制 baseUrl、apiKey、header 与 path。</p>
 *
 * <p>这一层现在原生支持 OpenAI function calling：当调用方传入 {@link ToolCallback} 集合时，
 * 请求体会带上 {@code tools}，客户端会解析 {@code tool_calls}、在本地执行工具回调、把结果作为
 * {@code role=tool} 消息回喂模型，循环若干轮直到模型给出最终文本回复。这样云端模型（DeepSeek、
 * Qwen、Gemini 等）也能真正使用技能工具，而不再是“只有本地 Ollama 才有工具”。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiCompatibleChatClient {

    // 统一的 JSON MediaType；所有 OpenAI-compatible 请求体都是 UTF-8 JSON。
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    // 工具调用回环的最大轮数，超过后强制不带 tools 让模型直接给出文本回复，避免死循环。
    private static final int MAX_TOOL_ROUNDS = 5;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    // Jackson 用于把请求 DTO 序列化成 JSON，也用于把上游 JSON 响应反序列化成内部 DTO。
    private final ObjectMapper objectMapper;
    // 使用网关专用 OkHttpClient，连接池、超时、代理等配置可以和普通业务 HTTP 客户端隔离。
    @Qualifier("aiGatewayOkHttpClient")
    private final OkHttpClient okHttpClient;

    /**
     * 发起一次非流式 OpenAI-compatible 聊天调用（无工具）。
     */
    public ChatResponse chat(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens) {
        return chat(model, provider, messages, temperature, maxTokens, List.of(), Map.of());
    }

    /**
     * 发起一次非流式 OpenAI-compatible 聊天调用，支持 function calling。
     *
     * <p>当 {@code tools} 非空时，会按 OpenAI function calling 协议循环执行工具调用，
     * 直到模型返回不含 {@code tool_calls} 的最终回复，或达到 {@link #MAX_TOOL_ROUNDS} 轮上限。</p>
     *
     * @param tools 可调用工具集合；为空表示纯聊天
     * @param toolContext 后端注入的可信上下文（userId/sessionId 等），随每次工具回调下传
     */
    public ChatResponse chat(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens,
            List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        try {
            List<ApiMessage> conversation = new ArrayList<>(toApiMessages(messages));
            UsageAccumulator usage = new UsageAccumulator();
            List<ToolCallback> safeTools = tools == null ? List.of() : tools;

            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                // 达到上限后最后一轮强制不带 tools，逼模型用文本回答，保证循环一定收敛。
                List<ToolCallback> roundTools = round < MAX_TOOL_ROUNDS ? safeTools : List.of();
                OpenAiChatResponse chatResponse = executeNonStreaming(model, provider, conversation, temperature, maxTokens, roundTools);
                usage.add(chatResponse == null ? null : chatResponse.getUsage());

                ChoiceMessage message = firstMessage(chatResponse);
                List<ToolCall> toolCalls = message == null ? null : message.getToolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    String content = message == null || message.getContent() == null ? "" : message.getContent();
                    return buildChatResponse(model.getModelCode(), content, usage);
                }

                // 把模型这一轮的 assistant + tool_calls 决策回写进对话，再逐个执行工具。
                conversation.add(assistantToolCallMessage(message.getContent(), toolCalls));
                for (ToolCall toolCall : toolCalls) {
                    String result = invokeTool(safeTools, toolCall, toolContext);
                    conversation.add(toolResultMessage(toolCall.getId(), result));
                }
            }
            // 理论上不会走到这里（上限轮已强制无工具），兜底返回空内容。
            return buildChatResponse(model.getModelCode(), "", usage);
        } catch (Exception e) {
            // 对外统一包装成 RuntimeException，让上层服务能按模型调用失败处理。
            throw new RuntimeException("调用外部模型失败(model=" + model.getModelCode() + "): " + e.getMessage(), e);
        }
    }

    /**
     * 发起一次流式 OpenAI-compatible 聊天调用（无工具）。
     */
    public Flux<ChatResponse> streamChat(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens) {
        return streamChat(model, provider, messages, temperature, maxTokens, List.of(), Map.of());
    }

    /**
     * 发起一次流式 OpenAI-compatible 聊天调用，支持 function calling。
     *
     * <p>纯聊天时与旧行为一致：逐 token 流式输出。带工具时，会解析流式 {@code tool_call} 增量，
     * 在工具调用轮内仍然把 assistant 文本增量实时下发，遇到 {@code tool_calls} 则本地执行后开启下一轮，
     * 最终一轮把模型的文本回复继续逐 token 流式返回。</p>
     */
    public Flux<ChatResponse> streamChat(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens,
            List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        List<ToolCallback> safeTools = tools == null ? List.of() : tools;
        if (safeTools.isEmpty()) {
            // 没有工具时保持原始纯流式路径，首字延迟和行为完全不变。
            return Flux.<ChatResponse>create(sink ->
                            executeStreamingRequest(sink, model, provider, toApiMessages(messages), temperature, maxTokens))
                    .subscribeOn(Schedulers.boundedElastic());
        }
        return Flux.<ChatResponse>create(sink ->
                        executeStreamingWithTools(sink, model, provider, messages, temperature, maxTokens, safeTools, toolContext))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ---------------------------------------------------------------------
    // 非流式执行
    // ---------------------------------------------------------------------

    /**
     * 执行一轮非流式请求并返回解析后的响应。
     */
    private OpenAiChatResponse executeNonStreaming(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<ApiMessage> conversation,
            Double temperature,
            Integer maxTokens,
            List<ToolCallback> tools) throws IOException {
        Request request = buildHttpRequest(model, provider, conversation, temperature, maxTokens, false, tools);
        try (Response response = okHttpClient.newCall(request).execute()) {
            ensureSuccessful(response, model.getModelCode());
            return readBody(response.body(), OpenAiChatResponse.class);
        }
    }

    // ---------------------------------------------------------------------
    // 流式执行（无工具）
    // ---------------------------------------------------------------------

    /**
     * 真正执行流式 HTTP 请求并把上游返回的 delta 内容拆成 ChatResponse 分片。
     */
    private void executeStreamingRequest(
            FluxSink<ChatResponse> sink,
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<ApiMessage> conversation,
            Double temperature,
            Integer maxTokens) {
        try {
            Request request = buildHttpRequest(model, provider, conversation, temperature, maxTokens, true, List.of());
            okhttp3.Call call = okHttpClient.newCall(request);
            sink.onCancel(call::cancel);

            try (Response response = call.execute()) {
                ensureSuccessful(response, model.getModelCode());
                ResponseBody body = response.body();
                if (body == null) {
                    sink.error(new IllegalStateException("流式响应 body 为空"));
                    return;
                }
                StreamRoundResult ignored = consumeStream(sink, body, model.getModelCode());
                sink.complete();
            }
        } catch (Exception e) {
            sink.error(new RuntimeException("调用外部模型流式接口失败(model=" + model.getModelCode() + "): " + e.getMessage(), e));
        }
    }

    // ---------------------------------------------------------------------
    // 流式执行（带工具）
    // ---------------------------------------------------------------------

    /**
     * 带工具的流式执行：在同一个阻塞线程里串联多轮流式请求。
     *
     * <p>每一轮：实时下发 assistant 文本增量，同时累积流式 {@code tool_call} 增量。
     * 若该轮出现工具调用，则本地执行工具、把结果回写对话并进入下一轮；
     * 否则说明模型已给出最终文本回复，结束流。</p>
     */
    private void executeStreamingWithTools(
            FluxSink<ChatResponse> sink,
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens,
            List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        try {
            List<ApiMessage> conversation = new ArrayList<>(toApiMessages(messages));
            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                List<ToolCallback> roundTools = round < MAX_TOOL_ROUNDS ? tools : List.of();
                Request request = buildHttpRequest(model, provider, conversation, temperature, maxTokens, true, roundTools);
                okhttp3.Call call = okHttpClient.newCall(request);
                sink.onCancel(call::cancel);

                StreamRoundResult roundResult;
                try (Response response = call.execute()) {
                    ensureSuccessful(response, model.getModelCode());
                    ResponseBody body = response.body();
                    if (body == null) {
                        sink.error(new IllegalStateException("流式响应 body 为空"));
                        return;
                    }
                    roundResult = consumeStream(sink, body, model.getModelCode());
                }

                List<ToolCall> toolCalls = roundResult.toToolCalls();
                if (toolCalls.isEmpty()) {
                    // 模型已经给出最终文本回复（增量已逐帧下发），结束。
                    sink.complete();
                    return;
                }
                // 出现工具调用：回写 assistant 决策、执行工具、把结果回喂，进入下一轮流式请求。
                conversation.add(assistantToolCallMessage(roundResult.content(), toolCalls));
                for (ToolCall toolCall : toolCalls) {
                    String result = invokeTool(tools, toolCall, toolContext);
                    conversation.add(toolResultMessage(toolCall.getId(), result));
                }
            }
            sink.complete();
        } catch (Exception e) {
            sink.error(new RuntimeException("调用外部模型流式接口失败(model=" + model.getModelCode() + "): " + e.getMessage(), e));
        }
    }

    /**
     * 读取一轮 SSE 流：实时下发文本/usage 分片，同时累积工具调用增量。
     *
     * @return 该轮累积到的文本内容与工具调用累加器
     */
    private StreamRoundResult consumeStream(FluxSink<ChatResponse> sink, ResponseBody body, String modelCode) throws IOException {
        StringBuilder content = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolAccumulators = new TreeMap<>();

        while (!body.source().exhausted()) {
            String line = body.source().readUtf8Line();
            if (!StringUtils.hasText(line) || !line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if (!StringUtils.hasText(data)) {
                continue;
            }
            if ("[DONE]".equals(data)) {
                break;
            }

            OpenAiChatChunk chunk = objectMapper.readValue(data, OpenAiChatChunk.class);
            if (chunk.getUsage() != null) {
                Usage usage = chunk.getUsage();
                sink.next(ChatResponse.builder()
                        .model(modelCode)
                        .timestamp(System.currentTimeMillis())
                        .success(true)
                        .tokenUsage(ChatResponse.TokenUsage.builder()
                                .promptTokens(usage.getPromptTokens())
                                .completionTokens(usage.getCompletionTokens())
                                .cachedPromptTokens(usage.getCachedPromptTokens())
                                .totalTokens(usage.getTotalTokens())
                                .build())
                        .build());
            }
            if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
                continue;
            }
            Delta delta = chunk.getChoices().get(0).getDelta();
            if (delta == null) {
                continue;
            }
            if (StringUtils.hasText(delta.getContent())) {
                content.append(delta.getContent());
                sink.next(ChatResponse.builder()
                        .content(delta.getContent())
                        .model(modelCode)
                        .timestamp(System.currentTimeMillis())
                        .success(true)
                        .build());
            }
            if (delta.getToolCalls() != null) {
                for (ToolCallDelta toolCallDelta : delta.getToolCalls()) {
                    int index = toolCallDelta.getIndex() == null ? 0 : toolCallDelta.getIndex();
                    ToolCallAccumulator accumulator = toolAccumulators.computeIfAbsent(index, ToolCallAccumulator::new);
                    if (StringUtils.hasText(toolCallDelta.getId())) {
                        accumulator.id = toolCallDelta.getId();
                    }
                    if (toolCallDelta.getFunction() != null) {
                        if (StringUtils.hasText(toolCallDelta.getFunction().getName())) {
                            accumulator.name = toolCallDelta.getFunction().getName();
                        }
                        if (toolCallDelta.getFunction().getArguments() != null) {
                            accumulator.arguments.append(toolCallDelta.getFunction().getArguments());
                        }
                    }
                }
            }
        }
        return new StreamRoundResult(content.toString(), toolAccumulators);
    }

    // ---------------------------------------------------------------------
    // 工具执行
    // ---------------------------------------------------------------------

    /**
     * 执行单个工具调用并返回工具结果字符串。
     *
     * <p>工具不存在或执行抛错时返回结构化 JSON 错误，让循环可以继续、模型能够理解失败原因，
     * 而不是直接中断整次对话。审计与危险等级管控由 {@code AuditingToolCallback} 装饰器内部完成。</p>
     */
    private String invokeTool(List<ToolCallback> tools, ToolCall toolCall, Map<String, Object> toolContext) {
        String name = toolCall.getFunction() == null ? null : toolCall.getFunction().getName();
        ToolCallback callback = tools.stream()
                .filter(tool -> toolDefinitionName(tool).equals(name))
                .findFirst()
                .orElse(null);
        if (callback == null) {
            return "{\"error\":\"Unknown tool: " + safeJson(name) + "\"}";
        }
        String arguments = toolCall.getFunction().getArguments();
        if (!StringUtils.hasText(arguments)) {
            arguments = "{}";
        }
        try {
            ToolContext context = new ToolContext(toolContext == null ? Map.of() : toolContext);
            return callback.call(arguments, context);
        } catch (Exception ex) {
            log.warn("云端工具调用失败 tool={} error={}", name, ex.getMessage());
            return "{\"error\":\"Tool execution failed: " + safeJson(ex.getMessage()) + "\"}";
        }
    }

    private String toolDefinitionName(ToolCallback tool) {
        ToolDefinition definition = tool.getToolDefinition();
        return definition == null || definition.name() == null ? "" : definition.name();
    }

    /**
     * 把 ToolCallback 集合转换成 OpenAI function calling 的 {@code tools} 规格。
     */
    private List<Map<String, Object>> toToolSpecs(List<ToolCallback> tools) {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (ToolCallback tool : tools) {
            ToolDefinition definition = tool.getToolDefinition();
            if (definition == null || !StringUtils.hasText(definition.name())) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", definition.name());
            function.put("description", definition.description() == null ? "" : definition.description());
            function.put("parameters", parseSchema(definition.inputSchema()));

            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", "function");
            spec.put("function", function);
            specs.add(spec);
        }
        return specs;
    }

    private Map<String, Object> parseSchema(String inputSchema) {
        if (!StringUtils.hasText(inputSchema)) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            Map<String, Object> schema = objectMapper.readValue(inputSchema, MAP_TYPE);
            return schema == null || schema.isEmpty() ? Map.of("type", "object", "properties", Map.of()) : schema;
        } catch (Exception ex) {
            log.warn("工具参数 schema 解析失败，使用空对象兜底: {}", ex.getMessage());
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    // ---------------------------------------------------------------------
    // 请求构造
    // ---------------------------------------------------------------------

    /**
     * 构造最终 HTTP 请求。
     */
    private Request buildHttpRequest(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<ApiMessage> conversation,
            Double temperature,
            Integer maxTokens,
            boolean stream,
            List<ToolCallback> tools) throws IOException {
        validateProvider(provider, model);

        List<Map<String, Object>> toolSpecs = tools == null || tools.isEmpty() ? null : toToolSpecs(tools);
        OpenAiChatRequest payload = OpenAiChatRequest.builder()
                .model(model.getApiModelName())
                .messages(conversation)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .stream(stream)
                .streamOptions(stream ? StreamOptions.includeUsage() : null)
                .tools(toolSpecs == null || toolSpecs.isEmpty() ? null : toolSpecs)
                .toolChoice(toolSpecs == null || toolSpecs.isEmpty() ? null : "auto")
                .build();

        RequestBody requestBody = RequestBody.create(objectMapper.writeValueAsString(payload), JSON_MEDIA_TYPE);
        Request.Builder builder = new Request.Builder()
                .url(buildUrl(provider))
                .post(requestBody)
                .header("Content-Type", "application/json");

        if (provider.getHeaders() != null) {
            provider.getHeaders().forEach(builder::header);
        }
        if (provider.isUseApiKey()) {
            String apiKeyValue = provider.getApiKeyPrefix() == null
                    ? provider.getApiKey()
                    : provider.getApiKeyPrefix() + provider.getApiKey();
            builder.header(provider.getApiKeyHeader(), apiKeyValue);
        }
        return builder.build();
    }

    /**
     * 校验 provider 是否具备调用所需的最小配置。
     */
    private void validateProvider(AiProviderProperties.Provider provider, AiModelDefinition model) {
        if (provider == null || !provider.isEnabled()) {
            throw new IllegalStateException("模型 provider 未配置或未启用: " + model.getProviderCode());
        }
        if (!StringUtils.hasText(provider.getBaseUrl())) {
            throw new IllegalStateException("模型 provider baseUrl 未配置: " + model.getProviderCode());
        }
        if (!StringUtils.hasText(provider.getChatCompletionsPath())) {
            throw new IllegalStateException("模型 provider chatCompletionsPath 未配置: " + model.getProviderCode());
        }
        if (provider.isUseApiKey() && !StringUtils.hasText(provider.getApiKey())) {
            throw new IllegalStateException("模型 provider API Key 未配置: " + model.getProviderCode());
        }
    }

    /**
     * 拼接 provider 的最终请求地址。
     */
    private String buildUrl(AiProviderProperties.Provider provider) {
        String baseUrl = provider.getBaseUrl().endsWith("/")
                ? provider.getBaseUrl().substring(0, provider.getBaseUrl().length() - 1)
                : provider.getBaseUrl();
        String path = provider.getChatCompletionsPath().startsWith("/")
                ? provider.getChatCompletionsPath()
                : "/" + provider.getChatCompletionsPath();
        return baseUrl + path;
    }

    // ---------------------------------------------------------------------
    // 消息转换与响应解析
    // ---------------------------------------------------------------------

    /**
     * 把 Spring AI Message 转成 OpenAI-compatible 协议里的 message 数组。
     */
    private List<ApiMessage> toApiMessages(List<Message> messages) {
        List<ApiMessage> apiMessages = new ArrayList<>();
        if (messages == null) {
            return apiMessages;
        }
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            apiMessages.add(ApiMessage.builder()
                    .role(resolveRole(message))
                    .content(message.getText())
                    .build());
        }
        return apiMessages;
    }

    private ApiMessage assistantToolCallMessage(String content, List<ToolCall> toolCalls) {
        return ApiMessage.builder()
                .role("assistant")
                .content(StringUtils.hasText(content) ? content : null)
                .toolCalls(toolCalls)
                .build();
    }

    private ApiMessage toolResultMessage(String toolCallId, String result) {
        return ApiMessage.builder()
                .role("tool")
                .toolCallId(toolCallId)
                .content(result == null ? "" : result)
                .build();
    }

    /**
     * 把 Spring AI 的消息角色映射为 OpenAI-compatible 角色字符串。
     */
    private String resolveRole(Message message) {
        return switch (message.getMessageType()) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            default -> "user";
        };
    }

    private ChoiceMessage firstMessage(OpenAiChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return null;
        }
        return response.getChoices().get(0).getMessage();
    }

    private ChatResponse buildChatResponse(String modelCode, String content, UsageAccumulator usage) {
        return ChatResponse.builder()
                .content(content)
                .model(modelCode)
                .timestamp(System.currentTimeMillis())
                .success(true)
                .tokenUsage(usage.isEmpty() ? null : usage.toTokenUsage())
                .build();
    }

    /**
     * 检查上游 HTTP 状态码是否成功；失败时尽量带回原始响应体，方便排错。
     */
    private void ensureSuccessful(Response response, String modelCode) throws IOException {
        if (response.isSuccessful()) {
            return;
        }
        String errorBody = response.body() != null ? response.body().string() : "";
        throw new IllegalStateException("上游模型返回失败(model=" + modelCode + ", code=" + response.code() + "): " + errorBody);
    }

    /**
     * 读取 JSON 响应体。
     */
    private <T> T readBody(ResponseBody body, Class<T> bodyType) throws IOException {
        if (body == null) {
            return null;
        }
        return objectMapper.readValue(body.string(), bodyType);
    }

    private String safeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    // ---------------------------------------------------------------------
    // 内部累加器 / DTO
    // ---------------------------------------------------------------------

    /** 跨多轮累计 token 用量，保证带工具调用时计费不漏。 */
    private static final class UsageAccumulator {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private int cachedPromptTokens;
        private boolean hasValue;

        void add(Usage usage) {
            if (usage == null) {
                return;
            }
            hasValue = true;
            promptTokens += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            completionTokens += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            totalTokens += usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
            cachedPromptTokens += usage.getCachedPromptTokens();
        }

        boolean isEmpty() {
            return !hasValue;
        }

        ChatResponse.TokenUsage toTokenUsage() {
            return ChatResponse.TokenUsage.builder()
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .cachedPromptTokens(cachedPromptTokens)
                    .totalTokens(totalTokens)
                    .build();
        }
    }

    /** 流式工具调用增量累加器：name 与 id 一般在首帧出现，arguments 跨多帧拼接。 */
    private static final class ToolCallAccumulator {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        ToolCallAccumulator(int index) {
            this.index = index;
        }

        ToolCall toToolCall() {
            return new ToolCall(
                    StringUtils.hasText(id) ? id : "call_" + index,
                    "function",
                    new FunctionCall(name, arguments.toString()));
        }
    }

    /** 一轮流式响应的产物：累计文本 + 工具调用累加器。 */
    private record StreamRoundResult(String content, Map<Integer, ToolCallAccumulator> toolAccumulators) {
        List<ToolCall> toToolCalls() {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (ToolCallAccumulator accumulator : toolAccumulators.values()) {
                if (StringUtils.hasText(accumulator.name)) {
                    toolCalls.add(accumulator.toToolCall());
                }
            }
            return toolCalls;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class OpenAiChatRequest {
        private String model;
        private List<ApiMessage> messages;
        private Double temperature;
        @JsonProperty("max_tokens")
        private Integer maxTokens;
        private Boolean stream;
        @JsonProperty("stream_options")
        private StreamOptions streamOptions;
        // function calling 工具规格；为空时不写入 JSON。
        private List<Map<String, Object>> tools;
        // tool_choice：有 tools 时设为 "auto"。
        @JsonProperty("tool_choice")
        private Object toolChoice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class StreamOptions {
        @JsonProperty("include_usage")
        private Boolean includeUsage;

        static StreamOptions includeUsage() {
            return StreamOptions.builder().includeUsage(true).build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ApiMessage {
        // OpenAI-compatible 角色：system、user、assistant、tool。
        private String role;
        // 该轮消息文本内容；assistant 的 tool_call 决策帧可以为空。
        private String content;
        // assistant 发起的工具调用列表。
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
        // tool 结果消息对应的 tool_call id。
        @JsonProperty("tool_call_id")
        private String toolCallId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ToolCall {
        private String id;
        private String type;
        private FunctionCall function;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FunctionCall {
        private String name;
        // OpenAI 协议里 arguments 是 JSON 字符串，不是对象。
        private String arguments;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OpenAiChatResponse {
        private List<Choice> choices;
        private Usage usage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OpenAiChatChunk {
        private List<ChunkChoice> choices;
        private Usage usage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Choice {
        private ChoiceMessage message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChoiceMessage {
        private String content;
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChunkChoice {
        private Delta delta;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Delta {
        private String content;
        @JsonProperty("tool_calls")
        private List<ToolCallDelta> toolCalls;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ToolCallDelta {
        private Integer index;
        private String id;
        private String type;
        private FunctionCall function;
    }

    /** 非流式响应中的 usage 结构。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @JsonProperty("completion_tokens")
        private Integer completionTokens;
        @JsonProperty("total_tokens")
        private Integer totalTokens;
        @JsonProperty("prompt_tokens_details")
        private PromptTokensDetails promptTokensDetails;

        Integer getCachedPromptTokens() {
            return promptTokensDetails == null || promptTokensDetails.getCachedTokens() == null
                    ? 0
                    : promptTokensDetails.getCachedTokens();
        }
    }

    /** prompt token 细分结构，主要读取 cached_tokens。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;
    }
}
