package com.honghu.ai.assigment.entity;

import com.honghu.ai.assigment.config.properties.AiProviderProperties.ProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI Provider（模型提供商）注册表实体。
 *
 * <p>把原本写死在 {@code application.yml} 里的 provider 配置（base-url、鉴权方式、API Key）
 * 落库，使管理员可以在后台自由新增/修改/删除 provider。{@code ai_model_definition.provider_code}
 * 引用本表的 {@code provider_code}。</p>
 *
 * <p><b>安全：</b>{@link #apiKeyCipher} 存储的是经 {@code SecretCipher}（AES-256-GCM）加密后的密文，
 * 形如 {@code enc:v1:...}，绝不明文落库；任何对外查询接口都不得返回明文，只回显掩码。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_provider")
public class AiProvider {

    /** provider 编码，作为唯一标识，与 ai_model_definition.provider_code 对齐。 */
    @Id
    @Column(name = "provider_code", length = 64)
    private String providerCode;

    /** 展示名称，便于后台列表识别。 */
    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    /** provider 类型：本地 Ollama 或 OpenAI 兼容接口。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 32)
    private ProviderType providerType;

    /** 基础 URL，例如 https://api.deepseek.com 或 http://localhost:11434。 */
    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    /** Chat Completions 路径。 */
    @Builder.Default
    @Column(name = "chat_completions_path", nullable = false, length = 200)
    private String chatCompletionsPath = "/v1/chat/completions";

    /** 是否启用 API Key（本地 Ollama 一般为 false）。 */
    @Builder.Default
    @Column(name = "use_api_key", nullable = false)
    private Boolean useApiKey = Boolean.TRUE;

    /**
     * 加密后的 API Key 密文（{@code enc:v1:...}）。
     *
     * <p>由 {@code SecretCipher} 加密；可为空（本地模型无需 key）。</p>
     */
    @Column(name = "api_key_cipher", columnDefinition = "TEXT")
    private String apiKeyCipher;

    /** API Key 请求头名称，如 Authorization / x-api-key。 */
    @Builder.Default
    @Column(name = "api_key_header", nullable = false, length = 64)
    private String apiKeyHeader = "Authorization";

    /** API Key 前缀，如 "Bearer "；部分厂商为空。 */
    @Column(name = "api_key_prefix", length = 32)
    private String apiKeyPrefix;

    /** 是否启用该 provider。 */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
