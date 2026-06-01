package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.SkillSource;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.crawler.McpRegistryCrawler;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpMarketplaceCategoryDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpMarketplaceItemDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpMarketplacePageDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.request.McpSkillInstallRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.UserSkillInstall;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.UserSkillInstallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 全网 MCP 商店服务（真实数据版）。
 *
 * <p>这个服务是“全网 MCP 详细商店”的后端边界。它已经不再返回任何手写的模拟目录，而是直接读取
 * {@code skill} 表里 source=MCP 的真实条目。这些条目由 {@link McpRegistryCrawler} 定时从官方
 * MCP Registry 同步而来，因此商店里的名称、描述、endpoint、版本都是真实有效的。</p>
 *
 * <p>分页、分类、搜索、排序都在内存里对当前 MCP 目录快照完成；当目录规模增长到万级以上时，
 * 应把这些条件下推到 Repository 查询或搜索索引。安装接口只负责把已存在的 MCP 技能与用户建立
 * {@code user_skill_install} 关系，绝不在这里执行安装命令或启动本地进程。</p>
 */
@Service
@RequiredArgsConstructor
public class McpMarketplaceService {

    /** 每页最大数量，防止前端误传过大 size 造成一次性返回过多数据。 */
    private static final int MAX_PAGE_SIZE = 80;

    /** MCP 商店目录来自 skill 表中 source=MCP 且 enabled=true 的条目。 */
    private final SkillRepository skillRepository;

    /** 安装动作会写入用户与 MCP 技能之间的安装关系。 */
    private final UserSkillInstallRepository userSkillInstallRepository;

    /** 安装配置序列化工具。 */
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
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String safeCategory = StringUtils.hasText(category) ? category : "all";
        String safeQuery = StringUtils.hasText(query) ? query.toLowerCase(Locale.ROOT).trim() : "";

        // 直接读取真实 MCP 目录快照（来自爬虫写入的 skill 表）。
        List<McpMarketplaceItemDto> catalog = loadCatalog();

        List<McpMarketplaceItemDto> filtered = catalog.stream()
                // 分类筛选先执行，减少后续搜索和排序处理的数据量。
                .filter(item -> matchCategory(item, safeCategory))
                .filter(item -> !healthyOnly || "healthy".equals(item.health()))
                .filter(item -> !noAuthOnly || "No Auth".equals(item.auth()))
                .filter(item -> matchQuery(item, safeQuery))
                .sorted(comparator(sort))
                .toList();

