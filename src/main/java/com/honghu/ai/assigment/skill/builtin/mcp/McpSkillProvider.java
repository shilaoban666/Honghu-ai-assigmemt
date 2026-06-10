package com.honghu.ai.assigment.skill.builtin.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.skill.core.DangerLevel;
import com.honghu.ai.assigment.skill.core.ToolCallbackRegistration;
import com.honghu.ai.assigment.skill.entity.Skill;
import com.honghu.ai.assigment.skill.entity.SkillTool;
import com.honghu.ai.assigment.skill.entity.UserSkillInstall;
import com.honghu.ai.assigment.skill.repository.SkillToolRepository;
import com.honghu.ai.assigment.skill.repository.UserSkillInstallRepository;
import com.honghu.ai.assigment.skill.security.SecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 HTTP JSON-RPC 的远程 MCP 工具提供器。
 *
 * <p>这个 provider 负责把“用户已经安装并启用的 MCP 技能”转换成 Spring AI 可调用工具。它会先调用
 * MCP 的 {@code tools/list} 拿到真实工具清单，再把每个远程工具包装成 {@code FunctionToolCallback}，
 * 最终交给 {@code SkillResolverService} 统一套上审计和危险等级守卫。</p>
 *
 * <p>当前实现只支持远程 {@code streamable-http}/{@code sse}/{@code http} 端点，并明确拒绝本地
 * {@code stdio} 端点。原因是 stdio MCP 需要启动用户控制的本地进程，必须先接入 CLI 沙箱、审批和审计
 * 后才能安全开放。</p>
 *
 * <p>安全边界包括：不执行安装命令、不启动本地进程、默认拒绝私网/环回/链路本地端点以防 SSRF、
 * 用户配置通过 {@link SecretCipher} 解密后只在内存中使用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpSkillProvider {

    /** MCP JSON-RPC 请求使用的媒体类型。 */
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** tools/list 结果缓存时间，避免每次聊天都重新向远程 MCP 拉工具清单。 */
    private static final Duration TOOL_CACHE_TTL = Duration.ofMinutes(5);

    /** JSON 序列化/反序列化工具，用于构造 JSON-RPC payload 和解析响应。 */
    private final ObjectMapper objectMapper;

    /** 用户安装关系仓库，用于确认当前用户是否真的安装并启用了某个 MCP。 */
    private final UserSkillInstallRepository userSkillInstallRepository;

    /** 工具清单仓库，运行时 tools/list 后会回填 skill_tool，供商店详情和审计使用。 */
    private final SkillToolRepository skillToolRepository;

    /** 用户安装配置中的敏感字段加解密工具，例如 API Key、token、authorization。 */
    private final SecretCipher secretCipher;

    /** 复用 AI 网关的 OkHttpClient，获得统一超时、代理和连接池配置。 */
    @Qualifier("aiGatewayOkHttpClient")
    private final OkHttpClient okHttpClient;

    /**
     * 是否允许 MCP 端点指向私网/环回/链路本地地址。
     *
     * <p>默认 false：拒绝指向 localhost、10.x/172.16-31.x/192.168.x、169.254.x（含云元数据
     * 169.254.169.254）等地址的端点，避免 SSRF。受控内网部署可显式打开。</p>
     */
    @Value("${app.skill.mcp.allow-private-endpoints:false}")
    private boolean allowPrivateEndpoints;

    /** tools/call 返回结果回喂模型前的最大字符数，避免超大对象塞爆上下文。 */
    @Value("${app.skill.mcp.max-result-chars:16000}")
    private int maxResultChars;

    /** 按 skill + 配置摘要缓存远程工具清单，避免频繁调用 tools/list。 */
    private final Map<String, CachedTools> cachedTools = new ConcurrentHashMap<>();

    /**
     * 将已启用、已安装、可安全访问的 MCP 技能解析成 Spring AI 工具回调。
     *
     * @param skills 已经经过全局启用、角色和会话开关过滤的 MCP 技能行
     * @param userId 可信用户 id，用来读取该用户自己的安装配置
     * @return 带平台元数据的工具回调列表；调用方还会继续包审计装饰器
     */
    public List<ToolCallbackRegistration> callbacksFor(List<Skill> skills, String userId) {
        // 没有技能、没有用户或匿名 guest 都不能解析 MCP；MCP 通常依赖用户级配置和密钥。
        if (skills == null || skills.isEmpty() || !StringUtils.hasText(userId) || "guest".equals(userId)) {
            return List.of();
        }
        // 收集所有可注入给模型的 MCP 工具注册信息。
        List<ToolCallbackRegistration> registrations = new ArrayList<>();
        for (Skill skill : skills) {
            // 只有当前用户安装并启用过的 MCP 才能被解析，不能仅凭全局 skill 启用就注入。
            Optional<UserSkillInstall> install = userSkillInstallRepository.findByUserIdAndSkillId(userId, skill.getId())
                    .filter(UserSkillInstall::isEnabled);
            if (install.isEmpty() || !isRemoteHttpMcp(skill)) {
                // 未安装、已停用、不是远程 HTTP MCP，或端点不安全时直接跳过。
                continue;
            }
            try {
                // 解密用户安装配置；敏感值只在内存中用于本次请求，不写回明文。
                Map<String, Object> config = secretCipher.decryptConfig(install.get().getUserConfig());
                // 通过 tools/list 获取远程 MCP 的真实工具清单。
                List<McpToolDescriptor> descriptors = loadTools(skill, config);
                // 把远程工具清单回填 skill_tool，保证商店详情和审计能看到一致的工具定义。
                upsertToolManifest(skill, descriptors);
                for (McpToolDescriptor descriptor : descriptors) {
                    // 每个远程 MCP tool 都包装成一个 Spring AI FunctionToolCallback。
                    ToolCallback callback = FunctionToolCallback
                            .builder(descriptor.qualifiedName(), (Map<String, Object> input, ToolContext context) ->
                                    // 模型传来的 input 就是 MCP tools/call 的 arguments；为空时按空对象处理。
                                    callTool(skill, config, descriptor.name(), input == null ? Map.of() : input))
                            .description(descriptor.description())
                            .inputSchema(descriptor.inputSchema())
                            // 函数式 builder 因泛型擦除无法推断入参类型，必须显式指定，否则 build() 抛 inputType cannot be null。
                            .inputType(Map.class)
                            .build();
                    // MCP 工具标记为 CAUTION：会访问外部网络和用户密钥，但通常不直接执行本地危险命令。
                    registrations.add(new ToolCallbackRegistration(
                            callback,
                            skill.getId(),
                            skill.getSkillKey(),
                            descriptor.qualifiedName(),
                            DangerLevel.CAUTION,
                            "MCP"
                    ));
                }
            } catch (Exception ex) {
                // 关键容错：单个 MCP 加载/鉴权失败（例如缺 API Key 的 401、网络超时、端点不可用）
                // 只跳过该技能并告警，绝不让它中断其它技能与正常聊天。
                log.warn("加载 MCP 技能工具失败，已跳过该技能（不影响本次聊天）：skillKey={}, endpoint={}, 原因={}",
                        skill.getSkillKey(), skill.getMcpEndpoint(), ex.getMessage());
            }
        }
        return registrations;
    }

    /**
     * 判断一个 Skill 是否是当前运行时支持的远程 HTTP MCP。
     */
    private boolean isRemoteHttpMcp(Skill skill) {
        // transport 是注册表/安装信息中的传输类型，例如 streamable-http、sse、stdio。
        String transport = skill.getMcpTransport();
        // endpoint 是真正要发起 HTTP JSON-RPC 请求的地址。
        String endpoint = skill.getMcpEndpoint();
        // 当前只接受 http/https endpoint，且 transport 必须为空或属于 HTTP 型 MCP。
        boolean transportOk = StringUtils.hasText(endpoint)
                && endpoint.regionMatches(true, 0, "http", 0, 4)
                && (!StringUtils.hasText(transport)
                || "streamable-http".equalsIgnoreCase(transport)
                || "sse".equalsIgnoreCase(transport)
                || "http".equalsIgnoreCase(transport));
        if (!transportOk) {
            // stdio 或缺少 endpoint 的 MCP 不能在这里执行。
            return false;
        }
        if (!isEndpointSafe(endpoint)) {
            // SSRF 防护：端点指向私网/环回/链路本地且未显式放开时，直接跳过该技能，不注册任何工具。
            return false;
        }
        return true;
    }

    /**
     * 校验 MCP 端点是否可以安全发起出站请求（SSRF 防护）。
     *
     * <p>仅允许 http/https，且在未显式放开私网的情况下拒绝指向环回、任意本地、链路本地
     * （含 169.254.169.254 云元数据）、私有网段和组播地址的端点。DNS 解析到的所有 IP
     * 都必须通过校验，避免通过解析到内网的域名绕过。</p>
     *
     * @param endpoint 配置的 MCP 端点
     * @return true 表示允许出站
     */
    public boolean isEndpointSafe(String endpoint) {
        if (allowPrivateEndpoints) {
            // 受控内网部署可以显式允许私网地址；默认生产配置不建议打开。
            return true;
        }
        try {
            // URI.create 负责基础格式解析；非法 URI 会进入 catch 并被拒绝。
            URI uri = URI.create(endpoint);
            // 只允许 HTTP/HTTPS，拒绝 file、gopher、ftp 等非预期协议。
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            // host 为空说明 URL 不能安全解析出目标主机。
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            // 对域名解析出的所有 IP 做检查，防止 DNS 指向内网地址绕过。
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress()
                        || address.isAnyLocalAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || isUniqueLocalIpv6(address)) {
                    return false;
                }
            }
            // 所有解析出的地址都不是本地/私网/组播地址，才允许出站。
            return true;
        } catch (Exception ex) {
            // 解析失败或域名无法解析时，保守拒绝。
            return false;
        }
    }

    /** 识别 IPv6 唯一本地地址 fc00::/7，Java 没有内置判断。 */
    private boolean isUniqueLocalIpv6(InetAddress address) {
        // IPv6 地址长度为 16 字节；fc00::/7 的前 7 位固定为 1111110。
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * 加载远程 MCP 工具清单。
     */
    private List<McpToolDescriptor> loadTools(Skill skill, Map<String, Object> config) {
        // 缓存 key 同时包含 skillId 和配置摘要，因为不同用户配置可能看到不同工具或权限。
        String cacheKey = skill.getId() + ":" + Integer.toHexString(String.valueOf(config).hashCode());
        CachedTools cached = cachedTools.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            // 未过期时直接返回缓存，减少远程 MCP 压力和聊天延迟。
            return cached.tools();
        }
        // MCP 标准方法：列出当前连接可用工具。
        JsonNode result = rpc(skill, config, "tools/list", Map.of());
        // 规范响应里工具数组通常在 result.tools。
        JsonNode toolsNode = result.path("tools");
        List<McpToolDescriptor> tools = new ArrayList<>();
        if (toolsNode.isArray()) {
            for (JsonNode tool : toolsNode) {
                // name 是 MCP tools/call 时必须传回去的远程工具名。
                String name = text(tool, "name", null);
                if (!StringUtils.hasText(name)) {
                    // 没有 name 的条目无法调用，跳过坏数据。
                    continue;
                }
                // 加 mcp + skillKey 前缀生成全局唯一工具名，避免不同 MCP 的工具重名。
                String qualifiedName = "mcp__" + sanitize(skill.getSkillKey()) + "__" + sanitize(name);
                // description 会展示给模型，影响模型是否选择这个工具。
                String description = text(tool, "description", "Remote MCP tool " + name);
                // MCP 生态里有 inputSchema 和 input_schema 两种写法，优先读取标准驼峰名。
                JsonNode schemaNode = tool.path("inputSchema");
                if (schemaNode.isMissingNode() || schemaNode.isNull()) {
                    schemaNode = tool.path("input_schema");
                }
                // 没有 schema 时使用“空对象参数”兜底，保证 Spring AI 能注册工具。
                String inputSchema = schemaNode.isMissingNode() || schemaNode.isNull()
                        ? "{\"type\":\"object\",\"properties\":{}}"
                        : schemaNode.toString();
                tools.add(new McpToolDescriptor(name, qualifiedName, description, inputSchema));
            }
        }
        // 写入短期缓存，后续同配置调用直接复用。
        cachedTools.put(cacheKey, new CachedTools(tools, Instant.now().plus(TOOL_CACHE_TTL)));
        return tools;
    }

    /**
     * 调用远程 MCP 的 tools/call。
     */
    private String callTool(Skill skill, Map<String, Object> config, String toolName, Map<String, Object> arguments) {
        // MCP tools/call 参数格式固定：name 是远程工具名，arguments 是模型生成的参数对象。
        JsonNode result = rpc(skill, config, "tools/call", Map.of(
                "name", toolName,
                "arguments", arguments
        ));
        // 返回给模型前做长度限制，避免远程工具结果过大。
        return truncateResult(result.toString());
    }

    /** 截断过大的工具结果，避免把巨型对象塞进模型上下文。 */
    private String truncateResult(String result) {
        if (result == null) {
            // null 结果按空字符串处理，避免上层收到 null。
            return "";
        }
        // 配置值太小会导致工具几乎不可用，因此至少保留 1000 字符。
        int limit = Math.max(1000, maxResultChars);
        return result.length() > limit ? result.substring(0, limit) + "...[truncated]" : result;
    }

    /**
     * 执行一次 MCP JSON-RPC 调用。
     */
    private JsonNode rpc(Skill skill, Map<String, Object> config, String method, Map<String, Object> params) {
        try {
            // LinkedHashMap 让序列化字段顺序稳定，便于抓包和日志排查。
            Map<String, Object> payload = new LinkedHashMap<>();
            // MCP 远程 HTTP 使用 JSON-RPC 2.0 结构。
            payload.put("jsonrpc", "2.0");
            // id 只需要在单次请求里唯一；这里用 nanoTime 避免引入全局计数器。
            payload.put("id", System.nanoTime());
            // method 可能是 tools/list 或 tools/call。
            payload.put("method", method);
            // params 为空时写空对象，符合 JSON-RPC 习惯。
            payload.put("params", params == null ? Map.of() : params);
            // 构造 POST 请求，endpoint 已在 isRemoteHttpMcp 中校验过。
            Request.Builder builder = new Request.Builder()
                    .url(skill.getMcpEndpoint())
                    .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            // 根据用户安装配置补 Authorization 或自定义 headers。
            applyAuthHeaders(builder, config);
            try (Response response = okHttpClient.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) {
                    // 失败时尽量带上响应体，方便定位鉴权失败、限流或 endpoint 错误。
                    String errorBody = response.body() == null ? "" : response.body().string();
                    throw new IllegalStateException("MCP endpoint returned " + response.code() + ": " + errorBody);
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IllegalStateException("MCP endpoint returned empty response body");
                }
                // 解析 JSON-RPC 响应。
                JsonNode root = objectMapper.readTree(body.string());
                if (root.hasNonNull("error")) {
                    // JSON-RPC 层面的错误也要作为调用失败抛出，不能把 error 当 result 回给模型。
                    throw new IllegalStateException("MCP JSON-RPC error: " + root.get("error"));
                }
                // provider 调用方只关心 result 节点。
                return root.path("result");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("MCP call failed for " + skill.getSkillKey() + " method " + method + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * 根据用户配置给 MCP 请求添加鉴权头。
     */
    private void applyAuthHeaders(Request.Builder builder, Map<String, Object> config) {
        // 如果用户直接给了完整 Authorization，就原样使用，例如 "Bearer xxx" 或 "Basic xxx"。
        Object authorization = firstNonNull(config, "authorization", "Authorization");
        // 常见配置只给 token/apiKey，此时统一按 Bearer token 发送。
        Object bearerToken = firstNonNull(config, "token", "accessToken", "apiKey", "api_key");
        if (authorization != null) {
            builder.header("Authorization", String.valueOf(authorization));
            return;
        }
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        // 高级用户可以传 headers map，用于 MCP 服务要求的自定义 header。
        Object headers = config.get("headers");
        if (headers instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    // key/value 都转字符串，避免配置中混入非 String 类型导致 OkHttp 报错。
                    builder.header(String.valueOf(key), String.valueOf(value));
                }
            });
        }
    }

    /**
     * 从配置中按多个候选 key 找第一个非空值。
     */
    private Object firstNonNull(Map<String, Object> config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                // 找到第一个有实际文本的值就返回，候选 key 顺序代表优先级。
                return value;
            }
        }
        return null;
    }

    /**
     * 将远程 MCP 工具清单回填到 skill_tool 表。
     */
    private void upsertToolManifest(Skill skill, List<McpToolDescriptor> descriptors) {
        if (descriptors.isEmpty()) {
            // 没有工具时不写库，避免清空历史清单导致商店详情闪烁。
            return;
        }
        for (int i = 0; i < descriptors.size(); i++) {
            McpToolDescriptor descriptor = descriptors.get(i);
            // qualifiedName 全局唯一，适合做幂等更新键。
            Optional<SkillTool> existing = skillToolRepository.findByQualifiedName(descriptor.qualifiedName());
            SkillTool row = existing.orElseGet(SkillTool::new);
            // skill_id 建立远程工具与 MCP 技能的归属关系。
            row.setSkillId(skill.getId());
            // toolName 保存远程 MCP 的原始工具名。
            row.setToolName(descriptor.name());
            // qualifiedName 是注入给模型和审计表使用的全局唯一工具名。
            row.setQualifiedName(descriptor.qualifiedName());
            // description/schema 都来自远程 tools/list。
            row.setDescription(descriptor.description());
            row.setParametersSchema(descriptor.inputSchema());
            // MCP 访问外部服务和用户密钥，因此至少标为 CAUTION。
            row.setDangerLevel(DangerLevel.CAUTION);
            // 按远程返回顺序保存排序，UI 展示稳定。
            row.setSortOrder(i);
            skillToolRepository.save(row);
        }
    }

    /**
     * 从 JSON 节点中读取字符串字段。
     */
    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        // 只有字段真实为字符串时才返回，否则使用 fallback。
        return value.isTextual() ? value.asText() : fallback;
    }

    /**
     * 把任意字符串归一化成可用于工具名的片段。
     */
    private String sanitize(String value) {
        // Spring AI / OpenAI 工具名更适合只包含字母、数字和下划线。
        return value == null ? "tool" : value.replaceAll("[^A-Za-z0-9_]+", "_");
    }

    /**
     * 远程 MCP 工具描述。
     *
     * @param name MCP 原始工具名，tools/call 时传给远程服务
     * @param qualifiedName 平台生成的全局唯一工具名，注入给模型
     * @param description 工具说明，影响模型是否调用
     * @param inputSchema 工具参数 JSON Schema
     */
    private record McpToolDescriptor(
            String name,
            String qualifiedName,
            String description,
            String inputSchema
    ) {
    }

    /**
     * tools/list 缓存值。
     *
     * @param tools 缓存的工具描述列表
     * @param expiresAt 过期时间，超过后重新请求远程 MCP
     */
    private record CachedTools(List<McpToolDescriptor> tools, Instant expiresAt) {
    }
}
