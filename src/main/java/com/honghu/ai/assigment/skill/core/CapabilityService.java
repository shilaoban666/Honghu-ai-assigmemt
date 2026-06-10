package com.honghu.ai.assigment.skill.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.repository.UserRepository;
import com.honghu.ai.assigment.skill.dto.CapabilityCategoryDto;
import com.honghu.ai.assigment.skill.dto.CapabilityDto;
import com.honghu.ai.assigment.skill.dto.request.CapabilityInstallRequest;
import com.honghu.ai.assigment.skill.dto.CapabilityPageDto;
import com.honghu.ai.assigment.skill.entity.SessionSkillSetting;
import com.honghu.ai.assigment.skill.entity.SessionSkillSettingId;
import com.honghu.ai.assigment.skill.entity.Skill;
import com.honghu.ai.assigment.skill.entity.SkillTool;
import com.honghu.ai.assigment.skill.entity.UserSkillInstall;
import com.honghu.ai.assigment.skill.repository.SessionSkillSettingRepository;
import com.honghu.ai.assigment.skill.repository.SkillRepository;
import com.honghu.ai.assigment.skill.repository.SkillToolRepository;
import com.honghu.ai.assigment.skill.repository.UserSkillInstallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 统一能力系统的应用服务。
 *
 * <p>这个服务是前端“能力商店 / 已安装能力 / 会话开关”与后端现有 {@code skill} 领域之间的适配层。
 * 设计重点是复用现有三层状态模型，而不是新建一套平行的 capability 主表：</p>
 * <ul>
 *     <li>{@link Skill}：全局能力目录，描述系统知道哪些能力。</li>
 *     <li>{@link UserSkillInstall}：用户安装关系，描述当前用户拥有或连接了哪些能力。</li>
 *     <li>{@link SessionSkillSetting}：会话级显式开关，描述某个会话是否注入某个能力。</li>
 * </ul>
 *
 * <p>需要特别说明：本类完成的是“目录、安装、启用状态”的闭环，不负责真正执行 MCP、CLI 或
 * Claude Skill 脚本。真正执行仍由 {@code SkillResolverService}、builtin provider，以及后续的
 * MCP/CLI/Claude Skill runtime provider 完成。这样可以先让 UI 和数据库状态真实可用，再逐步补齐运行时。</p>
 */
@Service
@RequiredArgsConstructor
public class CapabilityService {
    /**
     * 市场分页的最大 page size。
     *
     * <p>能力市场未来可能来自全网 registry 或爬虫同步结果，不能允许前端一次请求过大的 size，
     * 否则会把排序、DTO 转换和元数据组装的成本集中到单个请求里。</p>
     */
    private static final int MAX_PAGE_SIZE = 80;

    /** 全局能力目录仓库，所有 marketplace / installed / session 列表都从 skill 表出发。 */
    private final SkillRepository skillRepository;

    /** 能力下具体工具定义仓库，用于 DTO 中返回 toolNames 和 metadata.toolManifest。 */
    private final SkillToolRepository skillToolRepository;

    /** 用户安装关系仓库，用于判断某个用户是否安装、启用某个能力。 */
    private final UserSkillInstallRepository userSkillInstallRepository;

    /** 会话级显式开关仓库，用于写入和读取 session_skill_setting。 */
    private final SessionSkillSettingRepository sessionSkillSettingRepository;

    /** 运行时解析器，复用它计算最终 enabledIds，保证 UI 状态和聊天注入状态一致。 */
    private final SkillResolverService skillResolverService;

    /** 用户仓库，用于读取可信角色，进而让 resolver 按角色计算启用能力。 */
    private final UserRepository userRepository;

    /** 安装配置序列化工具；当前只保存非敏感 config，secrets 后续应走加密存储。 */
    private final ObjectMapper objectMapper;

