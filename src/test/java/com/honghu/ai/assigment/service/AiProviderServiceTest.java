package com.honghu.ai.assigment.service;

import com.honghu.ai.assigment.config.properties.AiProviderProperties;
import com.honghu.ai.assigment.entity.AiProvider;
import com.honghu.ai.assigment.repository.AiProviderRepository;
import com.honghu.ai.assigment.skill.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 验证 Provider 解析的两条核心规则：
 * <ol>
 *     <li>数据库 ai_provider 命中 → 解密 API Key 后返回运行期配置；</li>
 *     <li>数据库未命中 → 回退到 application.yml 的 app.ai.providers（向后兼容）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AiProviderServiceTest {

    @Mock
    private AiProviderRepository aiProviderRepository;
    @Mock
    private SecretCipher secretCipher;

    private AiProviderProperties aiProviderProperties;
    private AiProviderService service;

    @BeforeEach
    void setUp() {
        aiProviderProperties = new AiProviderProperties();
        aiProviderProperties.setProviders(new LinkedHashMap<>());
        service = new AiProviderService(aiProviderRepository, aiProviderProperties, secretCipher);
    }

    @Test
    void shouldResolveFromDatabaseAndDecryptApiKey() {
        AiProvider db = AiProvider.builder()
                .providerCode("deepseek-cloud")
                .providerType(AiProviderProperties.ProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://api.deepseek.com")
                .chatCompletionsPath("/v1/chat/completions")
                .useApiKey(true)
                .apiKeyCipher("enc:v1:cipher-text")
                .apiKeyHeader("Authorization")
                .apiKeyPrefix("Bearer ")
                .enabled(true)
                .build();
        when(aiProviderRepository.findById("deepseek-cloud")).thenReturn(Optional.of(db));
        when(secretCipher.decryptSecret("enc:v1:cipher-text")).thenReturn("sk-real-secret-key");

        AiProviderProperties.Provider resolved = service.resolve("deepseek-cloud");

        assertThat(resolved.getType()).isEqualTo(AiProviderProperties.ProviderType.OPENAI_COMPATIBLE);
        assertThat(resolved.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(resolved.getApiKey()).isEqualTo("sk-real-secret-key");
        assertThat(resolved.isUseApiKey()).isTrue();
    }

    @Test
    void shouldFallBackToYamlWhenNotInDatabase() {
        AiProviderProperties.Provider yaml = new AiProviderProperties.Provider();
        yaml.setType(AiProviderProperties.ProviderType.OLLAMA_LOCAL);
        yaml.setBaseUrl("http://localhost:11434");
        yaml.setEnabled(true);
        aiProviderProperties.getProviders().put("ollama-local", yaml);
        when(aiProviderRepository.findById("ollama-local")).thenReturn(Optional.empty());

        AiProviderProperties.Provider resolved = service.resolve("ollama-local");

        assertThat(resolved).isSameAs(yaml);
    }

    @Test
    void shouldThrowWhenDatabaseProviderDisabled() {
        AiProvider db = AiProvider.builder()
                .providerCode("disabled-one")
                .providerType(AiProviderProperties.ProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://example.com")
                .enabled(false)
                .build();
        when(aiProviderRepository.findById("disabled-one")).thenReturn(Optional.of(db));

        assertThatThrownBy(() -> service.resolve("disabled-one"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldThrowWhenProviderMissingEverywhere() {
        when(aiProviderRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("ghost"))
                .isInstanceOf(IllegalStateException.class);
    }
}
