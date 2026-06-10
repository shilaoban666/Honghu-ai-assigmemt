package com.honghu.ai.assigment.skill.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 技能安装配置中敏感字段的 AES-GCM 加解密工具。
 *
 * <p>{@code user_skill_install.user_config} 是 JSONB 字段，因为 MCP/CLI 能力需要保存用户自己的连接配置。
 * 其中 endpoint、transport 这类公开配置可以明文保存，方便排查；但 {@code apiKey}、{@code token}、
 * {@code password}、{@code secret}、OAuth 凭证等敏感字段不能明文入库。本组件会递归遍历配置 Map，
 * 只加密“字段名看起来敏感”的叶子值，非敏感字段保持可读。</p>
 *
 * <p>加密密钥来自 {@code APP_SKILL_SECRET_KEY} 或 {@code app.skill.secret-key}。本地开发可通过
 * {@code app.skill.allow-dev-secret-fallback=true} 使用确定性兜底密钥，便于测试和 demo；生产环境必须提供
 * 稳定的外部密钥，或者替换成 KMS/Secrets Manager。</p>
 */
@Component
@RequiredArgsConstructor
public class SecretCipher {

    /** 加密值前缀；写进 JSON 后可被未来迁移程序识别版本。 */
    private static final String PREFIX = "enc:v1:";

    /** AES-GCM 推荐使用 96 位随机 IV，也就是 12 字节。 */
    private static final int IV_BYTES = 12;

    /** GCM 认证标签长度，单位 bit；128 是常见安全取值。 */
    private static final int TAG_BITS = 128;

    /** PBKDF2 迭代次数；较高迭代次数可以提高泄漏口令被暴力破解的成本。 */
    private static final int PBKDF2_ITERATIONS = 120_000;

    /** 派生出的 AES 密钥长度，单位 bit。 */
    private static final int KEY_BITS = 256;

    /** Jackson 读取 JSONB 字符串时使用的 Map 类型引用。 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** JSON 序列化工具。 */
    private final ObjectMapper objectMapper;

    /** 生成 AES-GCM IV 的安全随机数源。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 延迟派生并缓存的 AES 密钥；PBKDF2 计算较重，所以只派生一次。 */
    private volatile SecretKeySpec cachedKey;

    @Value("${app.skill.secret-key:${APP_SKILL_SECRET_KEY:}}")
    private String configuredSecretKey;

    /**
     * PBKDF2 派生密钥使用的 salt。
     *
     * <p>salt 不是密钥，而是密钥派生的扰动值。这里使用稳定配置值，是为了保证应用重启后能派生出同一把 AES key，
     * 从而解开已经保存的密文。不同环境可以覆盖成不同 salt，作为额外防护。</p>
     */
    @Value("${app.skill.secret-salt:honghu-skill-secret-salt-v1}")
    private String configuredSecretSalt;

    @Value("${app.skill.allow-dev-secret-fallback:true}")
    private boolean allowDevSecretFallback;

    /**
     * 把配置 Map 转成可落库的加密 JSON 字符串。
     *
     * @param plainConfig 合并后的公开配置和敏感配置
     * @return 可以安全保存到 {@code user_skill_install.user_config} 的 JSON 字符串
     */
    public String encryptConfigToJson(Map<String, Object> plainConfig) {
        try {
            // null 配置按空对象保存，避免数据库出现 null JSON。
            return objectMapper.writeValueAsString(encryptMap(plainConfig == null ? Map.of() : plainConfig));
        } catch (Exception ex) {
            // 序列化失败通常说明配置里有 Jackson 不支持的对象。
            throw new IllegalArgumentException("Unable to serialize encrypted skill config", ex);
        }
    }

    /**
     * 从存储中的 JSON 读取配置，并解密敏感叶子字段。
     *
     * @param storedJson 来自 {@code user_skill_install.user_config} 的 JSONB 字符串
     * @return 内存态配置 Map；敏感值在返回结果里是明文，只应在本次运行时使用
     */
    public Map<String, Object> decryptConfig(String storedJson) {
        if (!StringUtils.hasText(storedJson)) {
            // 没有保存配置时返回空 Map，调用方不用判空。
            return Map.of();
        }
        try {
            // 先把 JSONB 字符串读成 Map，再递归解密其中 enc:v1: 前缀的值。
            Map<String, Object> map = objectMapper.readValue(storedJson, MAP_TYPE);
            return decryptMap(map);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to decrypt skill config", ex);
        }
    }

    /**
     * 加密单个敏感字符串（如模型 / Provider 的 API Key）。
     *
     * <p>null/空白原样返回；已是 {@code enc:v1:} 密文则幂等返回，避免二次加密。</p>
     */
    public String encryptSecret(String plain) {
        if (!StringUtils.hasText(plain)) {
            return plain;
        }
        if (plain.startsWith(PREFIX)) {
            return plain;
        }
        return encryptString(plain);
    }

    /**
     * 解密单个敏感字符串。
     *
     * <p>null/空白或非 {@code enc:v1:} 前缀（兼容历史明文 / 本地无 key）原样返回。</p>
     */
    public String decryptSecret(String stored) {
        if (!StringUtils.hasText(stored)) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        return decryptString(stored);
    }