    /**
     * 分页查询统一能力市场。
     *
     * @param userId 当前用户 id，缺失时按 guest
     * @param sessionId 可选会话 id；传入后 DTO 的 enabled 会反映该会话最终启用状态
     * @param kind 能力大类：builtin/mcp/skill/cli；为空表示全部
     * @param category 分类 key；all 表示不过滤分类
     * @param q 搜索词，匹配 key、名称、描述、分类和作者
     * @param sort 排序方式：popular/rating/recent/tools
     * @param page 0 基页码
     * @param size 每页数量，服务端会限制最大值
     * @return 当前页能力、分页信息和分类统计
     */
    @Transactional(readOnly = true)
    public CapabilityPageDto marketplace(String userId,
                                         String sessionId,
                                         String kind,
                                         String category,
                                         String q,
                                         String sort,
                                         int page,
                                         int size) {
        // page 不能为负数，负数统一当第一页。
        int safePage = Math.max(0, page);
        // size 至少 1，最多 MAX_PAGE_SIZE，保护后端内存和转换成本。
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        // kind 只接受白名单值，非法 kind 会归一化成 null，即不过滤 kind。
        String safeKind = normalizeKind(kind);
        // category 为空时按 all 处理。
        String safeCategory = StringUtils.hasText(category) ? category : "all";
        // 搜索统一小写和 trim，后续字段也转小写做 contains。
        String query = StringUtils.hasText(q) ? q.toLowerCase(Locale.ROOT).trim() : "";

        // 构造当前用户/会话上下文，包含安装态和最终启用态。
        CapabilityContext context = context(userId, sessionId);
        // 当前数据量较小，直接从 skill 表读取后在内存中归一化、筛选、排序。
        // 后续真实全网目录达到万级以上时，应把 kind/category/search/sort 下推到 Repository 或搜索索引。
        //
        // base 只按 kind + 搜索词过滤，不含“分类”过滤：分类导航的计数必须基于这个集合，
        // 否则一旦选中某个分类，categories 只会剩下当前分类，前端其它分类按钮就会“消失”。
        List<Skill> base = skillRepository.findAll().stream()
                // 全局禁用能力不出现在市场。
                .filter(Skill::isEnabled)
                .filter(skill -> safeKind == null || safeKind.equals(kindOf(skill)))
                .filter(skill -> matches(skill, query))
                .toList();
        // filtered 在 base 之上再叠加“分类”过滤和排序，作为当前页要展示的列表。
        List<Skill> filtered = base.stream()
                .filter(skill -> "all".equals(safeCategory) || safeCategory.equalsIgnoreCase(nullToEmpty(skill.getCategory())))
                .sorted(comparator(sort))
                .toList();

        // 根据页码和每页数量计算当前页起止下标，越界页返回空 items。
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return CapabilityPageDto.builder()
                .items(filtered.subList(from, to).stream().map(skill -> toDto(skill, context)).toList())
                .page(safePage)
                .size(safeSize)
                .total(filtered.size())
                .hasMore(to < filtered.size())
                // 分类导航始终基于 base（不含分类过滤），保证切换分类时所有分类按钮都在。
                .categories(categories(base))
                .build();
    }

    /**
     * 查询全部启用中的内置能力。
     */
    @Transactional(readOnly = true)
    public List<CapabilityDto> builtin(String userId, String sessionId) {
        CapabilityContext context = context(userId, sessionId);
        // 内置能力由 Java 注解扫描写入数据库。它们不需要用户安装，但仍需要返回 enabled，
        // 因为用户可以在会话中关闭非 mandatory 的内置工具。
        return skillRepository.findBySourceAndEnabledTrueOrderByDisplayNameAsc(SkillSource.BUILTIN).stream()
                .map(skill -> toDto(skill, context))
                .toList();
    }

