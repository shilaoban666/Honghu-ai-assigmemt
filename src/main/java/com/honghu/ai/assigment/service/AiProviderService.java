package com.honghu.ai.assigment.service;

import com.honghu.ai.assigment.config.properties.AiProviderProperties;
import com.honghu.ai.assigment.entity.AiProvider;
import com.honghu.ai.assigment.repository.AiProviderRepository;
import com.honghu.ai.assigment.skill.security.SecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Provider 配置解析服务。
 *
 * <p>统一对外提供「按 providerCode 取得运行期可用的 provider 配置」的能力，解析顺序：</p>
 * <ol>
 *   <li><b>数据库 {@code ai_provider} 表（管理员后台维护）</b> —— 命中则解密其 API Key 后返回；</li>
 *   <li><b>回退到 {@code application.yml} 的 {@code app.ai.providers}</b> —— 保证历史配置与本地开发不受影响。</li>
 * </ol>
 *
 * <p>这样既实现了「后台自由增删改 provider + 加密存 key」，又对现有调用链完全向后兼容：
 * 数据库没有任何 provider 记录时，行为与改造前一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProviderService {

    private final AiProviderRepository aiProviderRepository;
    private final AiProviderProperties aiProviderProperties;
    private final SecretCipher secretCipher;

    /**
     * 解析出运行期可用的 provider 配置。
     *
     * @param providerCode 模型上的 provider 编码
     * @return 运行期 provider 配置（API Key 已解密为明文，仅用于本次调用）
     * @throws IllegalStateException provider 不存在或已禁用
     */
    public AiProviderProperties.Provider resolve(String providerCode) {
        AiProvider dbProvider = aiProviderRepository.findById(providerCode).orElse(null);
        if (dbProvider != null) {
            if (Boolean.FALSE.equals(dbProvider.getEnabled())) {
                throw new IllegalStateException("provider 已禁用: " + providerCode);
            }
            return toRuntimeProvider(dbProvider);
        }

        // 数据库未配置该 provider 时，回退到 application.yml，保持向后兼容。
        AiProviderProperties.Provider yamlProvider = aiProviderProperties.getProviders().get(providerCode);
        if (yamlProvider == null) {
            throw new IllegalStateException("未找到 provider 配置: " + providerCode);
        }
        if (!yamlProvider.isEnabled()) {
            throw new IllegalStateException("provider 已禁用: " + providerCode);
        }
        return yamlProvider;
    }

    /**
     * 把数据库实体转成运行期 provider 配置，并解密 API Key。
     *
     * <p>解密只在调用瞬间发生，明文不缓存、不入库、不回传给前端。</p>
     */
    private AiProviderProperties.Provider toRuntimeProvider(AiProvider db) {
        AiProviderProperties.Provider provider = new AiProviderProperties.Provider();
        provider.setType(db.getProviderType());
        provider.setBaseUrl(db.getBaseUrl());
        provider.setChatCompletionsPath(db.getChatCompletionsPath());
        provider.setUseApiKey(Boolean.TRUE.equals(db.getUseApiKey()));
        provider.setApiKey(secretCipher.decryptSecret(db.getApiKeyCipher()));
        provider.setApiKeyHeader(db.getApiKeyHeader());
        provider.setApiKeyPrefix(db.getApiKeyPrefix());
        provider.setEnabled(Boolean.TRUE.equals(db.getEnabled()));
        return provider;
    }
}