        // 计算当前页下标，越界页返回空列表。
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());

        return McpMarketplacePageDto.builder()
                .items(filtered.subList(from, to))
                .page(safePage)
                .size(safeSize)
                .total(filtered.size())
                .hasMore(to < filtered.size())
                .categories(categories(catalog))
                .build();
    }

    /**
     * 安装 MCP 技能。
     *
     * <p>商店条目都已经由爬虫写入 skill 表，这里只需要按 skillKey 找到技能并为当前用户建立
     * {@code user_skill_install} 关系。</p>
     *
     * @param request 安装请求，至少需要 mcpId
     * @return 安装后的 MCP 技能条目
     */
    @Transactional
    public McpMarketplaceItemDto install(McpSkillInstallRequest request) {
        if (request == null || !StringUtils.hasText(request.mcpId())) {
            // mcpId 是安装动作的最小必填字段。
            throw new IllegalArgumentException("mcpId must not be blank");
        }
        // 只能安装已经同步进 skill 表、且来源确认为 MCP 的条目。
        Skill skill = skillRepository.findBySkillKey(request.mcpId())
                .filter(candidate -> candidate.getSource() == SkillSource.MCP)
                .orElseThrow(() -> new IllegalArgumentException("Unknown MCP marketplace item: " + request.mcpId()));

        if (StringUtils.hasText(request.userId())) {
            // 安装是幂等操作：已有记录就更新配置并重新启用。
            Optional<UserSkillInstall> existing = userSkillInstallRepository.findByUserIdAndSkillId(request.userId(), skill.getId());
            UserSkillInstall install = existing.orElseGet(UserSkillInstall::new);
            install.setUserId(request.userId());
            install.setSkillId(skill.getId());
            // 用户点击安装后，该安装关系默认启用。
            install.setEnabled(true);
            // 当前配置直接序列化；需要敏感字段时应接入 SecretCipher。
            install.setUserConfig(toJson(request.config()));
            userSkillInstallRepository.save(install);
        }
        // 返回安装后的市场条目，方便前端刷新按钮状态。
        return toItem(skill);
    }

    /**
     * 从 skill 表加载当前启用的 MCP 目录快照。
     */
    private List<McpMarketplaceItemDto> loadCatalog() {
        return skillRepository.findBySourceAndEnabledTrueOrderByDisplayNameAsc(SkillSource.MCP).stream()
                .map(this::toItem)
                .toList();
    }

    /**
     * 把数据库里的 MCP {@link Skill} 转成商店列表项。
     */
    private McpMarketplaceItemDto toItem(Skill skill) {
        // 没有分类时统一归到“通用”。
        String category = StringUtils.hasText(skill.getCategory()) ? skill.getCategory() : "通用";
        // 有 env schema 通常表示需要 API Key，否则视为 No Auth。
        String auth = StringUtils.hasText(skill.getMcpEnvSchema()) && !"{}".equals(skill.getMcpEnvSchema())
                ? "API Key" : "No Auth";
        // tags 当前由分类和传输方式组成，后续可扩展为爬虫标签。
        List<String> tags = new ArrayList<>();
        tags.add(category);
        if (StringUtils.hasText(skill.getMcpTransport())) {
            tags.add(skill.getMcpTransport());
        }
        return McpMarketplaceItemDto.builder()
                .id(skill.getSkillKey())
                .name(StringUtils.hasText(skill.getDisplayName()) ? skill.getDisplayName() : skill.getSkillKey())
                .grade("A")
                .category(category)
                .categoryLabel(category)
                .endpoint(skill.getMcpEndpoint() == null ? "" : skill.getMcpEndpoint())
                // 优先展示中文翻译（爬取时 LLM 生成），没有时回退英文原文。
                .description(StringUtils.hasText(skill.getDescriptionZh()) ? skill.getDescriptionZh()
                        : (skill.getDescription() == null ? "" : skill.getDescription()))
                .auth(auth)
                // Registry 只保留 active 条目，统一标记 healthy；真实健康检查可在 connect 时回填。
                .health("healthy")
                // 工具/资源/提示词数量需要 connect 后 tools/list 才能确定，目录阶段先置 0。
                .tools(0)
                .resources(0)
                .prompts(0)
                .downloads(skill.getDownloads() == null ? 0L : skill.getDownloads())
                .rating(skill.getRating() == null ? 0.0 : skill.getRating().doubleValue())
                .tags(tags)
                .updated(updatedLabel(skill))
                .verified(true)
                .build();
    }

    /**
     * 生成更新时间文案。
     */
    private String updatedLabel(Skill skill) {
        if (skill.getUpdatedAt() == null) {
            return "近期同步";
        }
        LocalDate date = skill.getUpdatedAt().toLocalDate();
        if (date.isEqual(LocalDate.now())) {
            return "今天同步";
        }
        if (date.isEqual(LocalDate.now().minusDays(1))) {
            return "昨天更新";
        }
        return date.toString();
    }

    /**
     * 判断一个 MCP 条目是否匹配分类。
     */
    private boolean matchCategory(McpMarketplaceItemDto item, String category) {
        if ("all".equals(category)) return true;
        if ("no-auth".equals(category)) return "No Auth".equals(item.auth());
        if ("healthy".equals(category)) return "healthy".equals(item.health());
        if ("tools".equals(category)) return item.tools() > 0;
        if ("resources".equals(category)) return item.resources() > 0;
        if ("prompts".equals(category)) return item.prompts() > 0;
        return category.equalsIgnoreCase(item.category());
    }

    /**
     * 判断一个 MCP 条目是否匹配搜索词。
     */
    private boolean matchQuery(McpMarketplaceItemDto item, String query) {
        if (!StringUtils.hasText(query)) return true;
        return List.of(
                        item.name(),
                        item.description(),
                        item.endpoint(),
                        item.categoryLabel(),
                        item.auth(),
                        String.join(" ", item.tags() == null ? List.of() : item.tags()))
                .stream()
                .filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(query));
    }

    /**
     * 根据 sort 参数选择排序器。
     */
    private Comparator<McpMarketplaceItemDto> comparator(String sort) {
        if ("rating".equals(sort)) return Comparator.comparingDouble(McpMarketplaceItemDto::rating).reversed();
        if ("tools".equals(sort)) return Comparator.comparingInt(McpMarketplaceItemDto::tools).reversed();
        if ("recent".equals(sort)) return Comparator.comparing(McpMarketplaceItemDto::updated, Comparator.nullsLast(Comparator.reverseOrder()));
        return Comparator.comparingLong(McpMarketplaceItemDto::downloads).reversed()
                .thenComparing(McpMarketplaceItemDto::name, Comparator.nullsLast(String::compareToIgnoreCase));
    }

    /**
     * 基于真实目录动态生成左侧分类导航。
     *
     * <p>固定保留几个快捷筛选（全部 / No Auth / Healthy / Tools），其余分类直接来自当前 MCP 目录中
     * 出现的真实分类标签，避免出现“分类写死、数据对不上”的空分类。</p>
     */
    private List<McpMarketplaceCategoryDto> categories(List<McpMarketplaceItemDto> catalog) {
        List<McpMarketplaceCategoryDto> categories = new ArrayList<>();
        categories.add(category("all", "全部", "全", catalog.size()));
        categories.add(category("no-auth", "No Auth", "▣", catalog.stream().filter(item -> "No Auth".equals(item.auth())).count()));
        categories.add(category("healthy", "Healthy", "✓", catalog.stream().filter(item -> "healthy".equals(item.health())).count()));
        categories.add(category("tools", "Tools", "⌁", catalog.stream().filter(item -> item.tools() > 0).count()));

        Map<String, Long> realCounts = new LinkedHashMap<>();
        catalog.stream()
                .map(McpMarketplaceItemDto::category)
                .filter(StringUtils::hasText)
                .forEach(label -> realCounts.merge(label, 1L, Long::sum));
        realCounts.forEach((label, count) -> categories.add(category(label, label, iconOf(label), count)));
        return categories;
    }

    /**
     * 构造一个 MCP 市场分类 DTO。
     *
     * <p>分类 DTO 的字段非常固定：key 用于前端筛选请求，label 用于展示，icon 用于左侧导航图标，
     * count 用于徽标数量。把 builder 收敛到这个小方法里，可以让上面的固定分类和动态分类都保持同样的生成规则。</p>
     *
     * @param key 分类稳定 key，例如 {@code all}、{@code no-auth} 或真实分类名
     * @param label 前端展示文本
     * @param icon 前端展示的短图标文本
     * @param count 该分类下的 MCP 数量
     * @return 可直接返回给前端的分类 DTO
     */
    private McpMarketplaceCategoryDto category(String key, String label, String icon, long count) {
        // 小工具方法集中构造分类 DTO，避免重复 builder 样板。
        return McpMarketplaceCategoryDto.builder().key(key).label(label).icon(icon).count(count).build();
    }

    /**
     * 根据分类名称生成短图标。
     */
    private String iconOf(String label) {
        return StringUtils.hasText(label) ? label.substring(0, Math.min(1, label.length())) : "#";
    }

    /**
     * 安装配置转 JSON。
     */
    private String toJson(Map<String, Object> config) {
        try {
            // null 配置按空对象保存。
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid MCP install config", e);
        }
    }
}
