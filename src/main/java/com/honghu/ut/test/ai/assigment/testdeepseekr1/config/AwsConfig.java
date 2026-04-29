package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;


/**
 * AWS 统一配置类。
 *
 * <p>集中初始化所有 AWS SDK v2 客户端 Bean：</p>
 * <ul>
 *     <li>{@link S3Client} — 对象存储操作（桶管理、对象读写）</li>
 *     <li>{@link S3Presigner} — 生成预签名 URL（前端直传）</li>
 *     <li>{@link SqsClient} — 消息队列操作（发送/接收消息）</li>
 * </ul>
 *
 * <p>所有客户端共享 {@link AwsProperties} 中的全局 endpoint / region / credentials，
 * 个别服务（如 SQS）可通过子配置单独覆盖 endpoint。</p>
 *
 * @author shilaoban
 * @since 2026-04-18
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AwsConfig {

    private final AwsProperties awsProperties;

    // ==================== 凭证构建 ====================

    /**
     * 构建统一的 AWS 凭证提供器。
     */
    private AwsCredentialsProvider credentialsProvider() {
        if (awsProperties.hasProfile()) {
            log.info("AWS 凭证模式: Profile = [{}]", awsProperties.getProfile());
            return ProfileCredentialsProvider.create(awsProperties.getProfile());
        }
        if (awsProperties.hasStaticKey()) {
            log.info("AWS 凭证模式: Static Access Key");
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(awsProperties.getAccessKey(), awsProperties.getSecretKey()));
        }
        log.info("AWS 凭证模式: DefaultCredentialsProvider（自动链式查找）");
        return DefaultCredentialsProvider.create();
    }

    // ==================== S3 客户端 ====================

    /**
     * S3 客户端，用于桶/对象的增删改查操作。
     *
     * <p>直连 AWS S3，无需自定义 endpoint，SDK 自动按 region 路由。</p>
     */
    @Bean
    public S3Client s3Client() {
        log.info("初始化 S3Client: region={}", awsProperties.getRegion());
        return S3Client.builder()
                .region(Region.of(awsProperties.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    /**
     * S3 预签名器，用于生成带有效期的预签名上传/下载 URL。
     *
     * <p>前端拿到预签名 URL 后，直接用 HTTP PUT/GET 与 S3 交互，
     * 服务端不需要做流量中转，降低带宽压力。</p>
     */
    @Bean
    public S3Presigner s3Presigner() {
        log.info("初始化 S3Presigner: region={}", awsProperties.getRegion());
        return S3Presigner.builder()
                .region(Region.of(awsProperties.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    // ==================== SQS 客户端 ====================

    /**
     * SQS 客户端，用于消息队列的发送/接收/删除操作。
     *
     * <p>仅在 {@code app.aws.sqs.enabled=true} 时才创建此 Bean，
     * 避免未使用 SQS 时产生无效连接。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.aws.sqs", name = "enabled", havingValue = "true")
    public SqsClient sqsClient() {

        log.info("初始化 SqsClient: region={}", awsProperties.getRegion());
        return SqsClient.builder()
                .region(Region.of(awsProperties.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();
    }
}

