package com.honghu.ai.assigment.config.properties;

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
    private String defaultModel = "deepseek-v4-flash";

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
     * 本地 Ollama 不可用时的自动回退配置。
     *
     * <p>目标是让系统“优先本地、失败回云端”：
     * 当用户请求命中了本地模型，但本机没有启动 Ollama 时，
     * 可以自动切换到配置好的 DeepSeek 云端基础模型，而不是把连接异常直接暴露给用户。</p>
     */
    private LocalModelFallback localModelFallback = new LocalModelFallback();

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
        private String simpleDefaultModel = "deepseek-v4-flash";

        /** 复杂问题默认模型。 */
        private String complexDefaultModel = "deepseek-v4-pro";
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
    public static class LocalModelFallback {
        /**
         * 是否启用“本地模型不可用 -> 云端模型”自动回退。
         */
        private boolean enabled = true;

        /**
         * 本地 provider 编码。
         *
         * <p>当前默认是 {@code ollama-local}，需要与数据库中的
         * {@code ai_model_definition.provider_code} 保持一致。</p>
         */
        private String localProviderCode = "ollama-local";

        /**
         * 首选云端回退模型编码。
         *
         * <p>例如：{@code deepseek-chat}。</p>
         */
        private String fallbackModel = "deepseek-v4-flash";

        /**
         * 是否在模型路由阶段先探测一次本地 Ollama 连通性。
         *
         * <p>开启后，可在真正发请求前就提前切到云端，避免一次无意义的本地连接失败重试。</p>
         */
        private boolean probeBeforeRoute = true;
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



