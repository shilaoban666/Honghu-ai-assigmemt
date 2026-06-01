package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusServiceClientProperties;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
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
        /*
         * TokenCountBatchingStrategy 用 token 数控制分批大小。
         *
         * Spring AI 1.0.0 的 VectorStore 写入链路需要一个 BatchingStrategy Bean；
         * 即使当前项目的索引写入主要走 MilvusVectorRepository，保留它也能让 Spring AI 的检索/兼容路径完整装配。
         */
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
        /*
         * 手动创建 Spring AI MilvusVectorStore。
         *
         * 参数里的 batchingStrategy / observationRegistry / customObservationConvention 保留在签名中，
         * 是为了和 Spring AI 自动配置的依赖形态保持接近，方便将来需要接回观察指标或批处理时不改调用方。
         * 当前 builder API 已经不再要求这些参数，所以这里主要使用 Milvus 客户端、EmbeddingModel 和 collection 配置。
         */
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                // 数据库名来自 spring.ai.vectorstore.milvus.database-name，确保读写侧落在同一个 Milvus database。
                .databaseName(vectorStoreProperties.getDatabaseName())
                // collectionName 是 RAG 检索所在集合，必须和 MilvusVectorRepository 写入的集合一致。
                .collectionName(vectorStoreProperties.getCollectionName())
                // indexType 从 Spring AI 配置枚举转换为 Milvus SDK 枚举，避免字符串拼写错误。
                .indexType(IndexType.valueOf(vectorStoreProperties.getIndexType().name()))
                // metricType 决定向量距离计算方式，必须和建索引时保持一致。
                .metricType(MetricType.valueOf(vectorStoreProperties.getMetricType().name()))
                // indexParameters 保存 Milvus 索引额外参数，例如 nlist、M、efConstruction 等。
                .indexParameters(vectorStoreProperties.getIndexParameters())
                // embeddingDimension 必须等于 embedding 模型输出维度，否则插入或检索都会失败。
                .embeddingDimension(vectorStoreProperties.getEmbeddingDimension())
                // initializeSchema 控制是否由应用启动时自动建 collection，生产环境通常关闭。
                .initializeSchema(vectorStoreProperties.isInitializeSchema())
                .build();
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean(MilvusServiceClient.class)
    @ConditionalOnProperty(prefix = "spring.ai.openai.embedding", name = "enabled", havingValue = "true")
    public MilvusServiceClient milvusClient(MilvusVectorStoreProperties vectorStoreProperties,
                                            MilvusServiceClientProperties clientProperties) {
        /*
         * 按 Spring AI Milvus 配置创建底层 Milvus Java SDK 客户端。
         *
         * 这里直接使用 SDK 客户端，是因为项目写入向量时需要自己控制 delete/insert 的幂等逻辑，
         * 不能完全依赖 VectorStore.add() 的封装。
         */
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                // Milvus 主机名或 IP。
                .withHost(clientProperties.getHost())
                // Milvus gRPC 端口。
                .withPort(clientProperties.getPort())
                // 目标 database，和 VectorStore builder 中的 databaseName 保持一致。
                .withDatabaseName(vectorStoreProperties.getDatabaseName())
                // 建连超时时间，防止 Milvus 不可达时请求长期阻塞。
                .withConnectTimeout(clientProperties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                // keepalive 发送间隔，用于维持长连接活性。
                .withKeepAliveTime(clientProperties.getKeepAliveTimeMs(), TimeUnit.MILLISECONDS)
                // keepalive 响应超时，超时后 SDK 会认为连接不可用。
                .withKeepAliveTimeout(clientProperties.getKeepAliveTimeoutMs(), TimeUnit.MILLISECONDS)
                // 单次 RPC 截止时间，避免查询或插入无限等待。
                .withRpcDeadline(clientProperties.getRpcDeadlineMs(), TimeUnit.MILLISECONDS)
                // 是否启用 TLS/安全连接。
                .withSecure(clientProperties.isSecure())
                // 空闲连接超时，释放长期不用的连接资源。
                .withIdleTimeout(clientProperties.getIdleTimeoutMs(), TimeUnit.MILLISECONDS)
                // 用户名密码鉴权；token 鉴权会在下面 secure 分支中补充。
                .withAuthorization(clientProperties.getUsername(), clientProperties.getPassword());

        // secure 模式下，如果配置了完整 URI，就让 SDK 使用 URI 作为连接入口。
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getUri())) {
            builder.withUri(clientProperties.getUri());
        }
        // secure 模式下支持 token 鉴权，常见于托管 Milvus 或 Zilliz Cloud。
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getToken())) {
            builder.withToken(clientProperties.getToken());
        }
        // 客户端私钥路径，用于 mTLS。
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getClientKeyPath())) {
            builder.withClientKeyPath(clientProperties.getClientKeyPath());
        }
        // 客户端证书路径，用于 mTLS。
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getClientPemPath())) {
            builder.withClientPemPath(clientProperties.getClientPemPath());
        }
        // CA 证书路径，用于校验服务端证书链。
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getCaPemPath())) {
            builder.withCaPemPath(clientProperties.getCaPemPath());
        }
        // 服务端证书路径，部分私有部署会要求显式传入。
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getServerPemPath())) {
            builder.withServerPemPath(clientProperties.getServerPemPath());
        }
        // serverName 用于 TLS SNI/证书校验，托管服务经常需要它匹配证书域名。
        if (clientProperties.isSecure() && StringUtils.hasText(clientProperties.getServerName())) {
            builder.withServerName(clientProperties.getServerName());
        }

        // 构建最终连接参数并创建 MilvusServiceClient，后续 VectorStore 和自定义仓储都会复用这个 Bean。
        return new MilvusServiceClient(builder.build());
    }
}