    /**
     * 加密 Map 中的敏感字段，同时保留公开字段原样。
     *
     * @param source 明文配置 Map
     * @return 已加密敏感字段后的配置 Map
     */
    public Map<String, Object> encryptMap(Map<String, Object> source) {
        // 用 LinkedHashMap 保持原字段顺序，便于排查和测试断言。
        Map<String, Object> encrypted = new LinkedHashMap<>();
        if (source == null) {
            return encrypted;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                // 嵌套对象递归处理，例如 headers.authorization。
                encrypted.put(entry.getKey(), encryptMap(castMap(nested)));
            } else if (isSensitiveKey(entry.getKey()) && value != null) {
                // 字段名命中敏感规则且值非空时，只加密这个叶子值。
                encrypted.put(entry.getKey(), encryptString(String.valueOf(value)));
            } else {
                // 非敏感字段保持原样，便于运营排查 endpoint、region 等配置。
                encrypted.put(entry.getKey(), value);
            }
        }
        return encrypted;
    }

    /**
     * 解密配置 Map 中的敏感字段。
     *
     * @param source 存储态配置 Map
     * @return 运行时明文配置 Map
     */
    public Map<String, Object> decryptMap(Map<String, Object> source) {
        // 同样保持字段顺序。
        Map<String, Object> decrypted = new LinkedHashMap<>();
        if (source == null) {
            return decrypted;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                // 嵌套对象递归解密。
                decrypted.put(entry.getKey(), decryptMap(castMap(nested)));
            } else if (value instanceof String text && text.startsWith(PREFIX)) {
                // 只有带 enc:v1: 前缀的字符串才解密，避免误处理普通文本。
                decrypted.put(entry.getKey(), decryptString(text));
            } else {
                // 非加密值原样返回。
                decrypted.put(entry.getKey(), value);
            }
        }
        return decrypted;
    }

    /**
     * 加密单个字符串值。
     */
    private String encryptString(String plainText) {
        try {
            // 每个值都使用独立随机 IV；同样明文多次加密也会得到不同密文。
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            // AES/GCM/NoPadding 同时提供机密性和完整性校验。
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            // 明文按 UTF-8 转字节后加密，得到密文+认证标签。
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // 使用 URL-safe Base64，避免 JSON/URL 传输时出现特殊字符问题。
            return PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(cipherText);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt skill secret", ex);
        }
    }

    /**
     * 解密单个 enc:v1: 字符串值。
     */
    private String decryptString(String value) {
        try {
            // 去掉版本前缀，剩下格式是 base64(iv):base64(cipherText)。
            String payload = value.substring(PREFIX.length());
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid encrypted secret format");
            }
            // 解码 IV 和密文。
            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getUrlDecoder().decode(parts[1]);
            // 用同一派生密钥和 IV 初始化 GCM 解密。
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            // GCM 标签校验失败时 doFinal 会抛异常，防止篡改密文被静默接受。
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decrypt skill secret", ex);
        }
    }

    /**
     * 派生并缓存 AES 密钥。
     */
    private SecretKeySpec keySpec() {
        // volatile 读：绝大多数调用直接命中缓存，避免重复 PBKDF2。
        SecretKeySpec local = cachedKey;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            // 双重检查，避免多个线程同时进入 synchronized 后重复派生。
            if (cachedKey != null) {
                return cachedKey;
            }
            // 优先使用外部配置的密钥短语。
            String passphrase = configuredSecretKey;
            if (!StringUtils.hasText(passphrase)) {
                if (!allowDevSecretFallback) {
                    // 生产环境应关闭 fallback，缺密钥直接失败。
                    throw new IllegalStateException("app.skill.secret-key or APP_SKILL_SECRET_KEY must be configured");
                }
                // 本地开发兜底密钥只用于 demo/test，不应在生产使用。
                passphrase = "local-development-skill-secret-change-me";
            }
            try {
                // 用“口令 + 稳定 salt”跑 PBKDF2，比单次 SHA-256 更抗暴力破解；
                // 较高迭代次数会让攻击者尝试大量候选口令的成本更高。
                byte[] salt = configuredSecretSalt.getBytes(StandardCharsets.UTF_8);
                // PBEKeySpec 定义 PBKDF2 输入：口令、salt、迭代次数、密钥长度。
                KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
                // 使用 HMAC-SHA256 作为 PBKDF2 的伪随机函数。
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                // 派生原始 AES key 字节。
                byte[] keyBytes = factory.generateSecret(spec).getEncoded();
                // 缓存为 AES SecretKeySpec，后续加解密复用。
                cachedKey = new SecretKeySpec(keyBytes, "AES");
                return cachedKey;
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to derive skill secret key", ex);
            }
        }
    }

    /**
     * 判断字段名是否属于敏感配置。
     */
    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        // 字段名统一转小写，兼容 apiKey、API_KEY、Authorization 等写法。
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("authorization");
    }

    /**
     * 将任意 key 类型的 Map 转成 String key 的 Map。
     */
    private Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            // JSON 对象 key 本质是字符串；这里统一 String.valueOf，方便递归处理。
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
