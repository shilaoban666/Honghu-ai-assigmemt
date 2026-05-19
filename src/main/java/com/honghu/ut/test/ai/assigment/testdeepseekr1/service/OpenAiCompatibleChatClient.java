package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容协议客户端。
 *
 * <p>除本地 localhost / Ollama 之外的模型统一走这层，
 * 通过配置文件控制 baseUrl、apiKey、header 与 path。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiCompatibleChatClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;
    @Qualifier("aiGatewayOkHttpClient")
    private final OkHttpClient okHttpClient;

    /**
     * 发起一次非流式 OpenAI-compatible 聊天调用。
     *
     * <p>这里把上层统一的 Prompt Message 列表转换成 OpenAI-compatible 协议的 messages，
     * 并按 provider 配置自动拼接 URL、鉴权头和请求体。</p>
     */
    public ChatResponse chat(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens) {
        try {
            Request request = buildHttpRequest(model, provider, messages, temperature, maxTokens, false);
            try (Response response = okHttpClient.newCall(request).execute()) {
                ensureSuccessful(response, model.getModelCode());
                OpenAiChatResponse chatResponse = readBody(response.body(), OpenAiChatResponse.class);
                String content = extractFullContent(chatResponse);
                Usage usage = chatResponse != null ? chatResponse.getUsage() : null;
                /*
                 * OpenAI-compatible 协议通常会在 usage.prompt_tokens_details.cached_tokens
                 * 里返回缓存命中的输入 token。这里统一映射到 ChatResponse.TokenUsage，
                 * 后续 BillingService 才能按 cached input 单价计算折扣。
                 */
                return ChatResponse.builder()
                        .content(content)
                        .model(model.getModelCode())
                        .timestamp(System.currentTimeMillis())
                        .success(true)
                        .tokenUsage(usage == null ? null : ChatResponse.TokenUsage.builder()
                                .promptTokens(usage.getPromptTokens())
                                .completionTokens(usage.getCompletionTokens())
                                .cachedPromptTokens(usage.getCachedPromptTokens())
                                .totalTokens(usage.getTotalTokens())
                                .build())
                        .build();
            }
        } catch (Exception e) {
            throw new RuntimeException("调用外部模型失败(model=" + model.getModelCode() + "): " + e.getMessage(), e);
        }
    }

    /**
     * 发起一次流式 OpenAI-compatible 聊天调用。
     *
     * <p>响应体按 SSE 风格逐行读取，遇到 {@code data: [DONE]} 时结束。</p>
     */
    public Flux<ChatResponse> streamChat(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens) {
        return Flux.<ChatResponse>create(sink -> executeStreamingRequest(sink, model, provider, messages, temperature, maxTokens))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 真正执行流式 HTTP 请求并把上游返回的 delta 内容拆成 ChatResponse 分片。
     */
    private void executeStreamingRequest(
            FluxSink<ChatResponse> sink,
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens) {
        try {
            Request request = buildHttpRequest(model, provider, messages, temperature, maxTokens, true);
            okhttp3.Call call = okHttpClient.newCall(request);
            sink.onCancel(call::cancel);

            try (Response response = call.execute()) {
                ensureSuccessful(response, model.getModelCode());
                ResponseBody body = response.body();
                if (body == null) {
                    sink.error(new IllegalStateException("流式响应 body 为空"));
                    return;
                }

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
                        /*
                         * 开启 stream_options.include_usage 后，部分 provider 会在流式最后一帧返回 usage。
                         * 这一帧可能没有 content，但必须继续向下游发出，网关会用它做最终计费。
                         */
                        Usage usage = chunk.getUsage();
                        sink.next(ChatResponse.builder()
                                .model(model.getModelCode())
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
                    String deltaContent = extractDeltaContent(chunk);
                    if (StringUtils.hasText(deltaContent)) {
                        sink.next(ChatResponse.builder()
                                .content(deltaContent)
                                .model(model.getModelCode())
                                .timestamp(System.currentTimeMillis())
                                .success(true)
                                .build());
                    }
                }
                sink.complete();
            }
        } catch (Exception e) {
            sink.error(new RuntimeException("调用外部模型流式接口失败(model=" + model.getModelCode() + "): " + e.getMessage(), e));
        }
    }

    /**
     * 构造最终 HTTP 请求。
     *
     * <p>对于外部模型，是否需要 API Key、放在哪个 Header、是否需要 Bearer 前缀，
     * 全部由 {@link AiProviderProperties.Provider} 决定。</p>
     */
    private Request buildHttpRequest(
            AiModelDefinition model,
            AiProviderProperties.Provider provider,
            List<Message> messages,
            Double temperature,
            Integer maxTokens,
            boolean stream) throws IOException {
        validateProvider(provider, model);

        OpenAiChatRequest payload = OpenAiChatRequest.builder()
                .model(model.getApiModelName())
                .messages(toApiMessages(messages))
                .temperature(temperature)
                .maxTokens(maxTokens)
                .stream(stream)
                .streamOptions(stream ? StreamOptions.includeUsage() : null)
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

    /**
     * 把 Spring AI Message 转成 OpenAI-compatible 协议里的 message 数组。
     */
    private List<ApiMessage> toApiMessages(List<Message> messages) {
        List<ApiMessage> apiMessages = new ArrayList<>();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            apiMessages.add(ApiMessage.builder()
                    .role(resolveRole(message))
                    .content(message.getContent())
                    .build());
        }
        return apiMessages;
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

    /**
     * 从非流式响应中提取完整回复内容。
     */
    private String extractFullContent(OpenAiChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return "";
        }
        Choice choice = response.getChoices().get(0);
        if (choice.getMessage() == null || !StringUtils.hasText(choice.getMessage().getContent())) {
            return "";
        }
        return choice.getMessage().getContent();
    }

    /**
     * 从流式 chunk 中提取本次增量文本。
     */
    private String extractDeltaContent(OpenAiChatChunk chunk) {
        if (chunk == null || chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            return null;
        }
        Delta delta = chunk.getChoices().get(0).getDelta();
        if (delta == null || !StringUtils.hasText(delta.getContent())) {
            return null;
        }
        return delta.getContent();
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
        @com.fasterxml.jackson.annotation.JsonProperty("max_tokens")
        private Integer maxTokens;
        private Boolean stream;
        @JsonProperty("stream_options")
        private StreamOptions streamOptions;
    }

    /**
     * OpenAI-compatible 流式选项。
     *
     * <p>当前只打开 {@code include_usage}，目的是让部分 provider 在最后一帧补回 usage，
     * 这样流式调用也能做准确计费。</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class StreamOptions {
        @JsonProperty("include_usage")
        private Boolean includeUsage;

        /** 快捷构造“在流式最后一帧返回 usage”的请求参数。 */
        static StreamOptions includeUsage() {
            return StreamOptions.builder().includeUsage(true).build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class ApiMessage {
        private String role;
        private String content;
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
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChoiceMessage {
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChunkChoice {
        private Delta delta;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Delta {
        private String content;
    }

    /** 非流式响应中的 usage 结构。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Usage {
        @com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens")
        private Integer completionTokens;
        @com.fasterxml.jackson.annotation.JsonProperty("total_tokens")
        private Integer totalTokens;
        @JsonProperty("prompt_tokens_details")
        private PromptTokensDetails promptTokensDetails;

        /**
         * 读取缓存命中的 prompt token 数。
         *
         * <p>很多 provider 会把它放在嵌套对象里，而不是直接放在 usage 顶层。</p>
         */
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




