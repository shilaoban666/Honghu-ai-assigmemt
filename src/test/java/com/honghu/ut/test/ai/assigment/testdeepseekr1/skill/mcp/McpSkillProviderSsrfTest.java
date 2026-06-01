package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.mcp.McpSkillProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link McpSkillProvider} 的 SSRF 端点校验。
 *
 * <p>用例使用字面 IP（如 8.8.8.8）和私网/环回字面量，不触发外部 DNS 解析，结果稳定。</p>
 */
class McpSkillProviderSsrfTest {

    private McpSkillProvider provider() {
        // isEndpointSafe 不依赖仓库/HTTP 客户端，未用到的依赖传 null。
        return new McpSkillProvider(new ObjectMapper(), null, null, null, null);
    }

    @Test
    void rejectsLoopbackEndpoint() {
        assertThat(provider().isEndpointSafe("http://localhost:8080/rpc")).isFalse();
        assertThat(provider().isEndpointSafe("http://127.0.0.1:8080/rpc")).isFalse();
    }

    @Test
    void rejectsCloudMetadataEndpoint() {
        assertThat(provider().isEndpointSafe("http://169.254.169.254/latest/meta-data")).isFalse();
    }

    @Test
    void rejectsPrivateNetworkEndpoints() {
        assertThat(provider().isEndpointSafe("http://10.0.0.5/rpc")).isFalse();
        assertThat(provider().isEndpointSafe("http://192.168.1.10/rpc")).isFalse();
        assertThat(provider().isEndpointSafe("http://172.16.0.9/rpc")).isFalse();
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThat(provider().isEndpointSafe("ftp://example.com/rpc")).isFalse();
        assertThat(provider().isEndpointSafe("file:///etc/passwd")).isFalse();
    }

    @Test
    void allowsPublicLiteralAddress() {
        assertThat(provider().isEndpointSafe("https://8.8.8.8/rpc")).isTrue();
    }

    @Test
    void allowPrivateEndpointsFlagBypassesChecks() {
        McpSkillProvider provider = provider();
        ReflectionTestUtils.setField(provider, "allowPrivateEndpoints", true);
        assertThat(provider.isEndpointSafe("http://localhost:8080/rpc")).isTrue();
    }
}
