package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import org.springframework.ai.autoconfigure.openai.OpenAiConnectionProperties;
import org.springframework.ai.autoconfigure.openai.OpenAiEmbeddingProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
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
        String baseUrl = firstText(embeddingProperties.getBaseUrl(), connectionProperties.getBaseUrl());
        String apiKey = firstText(embeddingProperties.getApiKey(), connectionProperties.getApiKey());
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("OpenAI-compatible embedding base URL must be set.");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("OpenAI-compatible embedding API key must be set.");
        }

        OpenAiApi api = new OpenAiApi(
                baseUrl,
                apiKey,
                embeddingProperties.getEmbeddingsPath(),
                "/v1/chat/completions",
                restClientBuilder,
                webClientBuilder,
                responseErrorHandler);

        return new OpenAiEmbeddingModel(
                api,
                embeddingProperties.getMetadataMode() == null ? MetadataMode.EMBED : embeddingProperties.getMetadataMode(),
                embeddingProperties.getOptions(),
                retryTemplateProvider.getIfAvailable(RetryTemplate::defaultInstance),
                observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP));
    }

    private static String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }
}
