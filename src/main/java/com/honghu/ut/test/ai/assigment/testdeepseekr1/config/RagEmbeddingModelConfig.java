package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;

/*
 * RAG EmbeddingModel 手动装配配置。
 *
 * 这个类专门解决“Spring AI 1.0.0 自动配置不完全适合当前项目”的问题：
 * 项目使用的是 OpenAI-compatible embedding 接口，实际 provider 可能是 DashScope 或其他兼容服务，
 * 因此 baseUrl、apiKey、embedding path 要优先读取 embedding 专属配置，再回退到全局 OpenAI 配置。
 */
@Configuration
@EnableConfigurationProperties({OpenAiConnectionProperties.class, OpenAiEmbeddingProperties.class})
public class RagEmbeddingModelConfig {

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(prefix = "spring.ai.openai.embedding", name = "enabled", havingValue = "true")
    public EmbeddingModel ragEmbeddingModel(OpenAiConnectionProperties connectionProperties,
                                            OpenAiEmbeddingProperties embeddingProperties,
                                            RestClient.Builder restClientBuilder,
                                            WebClient.Builder webClientBuilder,
                                            ResponseErrorHandler responseErrorHandler,
                                            ObjectProvider<RetryTemplate> retryTemplateProvider,
                                            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        // embedding 专属 baseUrl 优先级最高；没有单独配置时，回退到 spring.ai.openai.base-url。
        String baseUrl = firstText(embeddingProperties.getBaseUrl(), connectionProperties.getBaseUrl());
        // embedding 专属 apiKey 优先级最高；没有单独配置时，回退到 spring.ai.openai.api-key。
        String apiKey = firstText(embeddingProperties.getApiKey(), connectionProperties.getApiKey());
        // baseUrl 是 OpenAI-compatible embedding 的服务地址，缺失时不能启动，否则错误会延迟到用户上传文档时才暴露。
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("OpenAI-compatible embedding base URL must be set.");
        }
        // apiKey 是调用 embedding 服务的鉴权凭证，缺失时同样直接失败，避免 RAG 静默不可用。
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("OpenAI-compatible embedding API key must be set.");
        }

        /*
         * 构造 Spring AI 的 OpenAiApi。
         *
         * 注意这里虽然类型名叫 OpenAiApi，但它可以指向任何 OpenAI-compatible 服务。
         * embeddingsPath 来自配置，chat completions path 在这个 EmbeddingModel 中不会真正使用，
         * 但构造函数需要传入，所以保留标准 "/v1/chat/completions" 占位。
         */
        OpenAiApi api = new OpenAiApi(
                baseUrl,
                new SimpleApiKey(apiKey),
                new LinkedMultiValueMap<>(),
                embeddingProperties.getEmbeddingsPath(),
                "/v1/chat/completions",
                restClientBuilder,
                webClientBuilder,
                responseErrorHandler);

        /*
         * 创建真正注入给 RAG 管线使用的 EmbeddingModel。
         *
         * metadataMode 为空时使用 EMBED，表示把文档内容按 embedding 场景处理；
         * retryTemplate 和 observationRegistry 都通过 ObjectProvider 读取，保证没有自定义 Bean 时仍能启动。
         */
        return new OpenAiEmbeddingModel(
                api,
                embeddingProperties.getMetadataMode() == null ? MetadataMode.EMBED : embeddingProperties.getMetadataMode(),
                embeddingProperties.getOptions(),
                retryTemplateProvider.getIfAvailable(RetryTemplate::defaultInstance),
                observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP));
    }

    /*
     * 从两个字符串里挑第一个非空文本。
     *
     * primary 表示更具体的配置，fallback 表示通用配置；
     * 这样调用方不用重复写 StringUtils.hasText 的判断。
     */
    private static String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }
}
