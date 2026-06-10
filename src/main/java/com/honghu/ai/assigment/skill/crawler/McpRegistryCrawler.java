package com.honghu.ai.assigment.skill.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.skill.builtin.mcp.McpSkillProvider;
import com.honghu.ai.assigment.skill.core.SkillSource;
import com.honghu.ai.assigment.skill.entity.Skill;
import com.honghu.ai.assigment.skill.repository.SkillRepository;
import com.honghu.ai.assigment.skill.translation.SkillTranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 官方 MCP Registry 真实爬虫。
 *
 * <p>从 <a href="https://registry.modelcontextprotocol.io">官方 MCP Registry</a> 拉取真实、
 * 持续更新的 MCP Server 目录，并归一化写入 {@code skill} 表（source=MCP）。这样统一能力市场
 * {@code CapabilityService.marketplace} 和 MCP 详细商店都能展示真实可用的数据，而不再依赖
 * 手写的占位/模拟目录。</p>
 *
 * <p>选择官方 Registry 作为数据源的原因：</p>
 * <ul>
 *     <li>权威、免鉴权、稳定的 HTTP JSON API，游标分页；</li>
 *     <li>返回结构化 {@code remotes}（可直接被 {@link McpSkillProvider} 调用的 streamable-http/sse 端点）；</li>
 *     <li>只取 {@code isLatest && active} 版本，保证落库的是当前有效条目。</li>
 * </ul>
 *
 * <p>爬虫只做受控 HTTP 读取与数据库 upsert，绝不执行任何 MCP 安装命令、不启动本地 stdio 进程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpRegistryCrawler {

    /** 每页拉取数量，官方 Registry 上限通常为 100。 */
    private static final int PAGE_SIZE = 100;

    /** 解析官方 Registry JSON 响应。 */
    private final ObjectMapper objectMapper;

    /** MCP 目录最终落到 skill 表，因此通过 SkillRepository 做幂等写入。 */
    private final SkillRepository skillRepository;

    /** 爬取完成后负责把英文描述增量翻译成中文。 */
    private final SkillTranslationService translationService;

    /** 复用 AI 网关 OkHttpClient，获得统一网络配置和连接池。 */
    @Qualifier("aiGatewayOkHttpClient")
    private final OkHttpClient httpClient;

    /**
     * 统一爬虫线程池：让 4 个爬虫启动同步串行、有界、可观测，并随上下文优雅关闭。
     *
     * <p>字段名刻意与 Bean 名 {@code skillCrawlerExecutor} 一致：容器内有多个 Executor
     * （memorySummary/taskScheduler/本池），Spring 在类型歧义时按注入点名称回退匹配 Bean，
     * 这样不依赖 Lombok 拷贝 {@code @Qualifier} 也能精确注入到本池。</p>
     */
    private final java.util.concurrent.Executor skillCrawlerExecutor;

    /** 是否启用 MCP Registry 爬虫。 */
    @Value("${app.skill.crawler.mcp.enabled:true}")
    private boolean enabled;

    /** 官方 Registry 根地址，可通过配置切到自建/镜像 registry。 */
    @Value("${app.skill.crawler.mcp.base-url:https://registry.modelcontextprotocol.io}")
    private String baseUrl;

    /** 单次同步最多落库多少个 MCP，避免一次拉取全网导致目录过大。 */
    @Value("${app.skill.crawler.mcp.max-servers:400}")
    private int maxServers;

    /** 防止启动同步与定时同步并发执行。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 应用启动完成后异步触发一次同步。
     *
     * <p>放在独立线程里执行，避免阻塞应用就绪；首次启动如果 registry 不可达也不影响主流程。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("MCP Registry 爬虫已禁用（app.skill.crawler.mcp.enabled=false），跳过启动同步");
            return;
        }
        // 启动同步提交到统一的有界爬虫线程池（单线程串行），避免 4 个爬虫在启动一刻
        // 并发抢占数据库连接池/CPU；registry 慢或不可达也只占用这条后台流水线，不拖慢 ready。
        skillCrawlerExecutor.execute(this::crawlSafely);
    }

    /**
     * 定时同步官方 MCP Registry。
     *
     * <p>默认每天凌晨 3:30 执行一次；可通过 {@code app.skill.crawler.mcp.cron} 调整。</p>
     */
    @Scheduled(cron = "${app.skill.crawler.mcp.cron:0 30 3 * * *}")
    public void scheduledCrawl() {
        if (!enabled) {
            // 配置关闭时，定时任务仍会触发方法，但不做任何网络请求。
            return;
        }
        crawlSafely();
    }

    /**
     * 带并发保护与异常兜底的同步入口。
     */
    public void crawlSafely() {
        if (!running.compareAndSet(false, true)) {
            // compareAndSet 保证启动同步和定时同步不会并发写同一批 skill。
            log.info("MCP Registry 同步已在进行中，本次触发跳过");
            return;
        }
        try {
            // crawl 只负责抓取和写库，返回本次成功 upsert 的条目数。
            int saved = crawl();
            log.info("MCP Registry 同步完成，本次落库/更新 {} 个 MCP 技能", saved);
            // 同步后把缺中文翻译的描述补齐（增量、尽力而为）。
            translationService.translatePending(SkillSource.MCP);
        } catch (Exception ex) {
            // 爬虫属于维护任务，失败不能影响主应用聊天/登录等核心链路。
            log.warn("MCP Registry 同步失败：{}", ex.getMessage(), ex);
        } finally {
            // 无论成功失败都释放运行锁，否则后续定时任务会永久跳过。
            running.set(false);
        }
    }

    /**
     * 游标分页拉取并 upsert 到 skill 表。
     *
     * @return 实际落库/更新的数量
     */
    public int crawl() throws Exception {
        // 用 LinkedHashMap 保持抓取顺序，同时按 skillKey 去重。
        Map<String, Skill> bySkillKey = new LinkedHashMap<>();
        // 官方 Registry 使用游标分页，第一次请求没有 cursor。
        String cursor = null;
        // pages 是额外保险，防止服务端错误导致 cursor 无限循环。
        int pages = 0;
        while (bySkillKey.size() < maxServers && pages < 200) {
            // 拉取一页 Registry 响应。
            JsonNode root = fetchPage(cursor);
            // 真实条目数组在 servers 字段下。
            JsonNode servers = root.path("servers");
            if (!servers.isArray() || servers.isEmpty()) {
                // 没有 servers 表示已经没有可处理数据，结束分页。
                break;
            }
            for (JsonNode entry : servers) {
                if (bySkillKey.size() >= maxServers) {
                    // 达到本次同步上限就停止处理后续条目。
                    break;
                }
                if (!isLatestActive(entry)) {
                    // 只同步 latest + active，跳过历史版本和下线条目。
                    continue;
                }
                // 把 Registry 的 server 节点映射成本项目内部的 Skill 实体草稿。
                Skill mapped = toSkill(entry.path("server"));
                if (mapped != null) {
                    // 同一个 skillKey 多次出现时保留最后一次映射，避免重复落库。
                    bySkillKey.put(mapped.getSkillKey(), mapped);
                }
            }
            // 读取下一页游标；没有游标说明分页结束。
            cursor = root.path("metadata").path("nextCursor").asText(null);
            pages++;
            if (!StringUtils.hasText(cursor)) {
                break;
            }
        }

        // 逐条 upsert，单个条目失败不会影响前面已经处理的条目。
        int saved = 0;
        for (Skill candidate : bySkillKey.values()) {
            if (upsert(candidate)) {
                saved++;
            }
        }
        return saved;
    }

    /**
     * 拉取官方 Registry 的一页 server 列表。
     */
    private JsonNode fetchPage(String cursor) throws Exception {
        // 用 OkHttp 的 HttpUrl 拼 URL，避免手写字符串时漏转义 query 参数。
        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base == null) {
            throw new IllegalStateException("Invalid MCP registry base-url: " + baseUrl);
        }
        // 官方 API 路径为 /v0/servers，并通过 limit/cursor 做分页。
        HttpUrl.Builder urlBuilder = base.newBuilder()
                .addPathSegment("v0")
                .addPathSegment("servers")
                .addQueryParameter("limit", String.valueOf(PAGE_SIZE));
        if (StringUtils.hasText(cursor)) {
            // 第二页开始带上上一页返回的 nextCursor。
            urlBuilder.addQueryParameter("cursor", cursor);
        }
        // 只做 GET JSON 读取，不执行任何远程安装命令。
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // 非 2xx 直接失败，让 crawlSafely 记录 warning 并等待下次同步。
                throw new IllegalStateException("MCP registry returned " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("MCP registry returned empty body");
            }
            // 响应体只读取一次，立刻交给 Jackson 解析成树。
            return objectMapper.readTree(body.string());
        }
    }

    /**
     * 判断 Registry 条目是否是最新且仍处于 active 状态。
     */
    private boolean isLatestActive(JsonNode entry) {
        // 官方元数据在 _meta.io.modelcontextprotocol.registry/official 下。
        JsonNode official = entry.path("_meta").path("io.modelcontextprotocol.registry/official");
        // isLatest=false 代表历史版本，不应该覆盖当前目录。
        boolean isLatest = official.path("isLatest").asBoolean(false);
        // status 缺失时按 active 处理，兼容旧响应。
        String status = official.path("status").asText("active");
        return isLatest && "active".equalsIgnoreCase(status);
    }

    /**
     * 把一个 registry server 节点映射成内部 Skill。
     *
     * @return 映射后的 Skill；缺少必要字段时返回 null
     */
    private Skill toSkill(JsonNode server) {
        // name 是官方 Registry 的稳定包名/服务名，也是生成 skillKey 的基础。
        String name = server.path("name").asText(null);
        if (!StringUtils.hasText(name)) {
            // 没有 name 就无法形成稳定 skillKey，跳过。
            return null;
        }
        // skill_key 统一加 mcp: 前缀，避免和内置能力、Claude Skill、CLI 能力重名。
        String skillKey = truncate("mcp:" + name, 100);
        // title 更适合前端展示；没有 title 时后面回退 name。
        String title = textOrNull(server, "title");
        // description 保存英文原文，中文翻译另存 description_zh。
        String description = textOrNull(server, "description");
        // 数据库 version 字段长度有限，先截断。
        String version = truncate(textOrNull(server, "version"), 20);

        // 优先选择远程 HTTP endpoint；没有时根据 packages 推断 stdio 类型，但 endpoint 保持 null。
        RemoteEndpoint remote = pickRemote(server.path("remotes"));
        String transport = remote != null ? truncate(remote.type(), 20) : transportFromPackages(server.path("packages"));
        String endpoint = remote != null ? truncate(remote.url(), 500) : null;

        // repository.url 用于商店详情跳转源仓库。
        String repoUrl = truncate(server.path("repository").path("url").asText(null), 500);
        // author 粗略取 name 前缀，例如 owner/package -> owner。
        String author = truncate(authorOf(name), 100);
        // Registry 没有完全统一的分类字段，这里用名称/描述做粗粒度分类。
        String category = inferCategory(name, description);

        // 构造未持久化的 Skill 草稿，后续 upsert 决定新建还是更新。
        Skill skill = new Skill();
        skill.setSkillKey(skillKey);
        skill.setSource(SkillSource.MCP);
        skill.setDisplayName(truncate(StringUtils.hasText(title) ? title : name, 200));
        skill.setDescription(description);
        skill.setCategory(category);
        skill.setVersion(version);
        skill.setAuthor(author);
        skill.setRepoUrl(repoUrl);
        skill.setMcpTransport(transport);
        skill.setMcpEndpoint(endpoint);
        // 全网 MCP 默认普通用户可见；更严格的角色控制可由管理员后续修改。
        skill.setRequiredRole(User.UserRole.USER);
        return skill;
    }

    /**
     * upsert：按 skillKey 幂等写入。
     *
     * <p>已存在的行只刷新展示/连接信息，保留管理员可能调整过的 enabled/mandatory/defaultEnabled/role；
     * 新行默认 enabled=true、非默认启用、非必装。</p>
     *
     * @return 是否写库
     */
    private boolean upsert(Skill candidate) {
        try {
            // skillKey 是幂等键：同一个 MCP 重复同步只更新一行。
            Skill row = skillRepository.findBySkillKey(candidate.getSkillKey()).orElse(null);
            boolean isNew = row == null;
            if (isNew) {
                // 新条目直接使用 candidate，并补上默认开关状态。
                row = candidate;
                // enabled=true 表示出现在市场；不是“默认注入聊天”。
                row.setEnabled(true);
                // 新 MCP 不默认注入会话，用户必须安装/启用。
                row.setDefaultEnabled(false);
                // MCP 不是系统必装能力。
                row.setMandatory(false);
            } else {
                // 已存在时只刷新来自上游的展示和连接字段。
                row.setDisplayName(candidate.getDisplayName());
                // 英文原文变化时清空旧的中文翻译，触发下一轮重译，保证翻译跟随上游更新。
                if (!java.util.Objects.equals(row.getDescription(), candidate.getDescription())) {
                    row.setDescriptionZh(null);
                }
                row.setDescription(candidate.getDescription());
                row.setCategory(candidate.getCategory());
                row.setVersion(candidate.getVersion());
                row.setAuthor(candidate.getAuthor());
                row.setRepoUrl(candidate.getRepoUrl());
                row.setMcpTransport(candidate.getMcpTransport());
                row.setMcpEndpoint(candidate.getMcpEndpoint());
                if (row.getRequiredRole() == null) {
                    // 老数据如果缺 requiredRole，补成 USER，避免角色过滤时被漏掉。
                    row.setRequiredRole(User.UserRole.USER);
                }
            }
            // 保存新建或更新后的行。
            skillRepository.save(row);
            return true;
        } catch (Exception ex) {
            // 单条数据损坏时跳过该条，不能让整个爬虫批次失败。
            log.debug("跳过一个 MCP 条目 upsert：key={}, error={}", candidate.getSkillKey(), ex.getMessage());
            return false;
        }
    }

    /**
     * 从 remotes 数组里选择一个当前运行时支持的远程 HTTP MCP endpoint。
     */
    private RemoteEndpoint pickRemote(JsonNode remotes) {
        if (!remotes.isArray()) {
            // 没有 remotes 时无法直接远程调用。
            return null;
        }
        for (JsonNode remote : remotes) {
            // type 可能是 streamable-http、sse、http，也可能为空。
            String type = remote.path("type").asText("");
            // url 必须是真正的 http/https 地址，后续 provider 还会做 SSRF 检查。
            String url = remote.path("url").asText("");
            boolean httpUrl = url.regionMatches(true, 0, "http", 0, 4);
            boolean httpTransport = type.isEmpty()
                    || type.equalsIgnoreCase("streamable-http")
                    || type.equalsIgnoreCase("streamable_http")
                    || type.equalsIgnoreCase("sse")
                    || type.equalsIgnoreCase("http");
            if (httpUrl && httpTransport) {
                // type 为空时按 streamable-http 处理，这是 MCP 远程 HTTP 的主流形态。
                return new RemoteEndpoint(StringUtils.hasText(type) ? type : "streamable-http", url);
            }
        }
        return null;
    }

    /**
     * 当 Registry 只提供 packages 而没有 remote endpoint 时，推断本地 stdio 类型。
     */
    private String transportFromPackages(JsonNode packages) {
        if (packages.isArray() && !packages.isEmpty()) {
            // registryType 可能是 npm、pypi、docker 等，本项目暂不执行，只作为展示元数据。
            String registryType = packages.get(0).path("registryType").asText(null);
            return StringUtils.hasText(registryType) ? truncate("stdio:" + registryType, 20) : "stdio";
        }
        return "stdio";
    }

    /**
     * 根据 Registry name 粗略推断作者。
     */
    private String authorOf(String name) {
        // 常见 name 形态是 owner/server-name，取 slash 前作为作者/组织。
        int slash = name.indexOf('/');
        return slash > 0 ? name.substring(0, slash) : name;
    }

    /**
     * 基于名称/描述做粗粒度分类，便于商店左侧导航筛选。
     */
    private String inferCategory(String name, String description) {
        // 把 name 和 description 合并成小写文本，下面用关键词做简单分类。
        String haystack = (nullToEmpty(name) + " " + nullToEmpty(description)).toLowerCase(Locale.ROOT);
        if (containsAny(haystack, "search", "browse", "crawl", "web ")) return "信息检索";
        if (containsAny(haystack, "github", "git", "code", "deploy", "ci/cd", "developer", "docs")) return "开发";
        if (containsAny(haystack, "database", "sql", "postgres", "mysql", "data", "analytics", "warehouse")) return "数据";
        if (containsAny(haystack, "finance", "trading", "payment", "invoice", "bank", "crypto", "wallet")) return "金融";
        if (containsAny(haystack, "design", "image", "video", "audio", "art", "3d")) return "创意";
        if (containsAny(haystack, "agent", "automation", "workflow", "task")) return "自动化";
        if (containsAny(haystack, "security", "auth", "guard", "safety")) return "安全";
        return "通用";
    }

    /**
     * 判断文本中是否包含任意关键词。
     */
    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取 JsonNode 中非空字符串字段。
     */
    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        // 非字符串或空字符串都按 null 处理，避免保存 "null" 或空白。
        return value.isTextual() && StringUtils.hasText(value.asText()) ? value.asText() : null;
    }

    /**
     * 截断字符串到数据库字段长度。
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        // 超长上游字段直接截断，避免数据库长度约束报错导致整条同步失败。
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * null 转空字符串，方便关键词拼接。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 选中的远程 MCP endpoint。
     *
     * @param type MCP transport 类型
     * @param url 远程 HTTP endpoint
     */
    private record RemoteEndpoint(String type, String url) {
    }
}
