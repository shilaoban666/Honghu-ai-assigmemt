package com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AWS 全局配置属性。
 *
 * <p>统一管理所有 AWS 服务（S3、SQS 等）的连接信息，
 * 支持 AWS 原生服务和兼容协议的替代品（如 MinIO、LocalStack）。</p>
 *
 * <p>配置前缀：{@code app.aws}</p>
 *
 * @author shilaoban
 * @since 2026-04-18
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.aws")
public class AwsProperties {

    // ==================== 全局公共配置 ====================
    /**
     * AWS 命名 Profile（对应 ~/.aws/credentials 中的 [profile名称]）。
     * 配置此项后优先读取本地 credentials 文件，无需填写 access-key / secret-key。
     */


    private String profile;

    /** AWS 服务端点（可替换为 MinIO / LocalStack 地址） */
    private String endpoint ;

    /** AWS 区域 */
    private String region = "us-east-1";

    /** AWS Access Key ID */
    private String accessKey ;

    /** AWS Secret Access Key */
    private String secretKey ;

    // ==================== S3 对象存储配置 ====================

    /** S3 配置 */
    private S3 s3 = new S3();

    @Data
    public static class S3 {
        /** 是否启用 S3  */
        private boolean enabled = false;
        /** 存储桶名称 */
        private String uploadedBucket;

        /** 预签名 URL 过期时间（分钟） */
        private int presignedUrlExpirationMinutes = 30;
    }

    // ==================== SQS 消息队列配置 ====================

    /** SQS 配置 */
    private Sqs sqs = new Sqs();

    @Data
    public static class Sqs {
        /** 是否启用 SQS */
        private boolean enabled = false;

        /** SQS 端点（可单独覆盖，默认复用全局 endpoint） */
        private String endpoint;

        /**
         * 消息可见性超时（秒）。
         *
         * <p>当前主要保留给手工使用 AWS SDK 的场景；
         * 文档上传监听已经迁移到 {@code @SqsListener}，不再直接读取该值。</p>
         */
        private int visibilityTimeoutSeconds = 30;

        /**
         * 长轮询等待时间（秒），0 表示短轮询。
         *
         * <p>当前同样主要保留给手工 AWS SDK 调用场景。</p>
         */
        private int waitTimeSeconds = 20;
    }
    public boolean hasProfile() {
        return profile != null && !profile.isBlank();
    }
    public boolean hasStaticKey() {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
    public boolean hasCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}

