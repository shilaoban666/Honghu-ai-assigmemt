package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.mcp.marketplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.SkillSource;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpMarketplaceCategoryDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpMarketplaceItemDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpMarketplacePageDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpSkillInstallRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.UserSkillInstall;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.UserSkillInstallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 全网 MCP 商店服务。
 *
 * <p>这个服务是后端接入“全网 MCP 技能商店”的边界。前端详细商店页面只依赖这里的分页、
 * 分类、搜索、安装接口；以后把数据源从当前内存目录替换成 Smithery、PulseMCP 或自建爬虫后，
 * Controller 和前端页面都不需要跟着重写。</p>
 *
 * <p>当前实现有两个目的：</p>
 * <ul>
 *     <li>先生成 1000+ 条稳定目录数据，保证前端可以真实验证分页、懒加载和分类筛选性能；</li>
 *     <li>保留定时刷新入口，后续接真实爬虫时只替换 {@link #refreshMarketplaceCache()} 内部逻辑。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class McpMarketplaceService {

    /** 每页最大数量，防止前端误传过大 size 造成一次性返回过多数据。 */
    private static final int MAX_PAGE_SIZE = 80;

    /** 自动生成的全网 MCP 数量；加上热门 seed 后总量会超过 1000。 */
    private static final int GENERATED_MARKETPLACE_SIZE = 1120;

    /**
     * 分类定义统一放在后端，前端可以直接使用响应里的 categories 渲染左侧导航。
     *
     * <p>virtual=true 表示它不是 MCP 自身分类，而是一个快捷筛选入口，例如 Healthy、No Auth、Tools。</p>
     */
    private static final List<CategoryDefinition> CATEGORY_DEFINITIONS = List.of(
            new CategoryDefinition("all", "全部", "□", true),
            new CategoryDefinition("no-auth", "No Auth", "▣", true),
            new CategoryDefinition("healthy", "Healthy", "✓", true),
            new CategoryDefinition("tools", "Tools", "⌁", true),
            new CategoryDefinition("security", "安全", "盾", false),
            new CategoryDefinition("prompts", "提示词", "词", false),
            new CategoryDefinition("resources", "资源", "库", false),
            new CategoryDefinition("search", "搜索", "搜", false),
            new CategoryDefinition("developer", "开发工具", "</>", false),
            new CategoryDefinition("research", "研究与数据", "研", false),
            new CategoryDefinition("finance", "金融", "¥", false),
            new CategoryDefinition("automation", "自动化", "⚙", false),
            new CategoryDefinition("open-data", "开放数据", "数", false),
            new CategoryDefinition("government", "政务数据", "政", false),
            new CategoryDefinition("marketing", "营销", "M", false),
            new CategoryDefinition("agent", "Agent", "A", false),
            new CategoryDefinition("blockchain", "区块链", "链", false),
            new CategoryDefinition("ai-ml", "AI & ML", "AI", false),
            new CategoryDefinition("commerce", "电商零售", "店", false),
            new CategoryDefinition("oauth", "OAuth 2.0", "钥", false)
    );

    /** 用于生成目录数据的名称池，模拟真实全网 MCP 目录中的不同产品名。 */
    private static final List<String> GENERATED_NAMES = List.of(
            "PromptForge", "SecureFetch", "DataHarbor", "BrowserPilot", "MemoryBridge",
            "FinanceLens", "ResearchFlow", "CloudOps", "SchemaScout", "CrawlerHub",
            "VectorDock", "TicketSmith", "MailPilot", "NotionBridge", "SheetRunner",
            "DocuMind", "MetricWatch", "DeployMate", "OAuthVault", "AgentRouter"
    );

    /**
     * 当前阶段的 MCP 市场缓存。
     *
     * <p>这里使用静态不可变列表，避免每次搜索、分页、分类统计都重新生成 1000+ 条对象。
     * 真实爬虫接入后，可以把它替换成数据库查询、Redis 缓存或带版本号的内存快照。</p>
     */
    private static final List<McpMarketplaceItemDto> MARKETPLACE_ITEMS = buildMarketplaceSeed();

    /** 记录最近一次刷新时间，便于后续在健康检查或管理后台展示同步状态。 */
    private volatile Instant lastRefreshAt = Instant.now();

    private final SkillRepository skillRepository;
    private final UserSkillInstallRepository userSkillInstallRepository;
    private final ObjectMapper objectMapper;

    /**
     * 查询全网 MCP 商店分页列表。
     *
     * @param category 分类 key；all 表示全部
     * @param query 搜索关键词，可匹配名称、描述、endpoint、标签
     * @param healthyOnly 是否只看健康 MCP
     * @param noAuthOnly 是否只看 No Auth MCP
     * @param sort 排序字段：popular、rating、tools、recent
     * @param page 页码，从 0 开始
     * @param size 每页数量
     * @return 分页列表和分类统计
     */
    @Transactional(readOnly = true)
    public McpMarketplacePageDto search(String category,
                                        String query,
                                        boolean healthyOnly,
                                        boolean noAuthOnly,
                                        String sort,
                                        int page,
                                        int size) {
        // page/size 做边界保护，避免负数或过大分页参数进入后续计算。
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String safeCategory = StringUtils.hasText(category) ? category : "all";
        String safeQuery = StringUtils.hasText(query) ? query.toLowerCase(Locale.ROOT).trim() : "";

        // 先在内存快照上做筛选；真实生产环境如果目录更大，可以平移到 Repository 分页查询。
        List<McpMarketplaceItemDto> filtered = MARKETPLACE_ITEMS.stream()
                .filter(item -> matchCategory(item, safeCategory))
                .filter(item -> !healthyOnly || "healthy".equals(item.health()))
                .filter(item -> !noAuthOnly || "No Auth".equals(item.auth()))
                .filter(item -> matchQuery(item, safeQuery))
                .sorted(comparator(sort))
                .toList();

        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());

        return McpMarketplacePageDto.builder()
                .items(filtered.subList(from, to))
                .page(safePage)
                .size(safeSize)
                .total(filtered.size())
                .hasMore(to < filtered.size())
                .categories(categories())
                .build();
    }

    /**
     * 安装 MCP 技能。
     *
     * <p>安装会确保 skill 表里存在对应 MCP 技能，然后在 user_skill_install 中写入用户安装关系。
     * 目前不会在安装接口里立刻建立长连接；后续 McpSkillProvider 会读取这些安装关系并按用户创建连接池。</p>
     *
     * @param request 安装请求，至少需要 mcpId
     * @return 安装后的 MCP 技能条目
     */
    @Transactional
    public McpMarketplaceItemDto install(McpSkillInstallRequest request) {
        if (request == null || !StringUtils.hasText(request.mcpId())) {
            throw new IllegalArgumentException("mcpId must not be blank");
        }

        McpMarketplaceItemDto item = MARKETPLACE_ITEMS.stream()
                .filter(candidate -> candidate.id().equals(request.mcpId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown MCP marketplace item: " + request.mcpId()));

        // skill 表是技能容器表：安装 MCP 时先把远程 MCP 标准化成系统内部 Skill。
        Skill skill = skillRepository.findBySkillKey(item.id()).orElseGet(Skill::new);
        skill.setSkillKey(item.id());
        skill.setSource(SkillSource.MCP);
        skill.setDisplayName(item.name());
        skill.setDescription(item.description());
        skill.setCategory(item.categoryLabel());
        skill.setDefaultEnabled(false);
        skill.setMandatory(false);
        skill.setRequiredRole(com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User.UserRole.USER);
        skill.setRating(BigDecimal.valueOf(item.rating()));
        skill.setDownloads((int) Math.min(Integer.MAX_VALUE, item.downloads()));
        skill.setMcpTransport("streamable-http");
        skill.setMcpEndpoint(item.endpoint());
        skill.setEnabled(true);
        skill = skillRepository.save(skill);

        // userId 为空时允许只做 skill upsert；这样管理员同步市场时也能复用安装逻辑。
        if (StringUtils.hasText(request.userId())) {
            Optional<UserSkillInstall> existing = userSkillInstallRepository.findByUserIdAndSkillId(request.userId(), skill.getId());
            UserSkillInstall install = existing.orElseGet(UserSkillInstall::new);
            install.setUserId(request.userId());
            install.setSkillId(skill.getId());
            install.setEnabled(true);
            install.setUserConfig(toJson(request.config()));
            userSkillInstallRepository.save(install);
        }

        return item;
    }

    /**
     * 定时刷新 MCP 市场缓存。
     *
     * <p>当前阶段只更新时间戳并保留详细注释，因为本地生成目录已经能支撑前端开发和性能验证。
     * 接真实爬虫时，应把这里替换为：</p>
     * <ol>
     *     <li>请求 Smithery、PulseMCP 或内部 registry；</li>
     *     <li>归一化分类、鉴权方式、endpoint、工具数量、资源数量和提示词数量；</li>
     *     <li>对 endpoint 做健康检查和安全扫描，剔除不可用或高风险 MCP；</li>
     *     <li>把结果写入数据库或 Redis，然后用新快照替换内存缓存。</li>
     * </ol>
     */
    @Scheduled(cron = "${app.mcp.marketplace-sync-cron:0 17 */6 * * *}")
    public void refreshMarketplaceCache() {
        // 这里不能执行用户可控命令，也不要启动 stdio MCP；市场同步只允许走受控 HTTP registry。
        lastRefreshAt = Instant.now();
    }

    /**
     * 判断 MCP 条目是否命中当前分类。
     *
     * <p>这里同时支持两类分类：</p>
     * <ul>
     *     <li>真实分类：例如 security、developer、research，对应 MCP 条目自身的 category 字段；</li>
     *     <li>快捷筛选：例如 no-auth、healthy、tools、resources、prompts，它们不是数据源分类，而是页面左侧的快速入口。</li>
     * </ul>
     *
     * @param item 待判断的 MCP 条目
     * @param category 前端传入的分类 key
     * @return true 表示条目应该出现在当前分类列表中
     */
    private static boolean matchCategory(McpMarketplaceItemDto item, String category) {
        if ("all".equals(category)) return true;
        if ("no-auth".equals(category)) return "No Auth".equals(item.auth());
        if ("healthy".equals(category)) return "healthy".equals(item.health());
        if ("tools".equals(category)) return item.tools() > 0;
        if ("resources".equals(category)) return item.resources() > 0;
        if ("prompts".equals(category)) return item.prompts() > 0;
        return category.equals(item.category());
    }

    /**
     * 判断 MCP 条目是否命中搜索关键词。
     *
     * <p>搜索范围覆盖名称、描述、endpoint、分类名、鉴权方式和标签。
     * 这样用户既可以搜索 “GitHub”，也可以搜索 “No Auth”“search”“https://...” 等更技术化的信息。</p>
     *
     * @param item 待搜索的 MCP 条目
     * @param query 已经转成小写并 trim 后的搜索词
     * @return true 表示当前条目匹配搜索条件
     */
    private static boolean matchQuery(McpMarketplaceItemDto item, String query) {
        if (!StringUtils.hasText(query)) return true;
        return Stream.of(
                        item.name(),
                        item.description(),
                        item.endpoint(),
                        item.categoryLabel(),
                        item.auth(),
                        String.join(" ", safeList(item.tags())))
                .filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(query));
    }

    /**
     * 根据前端排序字段生成排序器。
     *
     * <p>默认 popular 使用 downloads 降序，符合“热门可用 MCP”的入口语义；
     * rating、tools、recent 分别服务于质量优先、工具数优先和最新同步优先三种筛选场景。</p>
     *
     * @param sort 排序字段
     * @return 可直接传给 Stream.sorted 的 Comparator
     */
    private static Comparator<McpMarketplaceItemDto> comparator(String sort) {
        if ("rating".equals(sort)) return Comparator.comparingDouble(McpMarketplaceItemDto::rating).reversed();
        if ("tools".equals(sort)) return Comparator.comparingInt(McpMarketplaceItemDto::tools).reversed();
        if ("recent".equals(sort)) return Comparator.comparingInt(item -> updatedWeight(item.updated()));
        return Comparator.comparingLong(McpMarketplaceItemDto::downloads).reversed();
    }

    /**
     * 把中文更新时间文案转成可排序权重。
     *
     * <p>当前 mock/缓存数据没有真实时间戳，所以用“今天、昨天、本周、3 天前”这类文案转权重。
     * 接真实爬虫后建议改为 Instant 字段，并保留这个方法作为展示文案的兼容兜底。</p>
     *
     * @param updated 更新时间文案
     * @return 数字越小表示越新
     */
    private static int updatedWeight(String updated) {
        if (updated == null) return 9;
        if (updated.contains("今天")) return 0;
        if (updated.contains("昨天")) return 1;
        if (updated.contains("本周")) return 2;
        if (updated.contains("3 天")) return 3;
        return 8;
    }

    /**
     * 生成左侧分类导航及数量。
     *
     * <p>每次查询都返回分类统计，是为了让前端在后端模式和本地兜底模式下使用同一套渲染逻辑。
     * 当前 1000+ 数据量很小，直接流式统计足够；真实生产目录更大时可以把统计结果缓存到 Redis 或数据库聚合表。</p>
     *
     * @return 分类导航 DTO 列表
     */
    private static List<McpMarketplaceCategoryDto> categories() {
        return CATEGORY_DEFINITIONS.stream()
                .map(definition -> McpMarketplaceCategoryDto.builder()
                        .key(definition.key())
                        .label(definition.label())
                        .icon(definition.icon())
                        .count(countByCategory(definition.key()))
                        .build())
                .toList();
    }

    /**
     * 统计某个分类下的 MCP 数量。
     *
     * @param key 分类 key
     * @return 当前缓存快照中命中该分类的条目数量
     */
    private static long countByCategory(String key) {
        return MARKETPLACE_ITEMS.stream().filter(item -> matchCategory(item, key)).count();
    }

    /**
     * 清理可能为 null 或包含空字符串的标签集合。
     *
     * @param values 原始标签集合
     * @return 非 null、非空字符串的标签列表
     */
    private static List<String> safeList(Collection<String> values) {
        return values == null ? List.of() : values.stream().filter(StringUtils::hasText).toList();
    }

    /**
     * 把用户安装配置序列化为 JSON 字符串。
     *
     * <p>user_skill_install.user_config 当前用字符串/JSON 保存用户级配置。
     * 真实生产环境里，API Key、Token 等敏感字段应在进入这里之前先加密或拆到密钥管理服务。</p>
     *
     * @param config 用户安装 MCP 时提交的配置
     * @return JSON 字符串；config 为 null 时返回空对象
     */
    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid MCP install config", e);
        }
    }

    /**
     * 构建当前阶段使用的全网 MCP 种子目录。
     *
     * <p>前几条是更像真实热门 MCP 的人工 seed；后续 GENERATED_MARKETPLACE_SIZE 条是按分类轮转生成的虚拟数据。
     * 这样前端可以在没有真实爬虫的情况下验证：</p>
     * <ul>
     *     <li>左侧分类筛选是否正确；</li>
     *     <li>列表懒加载是否只渲染当前页；</li>
     *     <li>搜索和排序在 1000+ 条数据下是否仍然顺滑；</li>
     *     <li>安装状态写回前端胶囊栏和设置页时是否有空值问题。</li>
     * </ul>
     *
     * @return 不可变 MCP 目录快照
     */
    private static List<McpMarketplaceItemDto> buildMarketplaceSeed() {
        List<McpMarketplaceItemDto> items = new ArrayList<>();
        items.add(item("mcp:adis", "adis", "A", "government", "政务数据", "https://adis.cz-agents.dev/mcp", "ADIS Czech VAT-payer reliability via MFCR SOAP，适合查询捷克税务与企业可靠性数据。", "No Auth", "healthy", 3, 2, 1, 126000, 4.8, List.of("tax", "government", "soap"), "今天同步", true));
        items.add(item("mcp:acrelens", "AcreLens", "A", "research", "研究与数据", "https://mcp.acrelens.com/mcp", "US land due-diligence MCP server，返回太阳能、洪水区、建筑法规等结构化报告。", "API Key", "healthy", 5, 1, 1, 98000, 4.9, List.of("land", "report"), "昨天更新", true));
        items.add(item("mcp:actiongate", "ActionGate", "A", "security", "安全", "https://api.actiongate.xyz/mcp", "Pre-execution safety layer for autonomous agent wallets via MCP and x402。", "OAuth 2.0", "healthy", 6, 0, 1, 154000, 4.8, List.of("wallet", "guard"), "今天同步", true));
        items.add(item("mcp:academic-research", "academic-research-mcp-server", "A", "research", "研究与数据", "https://nexgendata-mcp-proxy.steve-corbeil.workers.dev", "ArXiv preprints + Google Scholar papers，单次查询返回论文、引用数和摘要。", "No Auth", "healthy", 2, 2, 0, 211000, 4.7, List.of("arxiv", "paper"), "本周更新", true));
        items.add(item("mcp:acled", "Acled", "A", "open-data", "开放数据", "https://gateway.pipeworx.io/acled/mcp", "ACLED MCP，查询武装冲突、事件位置与公共开放数据项目。", "API Key", "healthy", 18, 1, 0, 87000, 4.6, List.of("conflict", "open-data", "events"), "3 天前", true));
        items.add(item("mcp:4bots", "4bots", "A", "automation", "自动化", "https://4bots.net/mcp", "Drop-in daily content for AI briefing agents，提供频道、简报和免费调用额度。", "No Auth", "healthy", 9, 1, 1, 143000, 4.8, List.of("briefing", "content", "daily"), "昨天更新", true));
        items.add(item("mcp:1stay", "1stay", "A", "commerce", "电商零售", "https://mcp.1stay.com/mcp", "Hotel booking MCP server，可搜索、预订并管理全球住宿预订。", "API Key", "healthy", 7, 3, 1, 73000, 4.5, List.of("hotel", "booking", "travel"), "本周更新", true));
        items.add(item("mcp:a2ax", "a2ax", "B", "agent", "Agent", "https://openjuno.example.com/mcp", "OpenJuno social network for AI agents，支持发布、关注、搜索和 Agent 互动。", "OAuth 2.0", "unhealthy", 1, 1, 1, 46000, 4.1, List.of("agent", "social", "network"), "待复测", false));
        items.add(item("mcp:github", "GitHub MCP", "A", "developer", "开发工具", "https://api.githubcopilot.com/mcp", "GitHub 仓库、Issue、Pull Request、代码搜索和文件读取 MCP 工具集。", "OAuth 2.0", "healthy", 32, 4, 2, 1200000, 4.9, List.of("github", "repo", "issue"), "今天同步", true));
        items.add(item("mcp:tavily", "Tavily Search", "A", "search", "搜索", "https://api.tavily.com/mcp", "面向 Agent 的实时网页搜索、网页提取、站点抓取和网页地图 MCP。", "API Key", "healthy", 4, 0, 1, 319000, 4.8, List.of("search", "extract", "crawl"), "昨天更新", true));

        List<CategoryDefinition> realCategories = CATEGORY_DEFINITIONS.stream()
                .filter(definition -> !definition.virtual())
                .toList();

        for (int index = 0; index < GENERATED_MARKETPLACE_SIZE; index++) {
            CategoryDefinition category = realCategories.get(index % realCategories.size());
            String baseName = GENERATED_NAMES.get(index % GENERATED_NAMES.size());
            String name = baseName + " " + (index + 1);
            String safeName = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            boolean healthy = index % 9 != 0;
            boolean noAuth = index % 4 == 0;
            String auth = noAuth ? "No Auth" : (index % 5 == 0 ? "OAuth 2.0" : "API Key");
            int tools = 1 + (index % 18);
            int resources = "resources".equals(category.key()) ? 2 + (index % 5) : index % 5;
            int prompts = "prompts".equals(category.key()) ? 1 + (index % 4) : index % 4;
            double rating = 4.1 + (index % 9) * 0.1;
            String updated = index % 3 == 0 ? "今天同步" : index % 3 == 1 ? "昨天更新" : "本周更新";

            items.add(item(
                    "mcp:market-" + (index + 1),
                    name,
                    index % 7 == 0 ? "B" : "A",
                    category.key(),
                    category.label(),
                    "https://" + safeName + ".mcp.tools/mcp",
                    category.label() + " MCP Server，提供可被 Agent 直接调用的工具、资源和提示词模板，已通过目录健康检查。",
                    auth,
                    healthy ? "healthy" : "unhealthy",
                    tools,
                    resources,
                    prompts,
                    1200L + index * 97L,
                    Math.round(rating * 10.0) / 10.0,
                    List.of(category.key(), healthy ? "healthy" : "needs-review", noAuth ? "no-auth" : "auth"),
                    updated,
                    healthy
            ));
        }

        return List.copyOf(items);
    }

    /**
     * 创建一个 MCP 商店条目。
     *
     * <p>这个小工厂方法把 builder 的长链式调用集中起来，避免 seed 数据区域重复写大量字段名。
     * 它不做业务校验，业务边界校验由 install/search 等公开方法完成。</p>
     */
    private static McpMarketplaceItemDto item(String id, String name, String grade, String category, String categoryLabel,
                                             String endpoint, String description, String auth, String health, int tools,
                                             int resources, int prompts, long downloads, double rating, List<String> tags,
                                             String updated, boolean verified) {
        return McpMarketplaceItemDto.builder()
                .id(id)
                .name(name)
                .grade(grade)
                .category(category)
                .categoryLabel(categoryLabel)
                .endpoint(endpoint)
                .description(description)
                .auth(auth)
                .health(health)
                .tools(tools)
                .resources(resources)
                .prompts(prompts)
                .downloads(downloads)
                .rating(rating)
                .tags(tags)
                .updated(updated)
                .verified(verified)
                .build();
    }

    /**
     * MCP 市场分类定义。
     *
     * @param key 稳定分类 key，前端筛选和后端过滤都使用它
     * @param label 展示给用户的分类名称
     * @param icon 左侧导航中显示的短图标文本
     * @param virtual 是否是快捷筛选项；true 表示它不是 MCP 原始分类
     */
    private record CategoryDefinition(String key, String label, String icon, boolean virtual) {
    }
}
