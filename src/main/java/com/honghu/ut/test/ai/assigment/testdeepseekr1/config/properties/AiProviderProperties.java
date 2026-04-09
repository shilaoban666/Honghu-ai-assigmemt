package com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模型提供商与路由配置。
 *
 * <p>把模型链接、鉴权方式、默认路由模型统一收口到配置文件，
 * 便于后续切换不同 provider 或通过环境变量注入 API Key。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.ai")
public class AiProviderProperties {

    /** 默认模型编码。 */
    private String defaultModel = "deepseek-r1:8b";

    /** 连接健康检查间隔，保留兼容已有配置。 */
    private long connectionHealthCheckInterval = 30000L;

    /**
     * 通用 HTTP 连接配置。
     *
     * <p>主要给外部 provider 的统一 HTTP 客户端使用。</p>
     */
    private Connection connection = new Connection();

    /** 简单/复杂问题的默认路由配置。 */
    private Routing routing = new Routing();

    /**
     * 多 provider 配置。
     *
     * <p>这里的 key 必须与 {@code ai_model_definition.provider_code} 对齐，
     * 这样模型目录表和配置文件才能正确关联起来。</p>
     */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    @Data
    public static class Routing {
        /** 闲聊 / 短问题阈值。 */
        private int simpleQueryLengthThreshold = 20;

        /** 简单问题默认模型。 */
        private String simpleDefaultModel = "deepseek-r1:8b";

        /** 复杂问题默认模型。 */
        private String complexDefaultModel = "deepseek-r1:32b";
    }

    @Data
    public static class Connection {
        /** 连接池大小。 */
        private int poolSize = 50;

        /** 连接超时，毫秒。 */
        private long connectTimeout = 120000L;

        /** 读取超时，毫秒。 */
        private long readTimeout = 600000L;

        /** 是否保持连接复用 / 失败重试。 */
        private boolean keepAlive = true;
    }

    @Data
    public static class Provider {
        /**
         * provider 类型：本地 Ollama 或 OpenAI 兼容接口。
         */
        private ProviderType type = ProviderType.OPENAI_COMPATIBLE;

        /**
         * 基础 URL。
         *
         * <p>例如：</p>
         * <ul>
         *     <li>本地 Ollama：{@code http://localhost:11434}</li>
         *     <li>DeepSeek 官方：{@code https://api.deepseek.com}</li>
         *     <li>其他聚合网关：按网关文档填写</li>
         * </ul>
         */
        private String baseUrl;

        /** Chat Completions 路径。 */
        private String chatCompletionsPath = "/v1/chat/completions";

        /**
         * 是否启用 API Key。
         *
         * <p>本地 localhost / Ollama 模型一般为 false；外部模型通常为 true。</p>
         */
        private boolean useApiKey = true;

        /**
         * API Key。
         *
         * <p>这里强调一下：DeepSeek 平台拿到的是 API Key，而不是 SSH Key。</p>
         */
        private String apiKey;

        /** API Key header 名称，例如 Authorization / x-api-key。 */
        private String apiKeyHeader = "Authorization";

        /** API Key 前缀，例如 Bearer 。 */
        private String apiKeyPrefix = "Bearer ";

        /** 额外固定请求头。 */
        private Map<String, String> headers = new LinkedHashMap<>();

        /** 是否启用该 provider。 */
        private boolean enabled = true;
    }

    public enum ProviderType {
        OLLAMA_LOCAL,
        OPENAI_COMPATIBLE
    }
}



