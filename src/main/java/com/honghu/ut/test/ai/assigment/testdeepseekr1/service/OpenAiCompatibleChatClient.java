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

    // 统一的 JSON MediaType；所有 OpenAI-compatible 请求体都是 UTF-8 JSON。
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    // Jackson 用于把请求 DTO 序列化成 JSON，也用于把上游 JSON 响应反序列化成内部 DTO。
    private final ObjectMapper objectMapper;
    // 使用网关专用 OkHttpClient，连接池、超时、代理等配置可以和普通业务 HTTP 客户端隔离。
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
            // 根据模型、provider、消息列表构造非流式 HTTP 请求。
            Request request = buildHttpRequest(model, provider, messages, temperature, maxTokens, false);
            // OkHttp Response 必须放在 try-with-resources 中，确保响应体及时关闭并释放连接。
            try (Response response = okHttpClient.newCall(request).execute()) {
                // 先检查 HTTP 状态码；失败时会带出上游原始错误体。
                ensureSuccessful(response, model.getModelCode());
                // 读取完整响应 JSON，并映射到 OpenAI-compatible 响应结构。
                OpenAiChatResponse chatResponse = readBody(response.body(), OpenAiChatResponse.class);
                // 从 choices[0].message.content 提取最终回复文本。
                String content = extractFullContent(chatResponse);
                // usage 可能为空，取决于 provider 是否返回 token 统计。
                Usage usage = chatResponse != null ? chatResponse.getUsage() : null;
                /*
                 * OpenAI-compatible 协议通常会在 usage.prompt_tokens_details.cached_tokens
                 * 里返回缓存命中的输入 token。这里统一映射到 ChatResponse.TokenUsage，
                 * 后续 BillingService 才能按 cached input 单价计算折扣。
                 */
                return ChatResponse.builder()
                        // 模型生成的完整文本。
                        .content(content)
                        // 返回本项目内部模型编码，便于前端和计费日志识别。
                        .model(model.getModelCode())
                        // 记录响应生成时间，保持和其他 ChatResponse 来源一致。
                        .timestamp(System.currentTimeMillis())
                        // 标记本次网关调用成功。
                        .success(true)
                        // 如果上游返回 usage，就映射为项目统一的 TokenUsage；否则保持 null。
                        .tokenUsage(usage == null ? null : ChatResponse.TokenUsage.builder()
                                .promptTokens(usage.getPromptTokens())
                                .completionTokens(usage.getCompletionTokens())
                                .cachedPromptTokens(usage.getCachedPromptTokens())
                                .totalTokens(usage.getTotalTokens())
                                .build())
                        .build();
            }
        } catch (Exception e) {
            // 对外统一包装成 RuntimeException，让上层服务能按模型调用失败处理。
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
        /*
         * Flux.create 负责把阻塞式 OkHttp SSE 读取桥接成 Reactor 流。
         * subscribeOn(boundedElastic) 很关键：OkHttp 读取是阻塞 IO，不能占用 Reactor 事件循环线程。
         */
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
            // 构建 stream=true 的请求体，上游会以 data: {...} 的 SSE 行持续返回。
            Request request = buildHttpRequest(model, provider, messages, temperature, maxTokens, true);
            // 保存 Call 对象，用于前端取消请求时同步取消底层 HTTP 调用。
            okhttp3.Call call = okHttpClient.newCall(request);
            // Reactor 订阅取消时，立刻取消 OkHttp 请求，避免后台继续读流浪费资源。
            sink.onCancel(call::cancel);

            // 执行阻塞式 HTTP 调用，并确保响应结束后关闭连接资源。
            try (Response response = call.execute()) {
                // 上游非 2xx 时直接让流失败，错误里会包含响应体。
                ensureSuccessful(response, model.getModelCode());
                // SSE 内容都在 response body 里；body 为空说明 provider 响应不符合协议。
                ResponseBody body = response.body();
                if (body == null) {
                    sink.error(new IllegalStateException("流式响应 body 为空"));
                    return;
                }

                // 持续逐行读取 SSE；OpenAI-compatible provider 一般每行形如 data: {...}。
                while (!body.source().exhausted()) {
                    // 读取一行 UTF-8 文本，Okio 会阻塞直到有新行或连接结束。
                    String line = body.source().readUtf8Line();
                    // 忽略空行、event 行、注释行等非 data 行。
                    if (!StringUtils.hasText(line) || !line.startsWith("data:")) {
                        continue;
                    }
                    // 去掉 data: 前缀，得到真正 JSON 或 [DONE] 标记。
                    String data = line.substring(5).trim();
                    // 空 data 没有业务意义，继续读下一行。
                    if (!StringUtils.hasText(data)) {
                        continue;
                    }
                    // [DONE] 表示上游完成生成，跳出循环并 complete。
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    // 把本帧 JSON 反序列化成 chunk，后面分别处理 usage 和 delta 文本。
                    OpenAiChatChunk chunk = objectMapper.readValue(data, OpenAiChatChunk.class);
                    if (chunk.getUsage() != null) {
                        /*
                         * 开启 stream_options.include_usage 后，部分 provider 会在流式最后一帧返回 usage。
                         * 这一帧可能没有 content，但必须继续向下游发出，网关会用它做最终计费。
                         */
                        Usage usage = chunk.getUsage();
                        sink.next(ChatResponse.builder()
                                // usage 帧通常没有 content，但仍要带 model 和 timestamp。
                                .model(model.getModelCode())
                                .timestamp(System.currentTimeMillis())
                                .success(true)
                                // 把 token 统计映射到统一对象，下游计费服务会读取它。
                                .tokenUsage(ChatResponse.TokenUsage.builder()
                                        .promptTokens(usage.getPromptTokens())
                                        .completionTokens(usage.getCompletionTokens())
                                        .cachedPromptTokens(usage.getCachedPromptTokens())
                                        .totalTokens(usage.getTotalTokens())
                                        .build())
                                .build());
                    }
                    // 从 choices[0].delta.content 中提取本帧新增文本。
                    String deltaContent = extractDeltaContent(chunk);
                    if (StringUtils.hasText(deltaContent)) {
                        sink.next(ChatResponse.builder()
                                // content 只放本次增量，不是完整累积文本。
                                .content(deltaContent)
                                .model(model.getModelCode())
                                .timestamp(System.currentTimeMillis())
                                .success(true)
                                .build());
                    }
                }
                // 正常读到 [DONE] 或上游关闭后，通知下游流结束。
                sink.complete();
            }
        } catch (Exception e) {
            // 任意解析、网络、协议错误都转为 Flux error，让控制器 SSE 层可以统一结束响应。
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
        // 先做 provider 基础校验，避免后面拼 URL 或 Header 时出现空指针。
        validateProvider(provider, model);

        // 构造 OpenAI-compatible 请求体；字段名通过内部 DTO 上的 @JsonProperty 控制。
        OpenAiChatRequest payload = OpenAiChatRequest.builder()
                // 使用 provider 真实 API 模型名，不一定等于系统内部 modelCode。
                .model(model.getApiModelName())
                // 把 Spring AI Message 转成 OpenAI-compatible messages。
                .messages(toApiMessages(messages))
                // temperature 为空时不写入 JSON，让 provider 使用默认值。
                .temperature(temperature)
                // max_tokens 为空时不写入 JSON，让 provider 使用默认值。
                .maxTokens(maxTokens)
                // stream 决定上游返回完整 JSON 还是 SSE 增量。
                .stream(stream)
                // 流式请求时带 include_usage，争取在最后一帧拿到 token 用量。
                .streamOptions(stream ? StreamOptions.includeUsage() : null)
                .build();

        // 序列化请求体，并指定 application/json。
        RequestBody requestBody = RequestBody.create(objectMapper.writeValueAsString(payload), JSON_MEDIA_TYPE);
        // 初始化 OkHttp Request，URL 和 HTTP method 在这里确定。
        Request.Builder builder = new Request.Builder()
                .url(buildUrl(provider))
                .post(requestBody)
                .header("Content-Type", "application/json");

        // 写入 provider 自定义 Header，例如平台要求的 App-Id、Organization、Beta 开关等。
        if (provider.getHeaders() != null) {
            provider.getHeaders().forEach(builder::header);
        }
        // 按 provider 配置写鉴权 Header；不同平台可能是 Authorization，也可能是 api-key。
        if (provider.isUseApiKey()) {
            // apiKeyPrefix 允许配置 Bearer 前缀；为空则直接使用原始 key。
            String apiKeyValue = provider.getApiKeyPrefix() == null
                    ? provider.getApiKey()
                    : provider.getApiKeyPrefix() + provider.getApiKey();
            builder.header(provider.getApiKeyHeader(), apiKeyValue);
        }
        // 返回最终不可变 Request，调用方直接 execute。
        return builder.build();
    }

    /**
     * 校验 provider 是否具备调用所需的最小配置。
     */
    private void validateProvider(AiProviderProperties.Provider provider, AiModelDefinition model) {
        // provider 未配置或被禁用时，不允许继续调用。
        if (provider == null || !provider.isEnabled()) {
            throw new IllegalStateException("模型 provider 未配置或未启用: " + model.getProviderCode());
        }
        // baseUrl 是所有 OpenAI-compatible 请求的根地址。
        if (!StringUtils.hasText(provider.getBaseUrl())) {
            throw new IllegalStateException("模型 provider baseUrl 未配置: " + model.getProviderCode());
        }
        // chatCompletionsPath 是具体接口路径，例如 /v1/chat/completions。
        if (!StringUtils.hasText(provider.getChatCompletionsPath())) {
            throw new IllegalStateException("模型 provider chatCompletionsPath 未配置: " + model.getProviderCode());
        }
        // 如果 provider 声明需要 API Key，就必须配置具体 key。
        if (provider.isUseApiKey() && !StringUtils.hasText(provider.getApiKey())) {
            throw new IllegalStateException("模型 provider API Key 未配置: " + model.getProviderCode());
        }
    }

    /**
     * 拼接 provider 的最终请求地址。
     */
    private String buildUrl(AiProviderProperties.Provider provider) {
        // 去掉 baseUrl 末尾斜杠，避免和 path 拼接时出现双斜杠。
        String baseUrl = provider.getBaseUrl().endsWith("/")
                ? provider.getBaseUrl().substring(0, provider.getBaseUrl().length() - 1)
                : provider.getBaseUrl();
        // path 必须以斜杠开头；配置中没写时这里补上。
        String path = provider.getChatCompletionsPath().startsWith("/")
                ? provider.getChatCompletionsPath()
                : "/" + provider.getChatCompletionsPath();
        // 拼成最终请求地址。
        return baseUrl + path;
    }

    /**
     * 把 Spring AI Message 转成 OpenAI-compatible 协议里的 message 数组。
     */
    private List<ApiMessage> toApiMessages(List<Message> messages) {
        // 预先创建结果列表，逐条转换 Spring AI Message。
        List<ApiMessage> apiMessages = new ArrayList<>();
        for (Message message : messages) {
            // 允许上游传入列表里夹杂 null，转换时直接跳过。
            if (message == null) {
                continue;
            }
            // 每条消息只保留 role 和文本 content，满足大多数 OpenAI-compatible provider 的最小协议。
            apiMessages.add(ApiMessage.builder()
                    .role(resolveRole(message))
                    .content(message.getText())
                    .build());
        }
        return apiMessages;
    }

    /**
     * 把 Spring AI 的消息角色映射为 OpenAI-compatible 角色字符串。
     */
    private String resolveRole(Message message) {
        // Spring AI 的 MessageType 转成 OpenAI 协议固定字符串；未知/普通消息默认当 user。
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
        // 2xx 直接返回，让调用方继续解析响应体。
        if (response.isSuccessful()) {
            return;
        }
        // 非 2xx 时尽量读取响应体，很多 provider 会把具体错误码和原因放在 body 里。
        String errorBody = response.body() != null ? response.body().string() : "";
        // 抛出带模型编码、HTTP 状态码和原始错误体的异常，方便日志定位。
        throw new IllegalStateException("上游模型返回失败(model=" + modelCode + ", code=" + response.code() + "): " + errorBody);
    }

    /**
     * 读取 JSON 响应体。
     */
    private <T> T readBody(ResponseBody body, Class<T> bodyType) throws IOException {
        // body 为空时返回 null，由调用方按空响应处理。
        if (body == null) {
            return null;
        }
        // OkHttp body.string() 只能读取一次，所以读取和反序列化必须在这里一次完成。
        return objectMapper.readValue(body.string(), bodyType);
    }

    /**
     * 从非流式响应中提取完整回复内容。
     */
    private String extractFullContent(OpenAiChatResponse response) {
        // response 或 choices 缺失时，视为没有文本输出。
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return "";
        }
        // OpenAI-compatible 协议通常把最终答案放在第一个 choice。
        Choice choice = response.getChoices().get(0);
        // message 或 content 缺失时返回空字符串，避免上层空指针。
        if (choice.getMessage() == null || !StringUtils.hasText(choice.getMessage().getContent())) {
            return "";
        }
        // 返回完整回复文本。
        return choice.getMessage().getContent();
    }

    /**
     * 从流式 chunk 中提取本次增量文本。
     */
    private String extractDeltaContent(OpenAiChatChunk chunk) {
        // chunk 或 choices 缺失时，本帧没有可展示文本。
        if (chunk == null || chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            return null;
        }
        // OpenAI-compatible 流式文本通常放在第一个 choice 的 delta 对象中。
        Delta delta = chunk.getChoices().get(0).getDelta();
        // delta 缺失或 content 为空时，可能是 role 帧、usage 帧或结束帧，直接忽略文本。
        if (delta == null || !StringUtils.hasText(delta.getContent())) {
            return null;
        }
        // 返回本帧增量文本。
        return delta.getContent();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class OpenAiChatRequest {
        // 上游 API 模型名，例如 deepseek-chat、qwen-plus、gpt-4o-mini 等。
        private String model;
        // 对话消息数组，按 system/user/assistant 顺序发送给 provider。
        private List<ApiMessage> messages;
        // 采样温度；为空时不发送，让 provider 使用默认值。
        private Double temperature;
        // 最大输出 token；JSON 字段名是 max_tokens。
        @com.fasterxml.jackson.annotation.JsonProperty("max_tokens")
        private Integer maxTokens;
        // 是否开启 SSE 流式输出。
        private Boolean stream;
        // 流式选项，目前只用于 include_usage。
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
        // OpenAI-compatible 字段 include_usage=true 表示希望最后一帧携带 usage。
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
        // OpenAI-compatible 角色：system、user、assistant。
        private String role;
        // 该轮消息文本内容。
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OpenAiChatResponse {
        // 非流式完整回复候选列表，当前只取第一个 choice。
        private List<Choice> choices;
        // token 用量统计，provider 可能不返回。
        private Usage usage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OpenAiChatChunk {
        // 流式增量候选列表，当前只取第一个 choice。
        private List<ChunkChoice> choices;
        // 流式最后一帧可能返回的 token 用量统计。
        private Usage usage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Choice {
        // 非流式回复中的完整 assistant message。
        private ChoiceMessage message;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChoiceMessage {
        // assistant 完整回复文本。
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChunkChoice {
        // 流式回复中的增量内容对象。
        private Delta delta;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Delta {
        // 本帧新增文本；可能为空，因为有些帧只包含 role、finish_reason 或 usage。
        private String content;
    }

    /** 非流式响应中的 usage 结构。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Usage {
        // 输入 prompt token 数。
        @com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens")
        private Integer promptTokens;
        // 输出 completion token 数。
        @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens")
        private Integer completionTokens;
        // 总 token 数。
        @com.fasterxml.jackson.annotation.JsonProperty("total_tokens")
        private Integer totalTokens;
        // 输入 token 细分，当前主要读取 cached_tokens。
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
        // provider 命中的缓存输入 token 数，用于 cached input 计费折扣。
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;
    }
}




