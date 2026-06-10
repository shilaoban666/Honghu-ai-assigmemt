package com.honghu.ai.assigment.skill.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link SecretCipher} 只加密敏感字段，并能正确往返解密。
 */
class SecretCipherTest {

    private SecretCipher secretCipher;

    @BeforeEach
    void setUp() {
        secretCipher = new SecretCipher(new ObjectMapper());
        ReflectionTestUtils.setField(secretCipher, "configuredSecretKey", "unit-test-secret-key");
        ReflectionTestUtils.setField(secretCipher, "configuredSecretSalt", "unit-test-salt");
        ReflectionTestUtils.setField(secretCipher, "allowDevSecretFallback", true);
    }

    @Test
    void encryptsSensitiveFieldsButKeepsPublicFieldsReadable() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apiKey", "super-secret-token");
        config.put("endpoint", "https://mcp.example.com/rpc");
        config.put("transport", "streamable-http");

        String json = secretCipher.encryptConfigToJson(config);

        // 敏感值被加密（带版本前缀），明文不应出现；公开字段保持可读。
        assertThat(json).contains("enc:v1:");
        assertThat(json).doesNotContain("super-secret-token");
        assertThat(json).contains("https://mcp.example.com/rpc");
        assertThat(json).contains("streamable-http");
    }

    @Test
    void decryptRestoresOriginalSensitiveValues() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apiKey", "super-secret-token");
        config.put("endpoint", "https://mcp.example.com/rpc");

        String json = secretCipher.encryptConfigToJson(config);
        Map<String, Object> decrypted = secretCipher.decryptConfig(json);

        assertThat(decrypted.get("apiKey")).isEqualTo("super-secret-token");
        assertThat(decrypted.get("endpoint")).isEqualTo("https://mcp.example.com/rpc");
    }

    @Test
    void blankConfigDecryptsToEmptyMap() {
        assertThat(secretCipher.decryptConfig(null)).isEmpty();
        assertThat(secretCipher.decryptConfig("")).isEmpty();
    }
}