    /**
     * 查询当前用户“已安装/隐式安装”的能力。
     *
     * <p>这里返回的 installed 不等于“本会话一定启用”。例如用户安装了某个 MCP，但在当前会话关闭了，
     * DTO 中 installed=true、enabled=false。</p>
     */
    @Transactional(readOnly = true)
    public List<CapabilityDto> installed(String userId, String sessionId) {
        CapabilityContext context = context(userId, sessionId);
        // 先放入用户显式安装且启用的能力 id。
        Set<Long> ids = new HashSet<>(context.installedIds());
        // builtin/default/mandatory 属于“隐式安装”。前端设置页需要看到这些能力，
        // 但输入区胶囊会再过滤系统上下文类能力，避免把 user_context 等底座能力展示给用户。
        skillRepository.findAll().stream()
                .filter(skill -> skill.isEnabled() && (skill.getSource() == SkillSource.BUILTIN || skill.isDefaultEnabled() || skill.isMandatory()))
                .map(Skill::getId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        return skillRepository.findAllById(ids).stream()
                .filter(Skill::isEnabled)
                .sorted(Comparator.comparing(Skill::getDisplayName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(skill -> toDto(skill, context))
                .toList();
    }

    /**
     * 查询某个会话中可展示的能力开关列表。
     */
    @Transactional(readOnly = true)
    public List<CapabilityDto> sessionSkills(String userId, String sessionId) {
        CapabilityContext context = context(userId, sessionId);
        // 会话设置页展示“可选范围”，因此从用户已安装能力开始，再加隐式安装能力。
        Set<Long> ids = new HashSet<>(context.installedIds());
        // 会话能力列表等于：用户已安装能力 + 隐式安装能力。enabled 字段再由 resolver 统一计算。
        // 这样前端既能展示“可选但关闭”的能力，也能展示“当前已启用”的能力。
        skillRepository.findAll().stream()
                .filter(skill -> skill.isEnabled() && (skill.getSource() == SkillSource.BUILTIN || skill.isDefaultEnabled() || skill.isMandatory()))
                .map(Skill::getId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        return skillRepository.findAllById(ids).stream()
                .filter(Skill::isEnabled)
                .sorted(Comparator.comparing(Skill::getDisplayName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(skill -> toDto(skill, context))
                .toList();
    }

    /**
     * 为当前用户安装一个能力。
     */
    @Transactional
    public CapabilityDto install(String userId, String sessionId, String skillKey, CapabilityInstallRequest request) {
        // 没有用户头时按 guest 写入，便于本地开发；正式环境应由身份层传真实 userId。
        String safeUserId = normalizeUserId(userId);
        // skillKey 是外部 API 使用的稳定标识，不暴露数据库 id。
        Skill skill = skillRepository.findBySkillKey(skillKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability: " + skillKey));
        // mandatory builtin 已经由系统隐式安装，不需要写 user_skill_install；其他能力都写用户安装关系。
        if (skill.getSource() != SkillSource.BUILTIN || !skill.isMandatory()) {
            // 安装接口幂等：已有记录就更新启用状态和配置，没有记录才新建。
            UserSkillInstall install = userSkillInstallRepository.findByUserIdAndSkillId(safeUserId, skill.getId())
                    .orElseGet(UserSkillInstall::new);
            install.setUserId(safeUserId);
            install.setSkillId(skill.getId());
            // install 接口表示用户希望使用该能力，因此 enabled=true。
            install.setEnabled(true);
            // 当前只保存 config；secrets 字段在 DTO 中预留，后续应走 SecretCipher 加密。
            install.setUserConfig(toJson(request == null ? null : request.config()));
            userSkillInstallRepository.save(install);
        }
        // 返回安装后的最新 DTO，方便前端立即刷新按钮状态。
        return toDto(skill, context(safeUserId, sessionId));
    }

    /**
     * 卸载当前用户的一个能力。
     */
    @Transactional
    public void uninstall(String userId, String sessionId, String skillKey) {
        String safeUserId = normalizeUserId(userId);
        Skill skill = skillRepository.findBySkillKey(skillKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability: " + skillKey));
        if (skill.isMandatory()) {
            // mandatory 能力由系统强制注入，不能被用户卸载。
            throw new IllegalArgumentException("Mandatory capability cannot be uninstalled: " + skillKey);
        }
        // 卸载只删除当前用户的 install 记录，不删除全局 skill 目录项；其他用户仍能看到市场条目。
        userSkillInstallRepository.findByUserIdAndSkillId(safeUserId, skill.getId())
                .ifPresent(userSkillInstallRepository::delete);
        if (StringUtils.hasText(sessionId)) {
            // 如果当前会话存在显式开关，也一起清理，避免卸载后会话还残留 enabled/disabled 快照。
            sessionSkillSettingRepository.deleteBySessionIdAndSkillId(sessionId, skill.getId());
        }
    }

    /**
     * 设置某个会话中某个能力是否启用。
     */
    @Transactional
    public CapabilityDto setSessionEnabled(String userId, String sessionId, String skillKey, boolean enabled) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Skill skill = skillRepository.findBySkillKey(skillKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability: " + skillKey));
        if (skill.isMandatory() && !enabled) {
            // 必装能力即使前端传 false，也不能被关闭。
            throw new IllegalArgumentException("Mandatory capability cannot be disabled: " + skillKey);
        }
        // session_skill_setting 是显式覆盖层。enabled=false 也要落库，否则 resolver 会退回默认启用规则。
        SessionSkillSetting setting = sessionSkillSettingRepository
                .findById(new SessionSkillSettingId(sessionId, skill.getId()))
                .orElseGet(SessionSkillSetting::new);
        // 联合主键第一部分：会话 id。
        setting.setSessionId(sessionId);
        // 联合主键第二部分：技能 id。
        setting.setSkillId(skill.getId());
        // 用户在这个会话里的显式选择。
        setting.setEnabled(enabled);
        // 启用时记录启用时间（供前端「启用于 X」提示）；关闭时清空。
        setting.setEnabledAt(enabled ? java.time.LocalDateTime.now() : null);
        sessionSkillSettingRepository.save(setting);
        return toDto(skill, context(userId, sessionId));
    }

    /**
     * 构造 DTO 映射所需的用户/会话上下文快照。
     */
    private CapabilityContext context(String userId, String sessionId) {
        String safeUserId = normalizeUserId(userId);
        // 安装态只统计 enabled=true 的 install 记录；未来如果引入 status=connected/error，
        // 这里应改成按 status 判断 installed/connected，而不是只看 enabled。
        Set<Long> installedIds = userSkillInstallRepository.findByUserId(safeUserId).stream()
                .filter(UserSkillInstall::isEnabled)
                .map(UserSkillInstall::getSkillId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 会话显式设置保留在上下文里，方便后续扩展展示“本会话显式打开/关闭”状态。
        List<SessionSkillSetting> settings = StringUtils.hasText(sessionId)
                ? sessionSkillSettingRepository.findBySessionId(sessionId)
                : List.of();
        Map<Long, Boolean> sessionSettings = settings.stream()
                .collect(Collectors.toMap(SessionSkillSetting::getSkillId, SessionSkillSetting::isEnabled, (a, b) -> b));
        // 已启用能力的启用时间，供 DTO 暴露给前端做悬停提示。
        Map<Long, java.time.LocalDateTime> sessionEnabledAt = settings.stream()
                .filter(SessionSkillSetting::isEnabled)
                .filter(setting -> setting.getEnabledAt() != null)
                .collect(Collectors.toMap(SessionSkillSetting::getSkillId, SessionSkillSetting::getEnabledAt, (a, b) -> b));
        // 角色缺失时按 GUEST，避免无身份请求获得高权限能力。
        User.UserRole role = userRepository.findById(safeUserId).map(User::getUserRole).orElse(User.UserRole.GUEST);
        // enabled 由既有 resolver 计算，保证前端看到的状态和聊天请求真正解析出的工具集合一致。
        Set<Long> effectiveEnabledIds = skillResolverService.computeEnabledSkillIds(safeUserId, sessionId, role);
        return new CapabilityContext(safeUserId, installedIds, sessionSettings, effectiveEnabledIds, sessionEnabledAt);
    }

    /**
     * 把数据库里的 {@link Skill} 转成前端统一使用的 {@link CapabilityDto}。
     *
     * <p>DTO 中故意同时保留 {@code id} 和 {@code skillKey}：前端旧组件常用 id，新组件按能力系统语义
     * 使用 skillKey；两者当前都指向 {@code skill.skill_key}，便于迁移期兼容。</p>
     */
    private CapabilityDto toDto(Skill skill, CapabilityContext context) {
        // 读取该能力下的工具定义，按 sortOrder 保持展示顺序。
        List<SkillTool> tools = skillToolRepository.findBySkillIdOrderBySortOrderAsc(skill.getId());
        // 归一化成前端使用的大类。
        String kind = kindOf(skill);
        // installed 表示用户拥有/系统隐式拥有该能力，不代表本会话启用。
        boolean installed = isImplicitlyInstalled(skill) || context.installedIds().contains(skill.getId());
        // enabled 必须按 resolver 结果算，mandatory 永远为 true。
        boolean enabled = skill.isMandatory() || context.effectiveEnabledIds().contains(skill.getId());
        // metadata 按 kind 提供不同扩展字段。
        Map<String, Object> metadata = metadata(skill, tools, kind);
        return CapabilityDto.builder()
                .skillId(skill.getId())
                .id(skill.getSkillKey())
                .kind(kind)
                .skillKey(skill.getSkillKey())
                .source(skill.getSource().name())
                .name(skill.getDisplayName())
                .slug(slug(skill.getSkillKey()))
                .description(displayDescription(skill))
                .icon(icon(skill, kind))
                .accent(accent(kind))
                .category(nullToDefault(skill.getCategory(), "general"))
                .origin(origin(skill))
                .publisher(nullToDefault(skill.getAuthor(), publisher(kind)))
                .version(nullToDefault(skill.getVersion(), "v1"))
                .rating(skill.getRating())
                .downloads(skill.getDownloads())
                .verified(skill.getSource() == SkillSource.BUILTIN)
                .installed(installed)
                .enabled(enabled)
                .mandatory(skill.isMandatory())
                .defaultEnabled(skill.isDefaultEnabled())
                .requiredRole(skill.getRequiredRole() == null ? "USER" : skill.getRequiredRole().name())
                .enabledAt(enabledAtIso(context.sessionEnabledAt().get(skill.getId())))
                .toolNames(tools.stream().map(SkillTool::getToolName).toList())
                .metadata(metadata)
                .build();
    }

    /**
     * 根据能力类型生成前端详情页需要的扩展元数据。
     */
    private Map<String, Object> metadata(Skill skill, List<SkillTool> tools, String kind) {
        // LinkedHashMap 让 JSON 字段顺序稳定，便于调试。
        Map<String, Object> metadata = new LinkedHashMap<>();
        // 所有类型都返回工具名和工具数量。
        metadata.put("toolNames", tools.stream().map(SkillTool::getToolName).toList());
        metadata.put("toolCount", tools.size());
        if ("mcp".equals(kind)) {
            // MCP 的 toolManifest 目前来自 skill_tool。真实 MCP connect 后，应由 tools/list introspection 回填。
            metadata.put("transport", nullToDefault(skill.getMcpTransport(), "unknown"));
            metadata.put("endpoint", nullToEmpty(skill.getMcpEndpoint()));
            metadata.put("authType", authType(skill.getMcpEnvSchema()));
            metadata.put("authStatus", "none");
            metadata.put("health", "unknown");
            metadata.put("toolManifest", tools.stream().map(tool -> Map.of(
                    "name", tool.getToolName(),
                    "description", nullToEmpty(tool.getDescription()),
                    "inputSchema", nullToDefault(tool.getParametersSchema(), "{}")
            )).toList());
        } else if ("cli".equals(kind)) {
            // CLI 当前只表达“可安装/可开关/应走沙箱”的目录信息，不在这里暴露万能 shell。
            metadata.put("command", skill.getSkillKey().replaceFirst("^cli:", ""));
            metadata.put("permissionScope", List.of("read", "workspace"));
            metadata.put("sandboxed", true);
            metadata.put("installSpec", nullToEmpty(skill.getMcpInstallCmd()));
        } else if ("skill".equals(kind)) {
            // Claude Skill 当前阶段只保留 prompt 型元信息；后续 SKILL.md parser 会补 frontmatter/resources/scripts。
            metadata.put("frontmatter", "");
            metadata.put("teaches", nullToEmpty(displayDescription(skill)));
            metadata.put("hasScripts", !tools.isEmpty());
            metadata.put("runtime", "prompt");
            metadata.put("license", nullToDefault(skill.getLicense(), "unknown"));
        } else {
            metadata.put("defaultEnabled", skill.isDefaultEnabled());
        }
        return metadata;
    }

    /**
     * 统计当前筛选范围内的分类数量。
     */
    private List<CapabilityCategoryDto> categories(List<Skill> skills) {
        Map<String, Long> counts = new LinkedHashMap<>();
        // all 永远放第一项，数量等于当前 base 集合总量。
        counts.put("all", (long) skills.size());
        skills.stream()
                .map(Skill::getCategory)
                .filter(StringUtils::hasText)
                .forEach(category -> counts.merge(category, 1L, Long::sum));
        return counts.entrySet().stream()
                .map(entry -> CapabilityCategoryDto.builder()
                        .key(entry.getKey())
                        .label("all".equals(entry.getKey()) ? "全部" : entry.getKey())
                        .icon(categoryIcon(entry.getKey()))
                        .count(entry.getValue())
                        .build())
                .toList();
    }

    /**
     * 根据 sort 参数返回能力排序器。
     */
    private Comparator<Skill> comparator(String sort) {
        if ("rating".equals(sort)) {
            // 按评分倒序，null 排最后。
            return Comparator.comparing(Skill::getRating, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        }
        if ("recent".equals(sort)) {
            // 按更新时间倒序，适合“最新”排序。
            return Comparator.comparing(Skill::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        }
        if ("tools".equals(sort)) {
            // 按工具数量倒序；当前通过仓库查询，数据量大时应下推/缓存。
            return Comparator.comparingInt((Skill skill) -> skillToolRepository.findBySkillIdOrderBySortOrderAsc(skill.getId()).size()).reversed();
        }
        // 默认 popular：按 downloads 倒序。
        return Comparator.comparing(Skill::getDownloads, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
    }

    /**
     * 判断 skill 是否匹配搜索词。
     */
    private boolean matches(Skill skill, String query) {
        if (!StringUtils.hasText(query)) return true;
        // 用 Stream.of 而不是 List.of：真实爬取的技能可能有 null 字段（description/author 等），
        // List.of 不允许 null 元素会直接抛 NPE，导致带搜索词的市场查询 500。
        return Stream.of(skill.getSkillKey(), skill.getDisplayName(), skill.getDescription(), skill.getCategory(), skill.getAuthor())
                .filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(query));
    }

    /**
     * 归一化前端传入的 kind。
     */
    private String normalizeKind(String kind) {
        if (!StringUtils.hasText(kind)) return null;
        String normalized = kind.toLowerCase(Locale.ROOT);
        // 前端可能传复数 skills，后端统一成 skill。
        if ("skills".equals(normalized)) return "skill";
        // 只接受白名单 kind，非法值按不过滤处理。
        if (Set.of("builtin", "mcp", "skill", "cli").contains(normalized)) return normalized;
        return null;
    }

    /**
     * 根据 skillKey 和 source 计算前端统一大类。
     */
    private String kindOf(Skill skill) {
        // skill_key 前缀优先于 source，是为了兼容旧数据和本次 seed：cli:*、skill:* 一眼可判定 kind。
        String key = nullToEmpty(skill.getSkillKey());
        if ("cli".equals(key) || key.startsWith("cli:")) return "cli";
        if (key.startsWith("skill:")) return "skill";
        if (skill.getSource() == SkillSource.BUILTIN) return "builtin";
        if (skill.getSource() == SkillSource.MCP) return "mcp";
        if (skill.getSource() == SkillSource.CLAUDE_SKILL) return "skill";
        if (skill.getSource() == SkillSource.CLI) return "cli";
        return "skill";
    }

    /**
     * 判断能力是否属于系统隐式安装。
     */
    private boolean isImplicitlyInstalled(Skill skill) {
        // 隐式安装能力没有 user_skill_install 行，但前端仍应该在“已安装/会话能力”中看到它们。
        return skill.getSource() == SkillSource.BUILTIN || skill.isMandatory() || skill.isDefaultEnabled();
    }

    /**
     * 规范用户 id，缺失时使用 guest。
     */
    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId : "guest";
    }

    /**
     * 将安装配置序列化为 JSON 字符串。
     */
    private String toJson(Map<String, Object> config) {
        try {
            // null 配置按空对象保存。
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid capability config", e);
        }
    }

    /**
     * 生成适合 URL/CSS/test selector 使用的短 slug。
     */
    private String slug(String skillKey) {
        // 冒号替换为短横线，例如 mcp:tavily -> mcp-tavily。
        return nullToEmpty(skillKey).replace(':', '-').toLowerCase(Locale.ROOT);
    }

    /**
     * 返回能力图标。
     */
    private String icon(Skill skill, String kind) {
        // 数据库有 iconUrl/icon 文本时优先使用。
        if (StringUtils.hasText(skill.getIconUrl())) return skill.getIconUrl();
        return switch (kind) {
            case "mcp" -> "MCP";
            case "cli" -> "CLI";
            case "skill" -> "技";
            default -> "能";
        };
    }

    /**
     * 返回前端卡片强调色。
     */
    private String accent(String kind) {
        return switch (kind) {
            case "mcp" -> "#d8f8e6";
            case "cli" -> "#d7f8ef";
            case "skill" -> "#e4efff";
            default -> "#ffe1df";
        };
    }

    /**
     * 返回来源类型。
     */
    private String origin(Skill skill) {
        // 内置能力来源为 builtin，其余当前统一视作 community。
        return skill.getSource() == SkillSource.BUILTIN ? "builtin" : "community";
    }

    /**
     * 返回默认发布者名称。
     */
    private String publisher(String kind) {
        return switch (kind) {
            case "mcp" -> "MCP Registry";
            case "cli" -> "Local";
            case "skill" -> "Community";
            default -> "System";
        };
    }

    /**
     * 根据 MCP 环境变量 schema 推断鉴权类型。
     */
    private String authType(String envSchema) {
        // 有 env schema 通常意味着需要 API Key；空对象表示不需要鉴权。
        return StringUtils.hasText(envSchema) && !"{}".equals(envSchema) ? "apiKey" : "none";
    }

    /**
     * 生成分类图标文本。
     */
    private String categoryIcon(String key) {
        if ("all".equals(key)) return "All";
        if (key == null || key.isBlank()) return "#";
        // 非 all 分类取前两个字符大写，作为轻量占位图标。
        return key.substring(0, Math.min(2, key.length())).toUpperCase(Locale.ROOT);
    }

    /**
     * 有文本时返回 value，否则返回 fallback。
     */
    private String nullToDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /** 展示描述：优先中文翻译（爬取时由 LLM 生成），没有时回退原始描述（内置技能本身就是中文）。 */
    private String displayDescription(Skill skill) {
        return StringUtils.hasText(skill.getDescriptionZh()) ? skill.getDescriptionZh() : skill.getDescription();
    }

    /** 把启用时间格式化为 ISO 字符串供前端展示；为空返回 null。 */
    private String enabledAtIso(java.time.LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    /**
     * null 转空字符串。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * DTO 映射过程中复用的上下文快照。
     *
     * @param userId 规范化后的用户 id
     * @param installedIds 当前用户显式安装并启用的能力 id 集合
     * @param sessionSettings 当前会话显式开关快照；当前预留，便于后续展示“显式关闭/打开”
     * @param effectiveEnabledIds resolver 计算出的本会话最终启用能力 id 集合
     */
    private record CapabilityContext(
            String userId,
            Set<Long> installedIds,
            Map<Long, Boolean> sessionSettings,
            Set<Long> effectiveEnabledIds,
            Map<Long, java.time.LocalDateTime> sessionEnabledAt
    ) {
    }
}
