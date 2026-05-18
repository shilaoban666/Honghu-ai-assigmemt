package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.autoconfigure.vectorstore.milvus.MilvusServiceClientProperties;
import org.springframework.ai.autoconfigure.vectorstore.milvus.MilvusVectorStoreProperties;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.MilvusVectorStore;
import org.springframework.ai.vectorstore.MilvusVectorStore.MilvusVectorStoreConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import io.micrometer.observation.ObservationRegistry;

import java.util.concurrent.TimeUnit;

/**
 * RAG 自己持有的 Milvus SDK 连接配置。
 *
 * <p>这里没有再让业务写入链路依赖 Spring AI 的 VectorStore.add()，而是直接创建
 * Milvus Java SDK 的 {@link MilvusServiceClient}。配置项仍然沿用
 * {@code spring.ai.vectorstore.milvus.*}，这样不会引入第二套 Milvus host、port、
 * collection、database 配置，避免线上出现“Spring AI 连一个库、我们自己连另一个库”的隐蔽问题。</p>
 *
 * <p>{@link ConditionalOnMissingBean} 的作用是保持兼容：如果测试或其他配置已经显式提供了
 * {@link MilvusServiceClient}，这里不会抢占；否则由本配置负责创建连接。Spring AI 的
 * Milvus 自动配置看到已有 {@link MilvusServiceClient} 后，也会复用这个连接。</p>
 */
@Configuration
@EnableConfigurationProperties({MilvusServiceClientProperties.class, MilvusVectorStoreProperties.class})
public class RagMilvusClientConfig {

    @Bean
    @ConditionalOnMissingBean(BatchingStrategy.class)
    public BatchingStrategy milvusBatchingStrategy() {
        return new TokenCountBatchingStrategy();
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    @ConditionalOnProperty(prefix = "spring.ai.openai.embedding", name = "enabled", havingValue = "true")
    public VectorStore milvusVectorStore(MilvusServiceClient milvusClient,
                                         EmbeddingModel embeddingModel,
                                         MilvusVectorStoreProperties vectorStoreProperties,
                                         BatchingStrategy batchingStrategy,
                                         ObjectProvider<ObservationRegistry> observationRegistry,
                                         ObjectProvider<VectorStoreObservationConvention> customObservationConvention) {
        MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
                .withCollectionName(vectorStoreProperties.getCollectionName())
                .withDatabaseName(vectorStoreProperties.getDatabaseName())
                .withIndexType(IndexType.valueOf(vectorStoreProperties.getIndexType().name()))
                .withMetricType(MetricType.valueOf(vectorStoreProperties.getMetricType().name()))
                .withIndexParameters(vectorStoreProperties.getIndexParameters())
                .withEmbeddingDimension(vectorStoreProperties.getEmbeddingDimension())
                .build();

        return new MilvusVectorStore(
                milvusClient,
                embeddingModel,
                config,
                vectorStoreProperties.isInitializeSchema(),
                batchingStrategy,
                observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP),
                customObservationConvention.getIfAvailable(() -> null));
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean(MilvusServiceClient.class)
    @ConditionalOnProperty(prefix = "spring.ai.openai.embedding", name = "enabled", havingValue = "true")
    public MilvusServiceClient milvusClient(MilvusVectorStoreProperties vectorStoreProperties,
                                            MilvusServiceClientProperties clientProperties) {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(clientProperties.getHost())
                .withPort(clientProperties.getPort())
                .withDatabaseName(vectorStoreProperties.getDatabaseName())
                .withConnectTimeout(clientProperties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .withKeepAliveTime(clientProperties.getKeepAliveTimeMs(), TimeUnit.MILLISECONDS)
                .withKeepAliveTimeout(clientProperties.getKeepAliveTimeoutMs(), TimeUnit.MILLISECONDS)
                .withRpcDeadline(clientProperties.getRpcDeadlineMs(), TimeUnit.MILLISECONDS)
                .withSecure(clientProperties.isSecure())
                .withIdleTimeout(clientProperties.getIdleTimeoutMs(), TimeUnit.MILLISECONDS)
                .withAuthorization(clientProperties.getUsername(), clientProperties.getPassword());

        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getUri())) {
            builder.withUri(clientProperties.getUri());
        }
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getToken())) {
            builder.withToken(clientProperties.getToken());
        }
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getClientKeyPath())) {
            builder.withClientKeyPath(clientProperties.getClientKeyPath());
        }
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getClientPemPath())) {
            builder.withClientPemPath(clientProperties.getClientPemPath());
        }
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getCaPemPath())) {
            builder.withCaPemPath(clientProperties.getCaPemPath());
        }
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getServerPemPath())) {
            builder.withServerPemPath(clientProperties.getServerPemPath());
        }
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getServerName())) {
            builder.withServerName(clientProperties.getServerName());
        }

        return new MilvusServiceClient(builder.build());
    }
}
