package com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单 RAG 配置。
 *
 * <p>这一版先聚焦“文档上传完成后 -> SQS 监听 -> S3 拉取文件 -> 文本抽取 -> 分块落库 -> 检索预留”的基础链路，
 * 不直接在这里做复杂的向量数据库集成，后续可在此配置上继续扩展 embedding / rerank / OCR 等能力。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 是否启用整套 RAG 基础能力。 */
    private boolean enabled = true;

    /** SQS 监听器相关配置。 */
    private Listener listener = new Listener();

    /** 文档摄取（ingestion）相关配置。 */
    private Ingestion ingestion = new Ingestion();

    /** 简单检索相关配置。 */
    private Retrieval retrieval = new Retrieval();

    @Data
    public static class Listener {
        /** 是否启用文档上传完成事件监听。 */
        private boolean enabled = true;

        /**
         * 要监听的队列名称。
         *
         * <p>当前由 {@code @SqsListener("${app.rag.listener.queue-name}")} 直接使用，
         * 因此这是文档监听入口真正生效的核心配置。</p>
         */
        private String queueName = "honghu-ai-document-upload-received";

        /**
         * SQS 消息允许处理的 S3 bucket 白名单。
         *
         * <p>防御 SSRF / 越权读 S3：如果攻击者能往队列里投伪造事件，
         * 没有这个白名单时服务会乖乖去下载攻击者指定的任意 bucket。</p>
         *
         * <p>留空时<strong>退化为只信任 {@code app.aws.s3.uploaded-bucket}</strong>，
         * 这是当前上传链路真正落盘的目标桶，也是绝大多数生产场景下唯一合法的来源桶。</p>
         */
        private List<String> allowedBuckets = new ArrayList<>();

        /**
         * 当一条事件停留在 PROCESSING 状态超过这个时长（分钟）时，被视为"僵尸锁"，
         * 后续重试可以强行夺锁继续处理。
         *
         * <p>阈值需要明显大于"最坏情况下一次摄取的耗时"。当前默认 10 分钟，
         * 留出足够缓冲应对慢 PDF / 大文件 / 临时网络抖动；
         * 同时也需要小于 SQS visibility 超时 + maxReceiveCount 的乘积，
         * 否则消息进 DLQ 时锁还没过期。</p>
         */
        private int staleProcessingMinutes = 10;
    }

    @Data
    public static class Ingestion {
        /** 单个对象允许处理的最大字节数，避免一次性拉取超大文件压垮服务。 */
        private long maxObjectSizeBytes = 20L * 1024 * 1024;

        /** 当前简单版支持的可解析扩展名。 */
        private List<String> supportedExtensions = new ArrayList<>(List.of("txt", "md", "json", "xml", "csv", "pdf"));

        /** 单个 chunk 的目标字符数。 */
        private int chunkSize = 800;

        /** 相邻 chunk 之间保留的重叠字符数，降低语义断裂。 */
        private int chunkOverlap = 120;

        /** 单个文档最多切成多少个 chunk，避免异常长文本无限膨胀。 */
        private int maxChunksPerDocument = 200;

        /** 过短 chunk 通常价值不高，低于该长度会尽量与相邻文本合并。 */
        private int minChunkLength = 80;

        /** 抽取后的最大保留字符数，防止脏文件或超长文本把数据库撑爆。 */
        private int maxExtractedCharacters = 100000;
    }

    @Data
    public static class Retrieval {
        /** 是否启用简单检索服务。 */
        private boolean enabled = true;

        /** 每次最多返回多少个候选片段。 */
        private int topK = 4;

        /** 检索前先从数据库拉多少个候选 chunk 参与打分。 */
        private int candidateLimit = 80;

        /** 拼接给大模型前，最多保留多少个字符的检索上下文。 */
        private int maxContextCharacters = 3000;

        /** 关键词最短长度，过短词通常噪音较大。 */
        private int minKeywordLength = 2;
    }
}


