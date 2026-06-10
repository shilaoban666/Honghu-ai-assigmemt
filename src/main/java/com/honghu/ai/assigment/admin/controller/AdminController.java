package com.honghu.ai.assigment.admin.controller;

import com.honghu.ai.assigment.admin.service.AdminAuthService;
import com.honghu.ai.assigment.config.properties.AiProviderProperties;
import com.honghu.ai.assigment.dto.AiModelResponse;
import com.honghu.ai.assigment.dto.QuotaSnapshot;
import com.honghu.ai.assigment.entity.*;
import com.honghu.ai.assigment.repository.*;
import com.honghu.ai.assigment.service.EntitlementService;
import com.honghu.ai.assigment.service.QuotaService;
import com.honghu.ai.assigment.skill.security.SecretCipher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 运营后台接口。
 *
 * <p>这个控制器提供后台管理系统需要的主要接口：模型目录、价格、角色配额、用户覆盖、
 * 企业 workspace、套餐权益和调用用量监控。</p>
 *
 * <p>当前后台鉴权使用轻量级登录态：先调用 {@code /auth/login} 换取 Bearer token，
 * 后续接口用 {@code Authorization: Bearer <token>}。为了兼容已有调试脚本，
 * 旧的 {@code X-User-Id} 管理员头仍然保留为兜底路径。</p>
 *
 * <p>这里直接返回实体或简单 Map，是为了快速交付后台 MVP。
 * 如果后续对外开放或前端契约变复杂，建议逐步拆出专门的 Admin DTO。</p>
 */
@Tag(name = "后台管理", description = "后台管理系统接口：管理员登录、模型目录、价格、角色、用户、工作空间、套餐、用量和 RAG 监控。")
@SecurityRequirement(name = "adminBearerAuth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminController {

    /**
     * 临时后台鉴权请求头。值必须是 ADMIN 用户的 userId。
     */
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final UserRepository userRepository;
    private final AiModelDefinitionRepository aiModelDefinitionRepository;
    private final AiProviderRepository aiProviderRepository;
    private final AiModelPricingRepository aiModelPricingRepository;
    private final AiUsageEventRepository aiUsageEventRepository;
    private final RoleQuotaConfigRepository roleQuotaConfigRepository;
    private final RoleModelDefaultRepository roleModelDefaultRepository;
    private final UserModelPermissionRepository userModelPermissionRepository;
    private final UserQuotaOverrideRepository userQuotaOverrideRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagDocumentChunkRepository ragDocumentChunkRepository;
    private final RagIngestionEventRepository ragIngestionEventRepository;
    private final AdminAuthService adminAuthService;
    private final EntitlementService entitlementService;
    private final QuotaService quotaService;
    private final SecretCipher secretCipher;

    /**
     * 后台登录。
     *
     * <p>前端提交管理员用户名和密码，后端确认账号角色是 ADMIN 后返回 Bearer token。
     * 后续所有 /api/v1/admin 业务接口都优先使用
     * {@code Authorization: Bearer <token>} 鉴权。</p>
     */
    @Operation(summary = "后台管理员登录", description = "使用 ADMIN 用户名和密码登录后台，返回后续后台请求使用的 Bearer token。", security = {})
    @PostMapping("/auth/login")
    public AdminAuthService.AdminLoginResult adminLogin(@RequestBody AdminLoginRequest request) {
        return adminAuthService.login(required(request.getUsername(), "username"), required(request.getPassword(), "password"));
    }

    /**
     * 查询当前后台登录用户。
     *
     * <p>用于前端刷新页面后确认 token 是否还有效，并展示管理员信息。</p>
     */
    @Operation(summary = "查询当前后台管理员", description = "校验后台 Bearer token 是否有效，并返回当前管理员用户信息。")
    @GetMapping("/auth/me")
    public User adminMe(@RequestHeader(value = AUTHORIZATION_HEADER, required = false) String authorization,
                        @RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId) {
        return requireAdmin(authorization, adminUserId);
    }

    /**
     * 主动退出后台登录。
     */
    @Operation(summary = "后台管理员退出登录", description = "主动使当前后台 Bearer token 失效。")
    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminLogout(@RequestHeader(value = AUTHORIZATION_HEADER, required = false) String authorization) {
        adminAuthService.logout(authorization);
    }

    /**
     * 查询模型目录。
     *
     * <p>后台需要看到 disabled 模型，所以这里查的是全部模型，不只查 enabled。
     * 返回值里会附带当前生效价格和最近 24 小时调用量，方便模型列表直接展示运营信息。</p>
     */
    @Operation(summary = "查询模型目录", description = "返回全部模型，包括已禁用模型，并附带当前生效价格和最近 24 小时调用次数。")
    @GetMapping("/models")
    public List<ModelAdminView> listModels(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId) {
        requireAdmin(null, adminUserId);
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        return aiModelDefinitionRepository.findAll(Sort.by("level").ascending().and(Sort.by("score").descending()))
                .stream()
                .map(model -> modelView(model, since))
                .toList();
    }

    /**
     * 新建模型。
     *
     * <p>模型定义只描述“模型是什么、属于哪个 provider、是否支持流式”等静态信息。
     * 如果请求体同时带 pricing，会顺手创建第一条价格快照，避免模型创建后因为没价格无法调用。</p>
     */
    @Operation(summary = "新建模型", description = "创建模型目录记录，可同时写入第一条价格快照。")
    @PostMapping("/models")
    @Transactional
    public ModelAdminView createModel(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                      @RequestBody ModelUpsertRequest request) {
        requireAdmin(null, adminUserId);
        if (!StringUtils.hasText(request.getModelCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelCode is required");
        }
        if (aiModelDefinitionRepository.existsById(request.getModelCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "model already exists");
        }
        AiModelDefinition model = AiModelDefinition.builder()
                .modelCode(request.getModelCode())
                .displayName(defaultString(request.getDisplayName(), request.getModelCode()))
                .providerCode(required(request.getProviderCode(), "providerCode"))
                .apiModelName(defaultString(request.getApiModelName(), request.getModelCode()))
                .level(request.getLevel() == null ? 2 : request.getLevel())
                .score(request.getScore() == null ? 0 : request.getScore())
                .localModel(Boolean.TRUE.equals(request.getLocalModel()))
                .supportsStream(request.getSupportsStream() == null || request.getSupportsStream())
                .enabled(request.getEnabled() == null || request.getEnabled())
                .description(request.getDescription())
                .build();
        model = aiModelDefinitionRepository.save(model);
        if (request.getPricing() != null) {
            savePricing(model.getModelCode(), request.getPricing());
        }
        return modelView(model, LocalDateTime.now().minusHours(24));
    }

    /**
     * 查询模型详情。
     *
     * <p>详情页需要的信息比列表更多：历史价格、开放角色、24 小时调用量都会一起返回。</p>
     */
    @Operation(summary = "查询模型详情", description = "返回模型、历史价格、开放角色和最近 24 小时调用次数。")
    @GetMapping("/models/{code}")
    public Map<String, Object> getModel(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                        @PathVariable String code) {
        requireAdmin(null, adminUserId);
        AiModelDefinition model = findModel(code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelView(model, LocalDateTime.now().minusHours(24)));
        body.put("pricing", aiModelPricingRepository.findByModelCodeOrderByEffectiveFromDesc(code));
        body.put("roles", roleModelDefaultRepository.findByModelCodeAndEnabledTrue(code).stream().map(RoleModelDefault::getRole).toList());
        body.put("last24hCalls", aiUsageEventRepository.countByModelCodeAndCreatedAtAfter(code, LocalDateTime.now().minusHours(24)));
        return body;
    }

    /**
     * 更新模型基础信息。
     *
     * <p>这里不直接修改价格；价格属于历史快照，必须通过 pricing 接口新增一条价格记录。</p>
     */
    @Operation(summary = "更新模型基础信息", description = "更新模型展示名、provider、层级、排序、流式能力和说明等字段。")
    @PatchMapping("/models/{code}")
    @Transactional
    public ModelAdminView updateModel(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                      @PathVariable String code,
                                      @RequestBody ModelUpsertRequest request) {
        requireAdmin(null, adminUserId);
        AiModelDefinition model = findModel(code);
        if (request.getDisplayName() != null) model.setDisplayName(request.getDisplayName());
        if (request.getProviderCode() != null) model.setProviderCode(request.getProviderCode());
        if (request.getApiModelName() != null) model.setApiModelName(request.getApiModelName());
        if (request.getLevel() != null) model.setLevel(request.getLevel());
        if (request.getScore() != null) model.setScore(request.getScore());
        if (request.getLocalModel() != null) model.setLocalModel(request.getLocalModel());
        if (request.getSupportsStream() != null) model.setSupportsStream(request.getSupportsStream());
        if (request.getDescription() != null) model.setDescription(request.getDescription());
        return modelView(aiModelDefinitionRepository.save(model), LocalDateTime.now().minusHours(24));
    }

    /**
     * 启用或禁用模型。
     *
     * <p>禁用后模型不会出现在用户可用模型列表里，但历史调用流水仍然保留。</p>
     */
    @Operation(summary = "启用或禁用模型", description = "切换模型 enabled 状态；禁用后用户不可见，但历史用量仍保留。")
    @PatchMapping("/models/{code}/enabled")
    @Transactional
    public ModelAdminView setModelEnabled(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                          @PathVariable String code,
                                          @RequestBody EnabledRequest request) {
        requireAdmin(null, adminUserId);
        AiModelDefinition model = findModel(code);
        model.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        return modelView(aiModelDefinitionRepository.save(model), LocalDateTime.now().minusHours(24));
    }

    /**
     * 软删除模型。
     *
     * <p>为了保护 ai_usage_event 的历史外键，不物理删除模型，只把 enabled 置为 false。</p>
     */
    @Operation(summary = "软删除模型", description = "将模型 enabled 置为 false，保留价格和调用流水，适合正式模型下架。")
    @DeleteMapping("/models/{code}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDeleteModel(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                @PathVariable String code) {
        requireAdmin(null, adminUserId);
        AiModelDefinition model = findModel(code);
        model.setEnabled(false);
        aiModelDefinitionRepository.save(model);
    }

    /**
     * 硬删除模型。
     *
     * <p>这个接口会真实删除模型目录记录，并清理依赖它的角色授权、用户 override、套餐权益、
     * 价格和调用流水。它适合删除误创建、测试模型或确认不需要保留审计的模型；如果只是下架，
     * 应继续使用普通 DELETE 的软删除。</p>
     */
    @Operation(summary = "硬删除模型", description = "物理删除模型并清理角色授权、用户覆盖、套餐权益、价格和调用流水；仅适合误建或测试模型。")
    @DeleteMapping("/models/{code}/hard")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hardDeleteModel(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                @PathVariable String code) {
        requireAdmin(null, adminUserId);
        findModel(code);
        roleModelDefaultRepository.deleteByModelCode(code);
        userModelPermissionRepository.deleteByModelCode(code);
        planEntitlementRepository.deleteByEntitlementTypeAndEntitlementKey(PlanEntitlement.EntitlementType.ALLOWED_MODEL, code);
        aiUsageEventRepository.deleteByModelCode(code);
        aiModelPricingRepository.deleteByModelCode(code);
        aiModelDefinitionRepository.deleteById(code);
    }

    /**
     * 查询模型的全部历史价格。
     *
     * <p>价格按 effectiveFrom 倒序返回，前端可以用于价格历史表格和回溯排查。</p>
     */
    @Operation(summary = "查询模型价格历史", description = "按生效时间倒序返回模型的全部价格快照。")
    @GetMapping("/models/{code}/pricing")
    public List<AiModelPricing> listPricing(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                            @PathVariable String code) {
        requireAdmin(null, adminUserId);
        findModel(code);
        return aiModelPricingRepository.findByModelCodeOrderByEffectiveFromDesc(code);
    }

    /**
     * 新增一条价格快照。
     *
     * <p>新增价格时会把当前仍然有效的旧价格 effectiveTo 设置为 now。
     * 这样历史调用仍绑定旧 pricingId，新调用会命中新价格。</p>
     */
    @Operation(summary = "新增模型价格快照", description = "新增价格时自动关闭上一条仍生效的价格，保证历史调用绑定旧价格。")
    @PostMapping("/models/{code}/pricing")
    @Transactional
    public AiModelPricing createPricing(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                        @PathVariable String code,
                                        @RequestBody PricingRequest request) {
        requireAdmin(null, adminUserId);
        findModel(code);
        LocalDateTime now = LocalDateTime.now();
        for (AiModelPricing pricing : aiModelPricingRepository.findActiveCandidates(code, now)) {
            pricing.setEffectiveTo(now);
            aiModelPricingRepository.save(pricing);
        }
        return savePricing(code, request);
    }

    /**
     * 快捷调整模型加价倍率。
     *
     * <p>实现上不是原地改当前价格，而是复制当前价格并生成新快照。
     * 这样价格历史和历史账单都能保持稳定。</p>
     */
    @Operation(summary = "快捷调整模型加价倍率", description = "复制当前价格生成新快照，只修改 markupRatio，不直接覆盖历史价格。")
    @PatchMapping("/models/{code}/markup-ratio")
    @Transactional
    public AiModelPricing updateMarkupRatio(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                            @PathVariable String code,
                                            @RequestBody MarkupRequest request) {
        requireAdmin(null, adminUserId);
        AiModelPricing current = aiModelPricingRepository.findActivePricing(code, LocalDateTime.now())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "active pricing not found"));
        PricingRequest next = PricingRequest.from(current);
        next.setMarkupRatio(request.getMarkupRatio());
        return createPricing(adminUserId, code, next);
    }

    /**
     * 查询所有角色的配额、模型数量和用户数量。
     */
    @Operation(summary = "查询角色概览", description = "返回每个角色的额度配置、默认模型数量和当前用户数量。")
    @GetMapping("/roles")
    public List<RoleAdminView> listRoles(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId) {
        requireAdmin(null, adminUserId);
        return List.of(User.UserRole.values()).stream().map(role -> {
            RoleQuotaConfig quota = roleQuotaConfigRepository.findById(role.name()).orElse(null);
            long modelCount = roleModelDefaultRepository.findByRoleAndEnabledTrue(role.name()).size();
            long userCount = userRepository.countByUserRole(role);
            return new RoleAdminView(role.name(), quota, modelCount, userCount);
        }).toList();
    }

    /**
     * 查询单个角色的额度配置。
     */
    @Operation(summary = "查询角色额度", description = "查询单个角色的日额度、月额度和并发配置。")
    @GetMapping("/roles/{role}/quota")
    public RoleQuotaConfig getRoleQuota(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                        @PathVariable String role) {
        requireAdmin(null, adminUserId);
        return roleQuotaConfigRepository.findById(normalizeRole(role))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role quota not found"));
    }

    /**
     * 修改角色额度。
     *
     * <p>dailyLimit 或 monthlyLimit 传 null 表示对应周期不限。
     * concurrentRequests 当前只是保存配置，v1 暂未在网关强制执行。</p>
     */
    @Operation(summary = "修改角色额度", description = "保存角色的日/月额度、并发上限和后台说明；null 表示不限额。")
    @PutMapping("/roles/{role}/quota")
    @Transactional
    public RoleQuotaConfig updateRoleQuota(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                           @PathVariable String role,
                                           @RequestBody RoleQuotaRequest request) {
        requireAdmin(null, adminUserId);
        String normalizedRole = normalizeRole(role);
        RoleQuotaConfig config = roleQuotaConfigRepository.findById(normalizedRole)
                .orElse(RoleQuotaConfig.builder().role(normalizedRole).build());
        config.setDailyLimit(request.getDailyLimit());
        config.setMonthlyLimit(request.getMonthlyLimit());
        config.setConcurrentRequests(request.getConcurrentRequests());
        config.setDescription(request.getDescription());
        return roleQuotaConfigRepository.save(config);
    }

    /**
     * 查询角色默认可用模型。
     *
     * <p>这里不包含用户级 GRANT/DENY，也不包含企业 plan，只展示角色基础规则。</p>
     */
    @Operation(summary = "查询角色默认模型", description = "查询角色基础规则下开放的模型，不叠加用户覆盖和企业套餐。")
    @GetMapping("/roles/{role}/models")
    public List<AiModelResponse> getRoleModels(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                               @PathVariable String role) {
        requireAdmin(null, adminUserId);
        return entitlementService.listByRole(User.UserRole.valueOf(normalizeRole(role))).stream()
                .map(AiModelResponse::fromEntity)
                .toList();
    }

    /**
     * 全量替换某个角色的默认模型列表。
     *
     * <p>这是后台批量保存时最方便的接口。注意它是全量替换，不是增量追加；
     * 前端做保存前应二次确认，避免误删角色已有模型。</p>
     */
    @Operation(summary = "全量替换角色默认模型", description = "用请求中的 modelCodes 完整替换该角色的默认模型列表。")
    @PutMapping("/roles/{role}/models")
    @Transactional
    public List<AiModelResponse> replaceRoleModels(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                   @PathVariable String role,
                                                   @RequestBody ModelCodesRequest request) {
        requireAdmin(null, adminUserId);
        String normalizedRole = normalizeRole(role);
        Set<String> modelCodes = validateModelCodes(request.getModelCodes());
        roleModelDefaultRepository.deleteByRole(normalizedRole);
        roleModelDefaultRepository.saveAll(modelCodes.stream()
                .map(code -> RoleModelDefault.builder().role(normalizedRole).modelCode(code).enabled(true).source("admin").build())
                .toList());
        return getRoleModels(adminUserId, normalizedRole);
    }

    /**
     * 给某个角色单独开放一个模型。
     */
    @Operation(summary = "给角色授权模型", description = "为指定角色单独开放一个模型。")
    @PostMapping("/roles/{role}/models/{code}")
    @Transactional
    public List<AiModelResponse> grantRoleModel(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                @PathVariable String role,
                                                @PathVariable String code) {
        requireAdmin(null, adminUserId);
        findModel(code);
        String normalizedRole = normalizeRole(role);
        RoleModelDefault mapping = roleModelDefaultRepository.findByRoleAndModelCode(normalizedRole, code)
                .orElse(RoleModelDefault.builder().role(normalizedRole).modelCode(code).source("admin").build());
        mapping.setEnabled(true);
        mapping.setSource("admin");
        roleModelDefaultRepository.save(mapping);
        return getRoleModels(adminUserId, normalizedRole);
    }

    /**
     * 取消某个角色对某模型的默认授权。
     */
    @Operation(summary = "取消角色模型授权", description = "禁用指定角色和模型之间的默认授权关系。")
    @DeleteMapping("/roles/{role}/models/{code}")
    @Transactional
    public List<AiModelResponse> revokeRoleModel(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                 @PathVariable String role,
                                                 @PathVariable String code) {
        requireAdmin(null, adminUserId);
        String normalizedRole = normalizeRole(role);
        roleModelDefaultRepository.findByRoleAndEnabledTrue(normalizedRole).stream()
                .filter(item -> item.getModelCode().equals(code))
                .forEach(item -> {
                    item.setEnabled(false);
                    roleModelDefaultRepository.save(item);
                });
        return getRoleModels(adminUserId, normalizedRole);
    }

    /**
     * 查询某个模型当前开放给哪些角色。
     */
    @Operation(summary = "查询模型开放角色", description = "从模型视角查询该模型当前开放给哪些角色。")
    @GetMapping("/models/{code}/roles")
    public List<String> getModelRoles(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                      @PathVariable String code) {
        requireAdmin(null, adminUserId);
        findModel(code);
        return roleModelDefaultRepository.findByModelCodeAndEnabledTrue(code).stream().map(RoleModelDefault::getRole).toList();
    }

    /**
     * 从模型视角全量替换开放角色。
     *
     * <p>这是角色-模型映射的另一种编辑方式，适合模型详情页里选择“开放给哪些角色”。</p>
     */
    @Operation(summary = "替换模型开放角色", description = "从模型视角全量替换该模型开放给哪些角色。")
    @PutMapping("/models/{code}/roles")
    @Transactional
    public List<String> replaceModelRoles(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                          @PathVariable String code,
                                          @RequestBody RolesRequest request) {
        requireAdmin(null, adminUserId);
        findModel(code);
        Set<String> targetRoles = request.getRoles() == null ? Set.of() : request.getRoles().stream().map(this::normalizeRole).collect(Collectors.toCollection(LinkedHashSet::new));
        List<RoleModelDefault> existing = roleModelDefaultRepository.findByRoleInAndEnabledTrue(List.of(User.UserRole.values()).stream().map(Enum::name).toList());
        for (RoleModelDefault item : existing) {
            if (item.getModelCode().equals(code) && !targetRoles.contains(item.getRole())) {
                item.setEnabled(false);
                roleModelDefaultRepository.save(item);
            }
        }
        for (String role : targetRoles) {
            grantRoleModel(adminUserId, role, code);
        }
        return getModelRoles(adminUserId, code);
    }

    /**
     * 用户列表，支持关键词、角色和分页筛选。
     */
    @Operation(summary = "查询用户列表", description = "支持关键词、角色和分页筛选，并返回用户额度快照。")
    @GetMapping("/users")
    public Page<UserAdminView> listUsers(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) User.UserRole role,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        requireAdmin(null, adminUserId);
        Pageable pageable = PageRequest.of(page, size);
        return userRepository.searchAdminUsers(blankToNull(q), role == null ? null : role.name(), pageable)
                .map(user -> userView(user, null));
    }

    /**
     * 用户详情。
     *
     * <p>返回用户基础信息、日/月配额、最近调用、模型覆盖和配额覆盖，
     * 前端可以直接用它组装用户详情页的多个 Tab。</p>
     */
    @Operation(summary = "查询用户详情", description = "返回用户基础信息、额度、最近调用、模型覆盖和额度覆盖。")
    @GetMapping("/users/{userId}")
    public Map<String, Object> getUser(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                       @PathVariable String userId) {
        requireAdmin(null, adminUserId);
        User user = findUser(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", userView(user, null));
        body.put("dailyQuota", quotaService.getSnapshot(userId, null, "DAILY"));
        body.put("monthlyQuota", quotaService.getSnapshot(userId, null, "MONTHLY"));
        body.put("recentUsage", aiUsageEventRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).getContent());
        body.put("modelOverrides", userModelPermissionRepository.findByUserId(userId));
        body.put("quotaOverrides", userQuotaOverrideRepository.findByUserIdOrderByCreatedAtDesc(userId));
        return body;
    }

    /**
     * 修改用户平台角色，例如 USER 升级为 PRO。
     */
    @Operation(summary = "修改用户角色", description = "修改用户平台角色，例如 USER、PRO、PLUS、VIP、ADMIN。")
    @PatchMapping("/users/{userId}/role")
    @Transactional
    public UserAdminView updateUserRole(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                        @PathVariable String userId,
                                        @RequestBody RoleRequest request) {
        requireAdmin(null, adminUserId);
        User user = findUser(userId);
        user.setUserRole(User.UserRole.valueOf(normalizeRole(request.getRole())));
        return userView(userRepository.save(user), null);
    }

    /**
     * 修改用户状态，例如封禁、恢复、停用。
     */
    @Operation(summary = "修改用户状态", description = "修改用户状态，例如 ACTIVE、BANNED、SUSPENDED、DELETED。")
    @PatchMapping("/users/{userId}/status")
    @Transactional
    public UserAdminView updateUserStatus(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                          @PathVariable String userId,
                                          @RequestBody StatusRequest request) {
        requireAdmin(null, adminUserId);
        User user = findUser(userId);
        user.setUserStatus(User.UserStatus.valueOf(required(request.getStatus(), "status").toUpperCase(Locale.ROOT)));
        return userView(userRepository.save(user), null);
    }

    /**
     * 新增或更新用户模型覆盖。
     *
     * <p>同一用户、同一 workspace、同一模型只保留一条覆盖。
     * overrideType 为 GRANT 时额外开放模型，为 DENY 时明确禁止模型。</p>
     */
    @Operation(summary = "新增或更新用户模型覆盖", description = "为用户增加 GRANT/DENY 模型覆盖，可限定 workspace 和过期时间。")
    @PostMapping("/users/{userId}/overrides/models")
    @Transactional
    public List<UserModelPermission> addUserModelOverride(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                          @PathVariable String userId,
                                                          @RequestBody ModelOverrideRequest request) {
        requireAdmin(null, adminUserId);
        findUser(userId);
        findModel(required(request.getModelCode(), "modelCode"));
        String workspaceId = blankToNull(request.getWorkspaceId());
        UserModelPermission permission = workspaceId == null
                ? userModelPermissionRepository.findByUserIdAndWorkspaceIdIsNullAndModelCode(userId, request.getModelCode()).orElse(new UserModelPermission())
                : userModelPermissionRepository.findByUserIdAndWorkspaceIdAndModelCode(userId, workspaceId, request.getModelCode()).orElse(new UserModelPermission());
        permission.setUserId(userId);
        permission.setWorkspaceId(workspaceId);
        permission.setModelCode(request.getModelCode());
        permission.setOverrideType(defaultString(request.getOverrideType(), "GRANT").toUpperCase(Locale.ROOT));
        permission.setEnabled(request.getEnabled() == null || request.getEnabled());
        permission.setReason(request.getReason());
        permission.setExpiresAt(request.getExpiresAt());
        userModelPermissionRepository.save(permission);
        return userModelPermissionRepository.findByUserId(userId);
    }

    /**
     * 删除用户模型覆盖。
     */
    @Operation(summary = "删除用户模型覆盖", description = "删除指定用户的一条模型 GRANT/DENY 覆盖。")
    @DeleteMapping("/users/{userId}/overrides/models/{overrideId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserModelOverride(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                        @PathVariable String userId,
                                        @PathVariable Long overrideId) {
        requireAdmin(null, adminUserId);
        UserModelPermission permission = userModelPermissionRepository.findById(overrideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "override not found"));
        if (!permission.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "override belongs to another user");
        }
        userModelPermissionRepository.delete(permission);
    }

    /**
     * 新增用户配额增量覆盖。
     *
     * <p>dailyDelta/monthlyDelta 是增量，不是最终值。正数增加额度，负数减少额度。</p>
     */
    @Operation(summary = "新增用户额度覆盖", description = "新增用户日/月额度增量覆盖，正数增加额度，负数减少额度。")
    @PostMapping("/users/{userId}/overrides/quota")
    @Transactional
    public List<UserQuotaOverride> addUserQuotaOverride(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                        @PathVariable String userId,
                                                        @RequestBody QuotaOverrideRequest request) {
        requireAdmin(null, adminUserId);
        findUser(userId);
        UserQuotaOverride override = UserQuotaOverride.builder()
                .userId(userId)
                .workspaceId(blankToNull(request.getWorkspaceId()))
                .dailyDelta(nullToZero(request.getDailyDelta()))
                .monthlyDelta(nullToZero(request.getMonthlyDelta()))
                .reason(request.getReason())
                .expiresAt(request.getExpiresAt())
                .build();
        userQuotaOverrideRepository.save(override);
        return userQuotaOverrideRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 删除用户配额增量覆盖。
     */
    @Operation(summary = "删除用户额度覆盖", description = "删除指定用户的一条额度增量覆盖。")
    @DeleteMapping("/users/{userId}/overrides/quota/{overrideId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserQuotaOverride(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                        @PathVariable String userId,
                                        @PathVariable Long overrideId) {
        requireAdmin(null, adminUserId);
        UserQuotaOverride override = userQuotaOverrideRepository.findById(overrideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "override not found"));
        if (!override.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "override belongs to another user");
        }
        userQuotaOverrideRepository.delete(override);
    }

    /**
     * 查询用户在某 workspace 下最终可用模型，已经叠加角色/套餐和用户覆盖。
     */
    @Operation(summary = "查询用户最终可用模型", description = "叠加角色或套餐规则、用户 GRANT/DENY 覆盖后，返回用户最终可用模型。")
    @GetMapping("/users/{userId}/effective-models")
    public List<AiModelResponse> getEffectiveModels(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                    @PathVariable String userId,
                                                    @RequestParam(required = false) String workspaceId) {
        requireAdmin(null, adminUserId);
        return entitlementService.listAccessibleModels(userId, workspaceId).stream().map(AiModelResponse::fromEntity).toList();
    }

    /**
     * 查询用户在某 workspace 下最终日/月配额，已经叠加用户额度 delta。
     */
    @Operation(summary = "查询用户最终额度", description = "叠加角色或套餐额度、用户额度覆盖后，返回日/月最终额度快照。")
    @GetMapping("/users/{userId}/effective-quota")
    public Map<String, QuotaSnapshot> getEffectiveQuota(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                        @PathVariable String userId,
                                                        @RequestParam(required = false) String workspaceId) {
        requireAdmin(null, adminUserId);
        return Map.of(
                "daily", quotaService.getSnapshot(userId, workspaceId, "DAILY"),
                "monthly", quotaService.getSnapshot(userId, workspaceId, "MONTHLY")
        );
    }

    /**
     * workspace 列表，支持名称/ID 搜索、套餐过滤和分页。
     */
    @Operation(summary = "查询工作空间列表", description = "支持名称/ID 关键词、套餐编码和分页筛选。")
    @GetMapping("/workspaces")
    public Page<Workspace> listWorkspaces(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String planCode,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        requireAdmin(null, adminUserId);
        return workspaceRepository.searchAdminWorkspaces(blankToNull(q), blankToNull(planCode), PageRequest.of(page, size));
    }

    /**
     * 查询 workspace 详情。
     *
     * <p>列表接口只返回 workspace 基础字段；详情页需要成员、套餐权益和最近用量，
     * 所以这里单独提供一个聚合视图，避免前端再用搜索接口猜一遍。</p>
     */
    @Operation(summary = "查询工作空间详情", description = "返回 workspace、成员、套餐、套餐权益和最近调用。")
    @GetMapping("/workspaces/{id}")
    public Map<String, Object> getWorkspace(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                            @PathVariable String id) {
        requireAdmin(null, adminUserId);
        Workspace workspace = workspaceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace not found"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace", workspace);
        body.put("members", workspaceMemberRepository.findByWorkspaceIdAndStatus(id, "ACTIVE"));
        body.put("plan", StringUtils.hasText(workspace.getPlanCode()) ? planRepository.findById(workspace.getPlanCode()).orElse(null) : null);
        body.put("planEntitlements", StringUtils.hasText(workspace.getPlanCode()) ? planEntitlementRepository.findByPlanCodeAndEnabledTrue(workspace.getPlanCode()) : List.of());
        body.put("recentUsage", aiUsageEventRepository.search(null, id, null, null, null, null, PageRequest.of(0, 20)).getContent());
        return body;
    }

    /**
     * 创建企业 workspace。
     *
     * <p>如果请求里的 orgId 不存在，会自动创建 organization。
     * 创建完成后会把 ownerUserId 加为 OWNER 成员。</p>
     */
    @Operation(summary = "创建工作空间", description = "创建企业 workspace，必要时自动创建 organization，并把 ownerUserId 加为 OWNER。")
    @PostMapping("/workspaces")
    @Transactional
    public Workspace createWorkspace(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                     @RequestBody WorkspaceRequest request) {
        requireAdmin(null, adminUserId);
        String ownerUserId = required(request.getOwnerUserId(), "ownerUserId");
        findUser(ownerUserId);
        String orgId = StringUtils.hasText(request.getOrgId()) ? request.getOrgId() : UUID.randomUUID().toString();
        organizationRepository.findById(orgId).orElseGet(() -> organizationRepository.save(Organization.builder()
                .orgId(orgId)
                .name(defaultString(request.getOrgName(), request.getName()))
                .status("ACTIVE")
                .build()));
        Workspace workspace = Workspace.builder()
                .workspaceId(StringUtils.hasText(request.getWorkspaceId()) ? request.getWorkspaceId() : UUID.randomUUID().toString())
                .orgId(orgId)
                .name(required(request.getName(), "name"))
                .planCode(blankToNull(request.getPlanCode()))
                .status(defaultString(request.getStatus(), "ACTIVE"))
                .build();
        workspace = workspaceRepository.save(workspace);
        workspaceMemberRepository.save(WorkspaceMember.builder()
                .workspaceId(workspace.getWorkspaceId())
                .userId(ownerUserId)
                .memberRole("OWNER")
                .status("ACTIVE")
                .build());
        return workspace;
    }

    /**
     * 更新 workspace 名称、套餐或状态。
     */
    @Operation(summary = "更新工作空间", description = "更新 workspace 名称、套餐编码或状态。")
    @PatchMapping("/workspaces/{id}")
    @Transactional
    public Workspace updateWorkspace(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                     @PathVariable String id,
                                     @RequestBody WorkspaceRequest request) {
        requireAdmin(null, adminUserId);
        Workspace workspace = workspaceRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace not found"));
        if (request.getName() != null) workspace.setName(request.getName());
        if (request.getPlanCode() != null) workspace.setPlanCode(blankToNull(request.getPlanCode()));
        if (request.getStatus() != null) workspace.setStatus(request.getStatus());
        return workspaceRepository.save(workspace);
    }

    /**
     * 添加或恢复 workspace 成员。
     */
    @Operation(summary = "添加工作空间成员", description = "添加或恢复 workspace 成员，并设置空间内角色。")
    @PostMapping("/workspaces/{id}/members")
    @Transactional
    public List<WorkspaceMember> addWorkspaceMember(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                    @PathVariable String id,
                                                    @RequestBody WorkspaceMemberRequest request) {
        requireAdmin(null, adminUserId);
        workspaceRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace not found"));
        findUser(required(request.getUserId(), "userId"));
        WorkspaceMember member = workspaceMemberRepository.findByWorkspaceIdAndUserIdAndStatus(id, request.getUserId(), "ACTIVE")
                .orElse(WorkspaceMember.builder().workspaceId(id).userId(request.getUserId()).build());
        member.setMemberRole(defaultString(request.getMemberRole(), "MEMBER"));
        member.setStatus("ACTIVE");
        workspaceMemberRepository.save(member);
        return workspaceMemberRepository.findByWorkspaceIdAndStatus(id, "ACTIVE");
    }

    /**
     * 移除 workspace 成员。当前实现为删除成员关系记录。
     */
    @Operation(summary = "移除工作空间成员", description = "删除 workspace 成员关系。")
    @DeleteMapping("/workspaces/{id}/members/{userId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeWorkspaceMember(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                      @PathVariable String id,
                                      @PathVariable String userId) {
        requireAdmin(null, adminUserId);
        workspaceMemberRepository.deleteByWorkspaceIdAndUserId(id, userId);
    }

    /**
     * 查询全部套餐。
     */
    @Operation(summary = "查询套餐列表", description = "返回全部套餐，按 tier 升序。")
    @GetMapping("/plans")
    public List<Plan> listPlans(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId) {
        requireAdmin(null, adminUserId);
        return planRepository.findAll(Sort.by("tier").ascending());
    }

    /**
     * 创建或覆盖保存套餐基础信息。
     */
    @Operation(summary = "创建或保存套餐", description = "按 planCode 创建或覆盖保存套餐基础信息。")
    @PostMapping("/plans")
    @Transactional
    public Plan createPlan(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                           @RequestBody Plan request) {
        requireAdmin(null, adminUserId);
        if (!StringUtils.hasText(request.getPlanCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planCode is required");
        }
        request.setEnabled(request.getEnabled() == null || request.getEnabled());
        return planRepository.save(request);
    }

    /**
     * 查询套餐已启用的权益列表。
     */
    @Operation(summary = "查询套餐权益", description = "返回指定套餐当前启用的权益配置。")
    @GetMapping("/plans/{code}/entitlements")
    public List<PlanEntitlement> listPlanEntitlements(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                      @PathVariable String code) {
        requireAdmin(null, adminUserId);
        return planEntitlementRepository.findByPlanCodeAndEnabledTrue(code);
    }

    /**
     * 新增或更新套餐权益。
     *
     * <p>同一个 planCode + entitlementType + entitlementKey 只保留一条记录。
     * 常见类型包括 ALLOWED_MODEL、ALLOWED_PROVIDER、LIMIT。</p>
     */
    @Operation(summary = "新增或更新套餐权益", description = "按 planCode、entitlementType、entitlementKey 新增或更新套餐权益。")
    @PostMapping("/plans/{code}/entitlements")
    @Transactional
    public PlanEntitlement upsertPlanEntitlement(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                 @PathVariable String code,
                                                 @RequestBody PlanEntitlementRequest request) {
        requireAdmin(null, adminUserId);
        planRepository.findById(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "plan not found"));
        PlanEntitlement.EntitlementType type = PlanEntitlement.EntitlementType.valueOf(required(request.getEntitlementType(), "entitlementType").toUpperCase(Locale.ROOT));
        PlanEntitlement entitlement = planEntitlementRepository
                .findByPlanCodeAndEntitlementTypeAndEntitlementKey(code, type, required(request.getEntitlementKey(), "entitlementKey"))
                .orElse(PlanEntitlement.builder().planCode(code).entitlementType(type).entitlementKey(request.getEntitlementKey()).build());
        entitlement.setValueText(request.getValueText());
        entitlement.setValueNumber(request.getValueNumber());
        entitlement.setEnabled(request.getEnabled() == null || request.getEnabled());
        return planEntitlementRepository.save(entitlement);
    }

    /**
     * 软删除套餐权益，把 enabled 设置为 false。
     */
    @Operation(summary = "禁用套餐权益", description = "将指定套餐权益 enabled 置为 false。")
    @DeleteMapping("/plans/{code}/entitlements/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlanEntitlement(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                      @PathVariable String code,
                                      @PathVariable Long id) {
        requireAdmin(null, adminUserId);
        PlanEntitlement entitlement = planEntitlementRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "entitlement not found"));
        if (!entitlement.getPlanCode().equals(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entitlement belongs to another plan");
        }
        entitlement.setEnabled(false);
        planEntitlementRepository.save(entitlement);
    }

    /**
     * 查询调用流水。
     *
     * <p>支持按用户、workspace、模型、状态和时间范围筛选。
     * 这个接口适合后台“调用明细”页面使用。</p>
     */
    @Operation(summary = "查询调用流水", description = "按用户、workspace、模型、状态和时间范围筛选调用流水。")
    @GetMapping("/usage/events")
    public Page<AiUsageEvent> searchUsageEvents(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                @RequestParam(required = false) String userId,
                                                @RequestParam(required = false) String workspaceId,
                                                @RequestParam(required = false) String modelCode,
                                                @RequestParam(required = false) AiUsageEvent.Status status,
                                                @RequestParam(required = false) LocalDateTime from,
                                                @RequestParam(required = false) LocalDateTime to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        requireAdmin(null, adminUserId);
        return aiUsageEventRepository.search(blankToNull(userId), blankToNull(workspaceId), blankToNull(modelCode),
                status == null ? null : status.name(), from, to, PageRequest.of(page, size));
    }

    /**
     * 聚合用量。
     *
     * <p>groupBy 支持 user、workspace、provider、model。聚合在数据库里完成，
     * 避免把大量流水拉到 JVM 再 group。</p>
     */
    @Operation(summary = "查询用量聚合", description = "按 user、workspace、provider、model 等维度聚合调用、Token、标准 Token、成本和失败量。")
    @GetMapping("/usage/aggregate")
    public List<Map<String, Object>> aggregateUsage(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                    @RequestParam(defaultValue = "model") String groupBy,
                                                    @RequestParam(required = false) LocalDateTime from,
                                                    @RequestParam(required = false) LocalDateTime to) {
        requireAdmin(null, adminUserId);
        return aiUsageEventRepository.aggregate(groupBy, from, to).stream()
                .map(row -> Map.<String, Object>of(
                        "dimension", row[0],
                        "calls", row[1],
                        "tokens", row[2],
                        "cost", row[3],
                        "standardTokens", row[4],
                        "failedCalls", row[5],
                        "blockedCalls", row[6],
                        "successCalls", row[7]))
                .toList();
    }

    /**
     * 用量 TopN，支持按 cost 或 tokens 排序。
     */
    @Operation(summary = "查询用量 TopN", description = "按 cost、tokens 或 standardTokens 指标返回指定维度的 TopN。")
    @GetMapping("/usage/topN")
    public List<Map<String, Object>> topNUsage(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                               @RequestParam(defaultValue = "model") String dimension,
                                               @RequestParam(defaultValue = "cost") String metric,
                                               @RequestParam(required = false) LocalDateTime from,
                                               @RequestParam(required = false) LocalDateTime to,
                                               @RequestParam(defaultValue = "10") int n) {
        requireAdmin(null, adminUserId);
        String metricKey = switch (metric.toLowerCase(Locale.ROOT)) {
            case "tokens" -> "tokens";
            case "standardtokens", "standard_tokens", "quota" -> "standardTokens";
            default -> "cost";
        };
        Comparator<Map<String, Object>> comparator = Comparator.comparing(item -> new BigDecimal(String.valueOf(item.get(metricKey))));
        return aggregateUsage(adminUserId, dimension, from, to).stream()
                .sorted(comparator.reversed())
                .limit(n)
                .toList();
    }

    /**
     * 角色额度状态概览。
     *
     * <p>用于后台监控页快速展示各角色用户数、模型数和额度配置。
     * 当前返回的是配置概览，不是每个用户的实时已用分布。</p>
     */
    @Operation(summary = "查询角色额度状态", description = "返回各角色用户数、模型数、金额额度和标准 Token 额度。")
    @GetMapping("/usage/quota-status")
    public List<Map<String, Object>> quotaStatus(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                 @RequestParam(required = false) User.UserRole role) {
        requireAdmin(null, adminUserId);
        return List.of(role == null ? User.UserRole.values() : new User.UserRole[]{role}).stream()
                .map(currentRole -> {
                    RoleQuotaConfig quota = roleQuotaConfigRepository.findById(currentRole.name()).orElse(null);
                    long userCount = userRepository.countByUserRole(currentRole);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("role", currentRole.name());
                    body.put("userCount", userCount);
                    body.put("dailyLimit", quota == null ? null : quota.getDailyLimit());
                    body.put("monthlyLimit", quota == null ? null : quota.getMonthlyLimit());
                    body.put("dailyTokenLimit", quota == null ? null : quotaService.moneyToStandardTokens(quota.getDailyLimit()));
                    body.put("monthlyTokenLimit", quota == null ? null : quotaService.moneyToStandardTokens(quota.getMonthlyLimit()));
                    body.put("unlimited", quota == null || (quota.getDailyLimit() == null && quota.getMonthlyLimit() == null));
                    body.put("modelCount", roleModelDefaultRepository.findByRoleAndEnabledTrue(currentRole.name()).size());
                    return body;
                })
                .toList();
    }

    /** 查询单个用户的调用时间线。 */
    /**
     * RAG 文件、用户和摄取状态监控总览。
     *
     * <p>这个接口面向后台监控页，不参与聊天主流程。它把文档数、chunk 数、文件状态分布、
     * 用户文件分布和最近摄取事件一次性返回，前端可以直接画表格和图表。</p>
     */
    @Operation(summary = "查询 RAG 监控总览", description = "返回 RAG 文档、chunk、Token 估算、状态分布、用户文件分布和最近摄取事件。")
    @GetMapping("/rag/overview")
    public Map<String, Object> ragOverview(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId) {
        requireAdmin(null, adminUserId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentCount", ragDocumentRepository.count());
        body.put("chunkCount", ragDocumentChunkRepository.countAllChunks());
        body.put("estimatedTokens", nullToZeroLong(ragDocumentChunkRepository.sumTokenEstimate()));
        body.put("declaredChunkCount", nullToZeroLong(ragDocumentRepository.sumChunkCount()));
        body.put("documentStatus", toDimensionRows(ragDocumentRepository.aggregateByStatus(), "count"));
        body.put("eventFileStatus", toDimensionRows(ragIngestionEventRepository.aggregateByFileStatus(), "count"));
        body.put("eventRagStatus", toDimensionRows(ragIngestionEventRepository.aggregateByRagStatus(), "count"));
        body.put("ownerUsage", ragDocumentRepository.aggregateByOwner().stream()
                .map(row -> Map.<String, Object>of("dimension", row[0], "documents", row[1], "bytes", row[2]))
                .toList());
        body.put("recentEvents", ragIngestionEventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 20)).getContent());
        return body;
    }

    /**
     * RAG 文档列表。
     *
     * <p>支持按文件名/objectKey、用户目录、session、fileId 和状态过滤，方便定位“某个用户上传的某个文件”
     * 是否已经完成解析、分块和索引。</p>
     */
    @Operation(summary = "查询 RAG 文档列表", description = "按文件名、用户目录、session、fileId 和状态筛选 RAG 文档。")
    @GetMapping("/rag/documents")
    public Page<RagDocument> searchRagDocuments(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(required = false) String ownerFolder,
                                                @RequestParam(required = false) String sessionId,
                                                @RequestParam(required = false) String fileId,
                                                @RequestParam(required = false) RagDocument.Status status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        requireAdmin(null, adminUserId);
        return ragDocumentRepository.searchAdminDocuments(
                blankToNull(q),
                blankToNull(ownerFolder),
                blankToNull(sessionId),
                blankToNull(fileId),
                status == null ? null : status.name(),
                PageRequest.of(page, size)
        );
    }

    @Operation(summary = "查询用户调用时间线", description = "按时间倒序返回单个用户的调用流水，用于用户详情页时间线。")
    @GetMapping("/usage/users/{userId}/timeline")
    public Page<AiUsageEvent> userUsageTimeline(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                @PathVariable String userId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "100") int size) {
        requireAdmin(null, adminUserId);
        return aiUsageEventRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    // ============================================================
    // Provider 注册表（模型提供商配置 + 加密 API Key），供后台维护。
    // API Key 写入即用 AES-GCM 加密；任何查询只回掩码，绝不回明文 / 密文。
    // ============================================================

    @Operation(summary = "查询 Provider 列表", description = "返回全部模型提供商配置；API Key 只回掩码。")
    @GetMapping("/providers")
    public List<ProviderAdminView> listProviders(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId) {
        requireAdmin(null, adminUserId);
        return aiProviderRepository.findAll(Sort.by("providerCode").ascending())
                .stream().map(this::providerView).toList();
    }

    @Operation(summary = "查询 Provider 详情", description = "返回单个 provider 配置；API Key 只回掩码。")
    @GetMapping("/providers/{code}")
    public ProviderAdminView getProvider(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                         @PathVariable String code) {
        requireAdmin(null, adminUserId);
        return providerView(findProvider(code));
    }

    @Operation(summary = "新建 Provider", description = "创建模型提供商配置；如带 apiKey 会加密后落库。")
    @PostMapping("/providers")
    @Transactional
    public ProviderAdminView createProvider(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                            @RequestBody ProviderUpsertRequest request) {
        requireAdmin(null, adminUserId);
        String code = required(request.getProviderCode(), "providerCode");
        if (aiProviderRepository.existsById(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "provider already exists");
        }
        AiProvider provider = AiProvider.builder()
                .providerCode(code)
                .displayName(defaultString(request.getDisplayName(), code))
                .providerType(request.getProviderType() == null
                        ? AiProviderProperties.ProviderType.OPENAI_COMPATIBLE : request.getProviderType())
                .baseUrl(required(request.getBaseUrl(), "baseUrl"))
                .chatCompletionsPath(defaultString(request.getChatCompletionsPath(), "/v1/chat/completions"))
                .useApiKey(request.getUseApiKey() == null || request.getUseApiKey())
                .apiKeyCipher(StringUtils.hasText(request.getApiKey())
                        ? secretCipher.encryptSecret(request.getApiKey()) : null)
                .apiKeyHeader(defaultString(request.getApiKeyHeader(), "Authorization"))
                .apiKeyPrefix(request.getApiKeyPrefix())
                .enabled(request.getEnabled() == null || request.getEnabled())
                .build();
        return providerView(aiProviderRepository.save(provider));
    }

    @Operation(summary = "更新 Provider", description = "更新 provider 配置；apiKey 留空表示不修改，传入新值则重新加密。")
    @PatchMapping("/providers/{code}")
    @Transactional
    public ProviderAdminView updateProvider(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                            @PathVariable String code,
                                            @RequestBody ProviderUpsertRequest request) {
        requireAdmin(null, adminUserId);
        AiProvider provider = findProvider(code);
        if (request.getDisplayName() != null) provider.setDisplayName(request.getDisplayName());
        if (request.getProviderType() != null) provider.setProviderType(request.getProviderType());
        if (request.getBaseUrl() != null) provider.setBaseUrl(request.getBaseUrl());
        if (request.getChatCompletionsPath() != null) provider.setChatCompletionsPath(request.getChatCompletionsPath());
        if (request.getUseApiKey() != null) provider.setUseApiKey(request.getUseApiKey());
        if (request.getApiKeyHeader() != null) provider.setApiKeyHeader(request.getApiKeyHeader());
        if (request.getApiKeyPrefix() != null) provider.setApiKeyPrefix(request.getApiKeyPrefix());
        if (request.getEnabled() != null) provider.setEnabled(request.getEnabled());
        // 仅当传入非空 apiKey 时才覆盖并重新加密；留空 = 保持原 key 不变。
        if (StringUtils.hasText(request.getApiKey())) {
            provider.setApiKeyCipher(secretCipher.encryptSecret(request.getApiKey()));
        }
        return providerView(aiProviderRepository.save(provider));
    }

    @Operation(summary = "启用或禁用 Provider", description = "切换 provider enabled 状态。")
    @PatchMapping("/providers/{code}/enabled")
    @Transactional
    public ProviderAdminView setProviderEnabled(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                                                @PathVariable String code,
                                                @RequestBody EnabledRequest request) {
        requireAdmin(null, adminUserId);
        AiProvider provider = findProvider(code);
        provider.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        return providerView(aiProviderRepository.save(provider));
    }

    @Operation(summary = "删除 Provider", description = "物理删除 provider；若仍有模型引用则拒绝，避免运行期解析失败。")
    @DeleteMapping("/providers/{code}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProvider(@RequestHeader(value = USER_ID_HEADER, required = false) String adminUserId,
                               @PathVariable String code) {
        requireAdmin(null, adminUserId);
        findProvider(code);
        long refs = aiModelDefinitionRepository.countByProviderCode(code);
        if (refs > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "仍有 " + refs + " 个模型引用该 provider，请先改用其它 provider 再删除");
        }
        aiProviderRepository.deleteById(code);
    }

    private AiProvider findProvider(String code) {
        return aiProviderRepository.findById(required(code, "providerCode"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    }

    private ProviderAdminView providerView(AiProvider provider) {
        return new ProviderAdminView(provider, maskApiKey(provider.getApiKeyCipher()));
    }

    /** 把密文解密后做掩码（前 3 + **** + 后 4）；仅用于后台展示，绝不返回明文。 */
    private String maskApiKey(String cipher) {
        if (!StringUtils.hasText(cipher)) {
            return null;
        }
        String plain;
        try {
            plain = secretCipher.decryptSecret(cipher);
        } catch (Exception ex) {
            return "********";
        }
        if (!StringUtils.hasText(plain)) {
            return null;
        }
        if (plain.length() <= 8) {
            return "********";
        }
        return plain.substring(0, 3) + "****" + plain.substring(plain.length() - 4);
    }

    private User requireAdmin(String authorization, String adminUserId) {
        return adminAuthService.requireAdmin(defaultString(authorization, currentAuthorizationHeader()), adminUserId);
    }

    private String currentAuthorizationHeader() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader(AUTHORIZATION_HEADER);
        }
        return null;
    }

    private User findUser(String userId) {
        return userRepository.findById(required(userId, "userId"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    private AiModelDefinition findModel(String modelCode) {
        return aiModelDefinitionRepository.findById(required(modelCode, "modelCode"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "model not found"));
    }

    private ModelAdminView modelView(AiModelDefinition model, LocalDateTime since) {
        AiModelPricing pricing = aiModelPricingRepository.findActivePricing(model.getModelCode(), LocalDateTime.now()).orElse(null);
        return new ModelAdminView(AiModelResponse.fromEntity(model), model.getEnabled(), pricing,
                aiUsageEventRepository.countByModelCodeAndCreatedAtAfter(model.getModelCode(), since));
    }

    private UserAdminView userView(User user, String workspaceId) {
        QuotaSnapshot daily = quotaService.getSnapshot(user.getUserId(), workspaceId, "DAILY");
        QuotaSnapshot monthly = quotaService.getSnapshot(user.getUserId(), workspaceId, "MONTHLY");
        return new UserAdminView(user, daily, monthly);
    }

    private AiModelPricing savePricing(String modelCode, PricingRequest request) {
        return aiModelPricingRepository.save(AiModelPricing.builder()
                .modelCode(modelCode)
                .currency(defaultString(request.getCurrency(), "CNY"))
                .promptPricePerMillion(requiredNumber(request.getPromptPricePerMillion(), "promptPricePerMillion"))
                .completionPricePerMillion(requiredNumber(request.getCompletionPricePerMillion(), "completionPricePerMillion"))
                .cachedInputPricePerMillion(request.getCachedInputPricePerMillion())
                .requestSurcharge(nullToZero(request.getRequestSurcharge()))
                .markupRatio(request.getMarkupRatio() == null ? BigDecimal.ONE : request.getMarkupRatio())
                .effectiveFrom(request.getEffectiveFrom() == null ? LocalDateTime.now() : request.getEffectiveFrom())
                .effectiveTo(request.getEffectiveTo())
                .enabled(request.getEnabled() == null || request.getEnabled())
                .build());
    }

    private Set<String> validateModelCodes(List<String> modelCodes) {
        Set<String> targetCodes = modelCodes == null ? Set.of() : modelCodes.stream().filter(StringUtils::hasText).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> found = aiModelDefinitionRepository.findByModelCodeInAndEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc(targetCodes)
                .stream().map(AiModelDefinition::getModelCode).collect(Collectors.toSet());
        if (!found.containsAll(targetCodes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "some models are missing or disabled");
        }
        return targetCodes;
    }

    private String normalizeRole(String role) {
        return User.UserRole.valueOf(required(role, "role").toUpperCase(Locale.ROOT)).name();
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    private BigDecimal requiredNumber(BigDecimal value, String field) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Long nullToZeroLong(Long value) {
        return value == null ? 0L : value;
    }

    private List<Map<String, Object>> toDimensionRows(List<Object[]> rows, String valueKey) {
        return rows.stream()
                .map(row -> Map.<String, Object>of(
                        "dimension", row[0],
                        valueKey, row[1]))
                .toList();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private String defaultString(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    /**
     * 模型列表/详情页的聚合视图。
     *
     * @param model         模型基础信息
     * @param enabled       是否启用
     * @param activePricing 当前生效价格；没有配置价格时为空
     * @param last24hCalls  最近 24 小时调用次数
     */
    public record ModelAdminView(AiModelResponse model, Boolean enabled, AiModelPricing activePricing,
                                 long last24hCalls) {
    }

    /**
     * 角色后台概览。
     *
     * @param role       角色名
     * @param quota      角色额度配置
     * @param modelCount 当前默认开放模型数量
     * @param userCount  当前属于该角色的用户数
     */
    public record RoleAdminView(String role, RoleQuotaConfig quota, long modelCount, long userCount) {
    }

    /**
     * 用户后台概览。
     *
     * @param user         用户基础信息
     * @param dailyQuota   日额度快照
     * @param monthlyQuota 月额度快照
     */
    public record UserAdminView(User user, QuotaSnapshot dailyQuota, QuotaSnapshot monthlyQuota) {
    }

    /**
     * 后台管理员登录请求体。
     */
    @Data
    public static class AdminLoginRequest {
        /**
         * 管理员用户名，也就是 users.username。
         */
        private String username;
        /**
         * 管理员密码。当前复用普通用户登录的密码校验逻辑。
         */
        private String password;
    }

    /**
     * 新建或更新模型的请求体。
     */
    @Data
    public static class ModelUpsertRequest {
        /**
         * 模型编码，对外稳定标识，例如 deepseek-chat。
         */
        private String modelCode;
        /**
         * 展示名称。为空时默认使用 modelCode。
         */
        private String displayName;
        /**
         * provider 编码，对应 application.yml 里的 provider 配置。
         */
        private String providerCode;
        /**
         * 上游真实模型名。为空时默认使用 modelCode。
         */
        private String apiModelName;
        /**
         * 模型层级，数字越小通常越高阶。
         */
        private Integer level;
        /**
         * 同层级内排序分数，越高越靠前。
         */
        private Integer score;
        /**
         * 是否本地模型。
         */
        private Boolean localModel;
        /**
         * 是否支持流式输出。
         */
        private Boolean supportsStream;
        /**
         * 是否启用。
         */
        private Boolean enabled;
        /**
         * 模型说明。
         */
        private String description;
        /**
         * 可选的首条价格配置。
         */
        private PricingRequest pricing;
    }

    /**
     * 新增价格快照的请求体。
     */
    @Data
    public static class PricingRequest {
        /**
         * 币种，默认 CNY。
         */
        private String currency;
        /**
         * 输入 token 单价，单位为每 100 万 token。
         */
        private BigDecimal promptPricePerMillion;
        /**
         * 输出 token 单价，单位为每 100 万 token。
         */
        private BigDecimal completionPricePerMillion;
        /**
         * 缓存输入 token 单价，单位为每 100 万 token。
         */
        private BigDecimal cachedInputPricePerMillion;
        /**
         * 单次请求固定附加费。
         */
        private BigDecimal requestSurcharge;
        /**
         * 平台加价倍率。
         */
        private BigDecimal markupRatio;
        /**
         * 生效开始时间。为空时使用当前时间。
         */
        private LocalDateTime effectiveFrom;
        /**
         * 生效结束时间。通常由后端在新增下一条价格时自动补。
         */
        private LocalDateTime effectiveTo;
        /**
         * 是否启用该价格。
         */
        private Boolean enabled;

        static PricingRequest from(AiModelPricing pricing) {
            PricingRequest request = new PricingRequest();
            request.setCurrency(pricing.getCurrency());
            request.setPromptPricePerMillion(pricing.getPromptPricePerMillion());
            request.setCompletionPricePerMillion(pricing.getCompletionPricePerMillion());
            request.setCachedInputPricePerMillion(pricing.getCachedInputPricePerMillion());
            request.setRequestSurcharge(pricing.getRequestSurcharge());
            request.setMarkupRatio(pricing.getMarkupRatio());
            request.setEnabled(pricing.getEnabled());
            return request;
        }
    }

    /**
     * 快捷调整加价倍率的请求体。
     */
    @Data
    public static class MarkupRequest {
        private BigDecimal markupRatio;
    }

    /**
     * 启用/禁用请求体。
     */
    @Data
    public static class EnabledRequest {
        private Boolean enabled;
    }

    /**
     * Provider 后台视图：包含全部配置字段，但<b>绝不</b>包含明文 / 密文 API Key，
     * 只回 {@code hasApiKey} 与掩码 {@code apiKeyMasked}。
     */
    @Data
    public static class ProviderAdminView {
        private final String providerCode;
        private final String displayName;
        private final AiProviderProperties.ProviderType providerType;
        private final String baseUrl;
        private final String chatCompletionsPath;
        private final Boolean useApiKey;
        private final boolean hasApiKey;
        private final String apiKeyMasked;
        private final String apiKeyHeader;
        private final String apiKeyPrefix;
        private final Boolean enabled;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;

        ProviderAdminView(AiProvider p, String apiKeyMasked) {
            this.providerCode = p.getProviderCode();
            this.displayName = p.getDisplayName();
            this.providerType = p.getProviderType();
            this.baseUrl = p.getBaseUrl();
            this.chatCompletionsPath = p.getChatCompletionsPath();
            this.useApiKey = p.getUseApiKey();
            this.hasApiKey = StringUtils.hasText(p.getApiKeyCipher());
            this.apiKeyMasked = apiKeyMasked;
            this.apiKeyHeader = p.getApiKeyHeader();
            this.apiKeyPrefix = p.getApiKeyPrefix();
            this.enabled = p.getEnabled();
            this.createdAt = p.getCreatedAt();
            this.updatedAt = p.getUpdatedAt();
        }
    }

    /** Provider 新建 / 更新请求体。{@code apiKey} 为明文，仅写入；查询接口绝不回传。 */
    @Data
    public static class ProviderUpsertRequest {
        private String providerCode;
        private String displayName;
        private AiProviderProperties.ProviderType providerType;
        private String baseUrl;
        private String chatCompletionsPath;
        private Boolean useApiKey;
        private String apiKey;
        private String apiKeyHeader;
        private String apiKeyPrefix;
        private Boolean enabled;
    }

    /**
     * 角色额度请求体。
     */
    @Data
    public static class RoleQuotaRequest {
        /**
         * 日额度，单位 CNY；为空表示不限。
         */
        private BigDecimal dailyLimit;
        /**
         * 月额度，单位 CNY；为空表示不限。
         */
        private BigDecimal monthlyLimit;
        /**
         * 并发请求上限，当前保存配置，v1 暂未强制执行。
         */
        private Integer concurrentRequests;
        /**
         * 后台说明。
         */
        private String description;
    }

    /**
     * 模型编码列表请求体，用于批量替换角色模型。
     */
    @Data
    public static class ModelCodesRequest {
        private List<String> modelCodes;
    }

    /**
     * 角色列表请求体，用于从模型视角替换开放角色。
     */
    @Data
    public static class RolesRequest {
        private List<String> roles;
    }

    /**
     * 修改用户角色请求体。
     */
    @Data
    public static class RoleRequest {
        private String role;
    }

    /**
     * 修改用户状态请求体。
     */
    @Data
    public static class StatusRequest {
        private String status;
    }

    /**
     * 用户模型覆盖请求体。
     */
    @Data
    public static class ModelOverrideRequest {
        /**
         * 模型编码。
         */
        private String modelCode;
        /**
         * 覆盖类型：GRANT 或 DENY。
         */
        private String overrideType;
        /**
         * 为空表示全局生效，不为空表示只在指定 workspace 生效。
         */
        private String workspaceId;
        /**
         * 是否启用该覆盖。
         */
        private Boolean enabled;
        /**
         * 后台备注。
         */
        private String reason;
        /**
         * 过期时间。为空表示长期有效。
         */
        private LocalDateTime expiresAt;
    }

    /**
     * 用户配额增量覆盖请求体。
     */
    @Data
    public static class QuotaOverrideRequest {
        /**
         * 为空表示全局/个人上下文生效，不为空表示只在指定 workspace 生效。
         */
        private String workspaceId;
        /**
         * 日额度增量，单位 CNY。
         */
        private BigDecimal dailyDelta;
        /**
         * 月额度增量，单位 CNY。
         */
        private BigDecimal monthlyDelta;
        /**
         * 后台备注。
         */
        private String reason;
        /**
         * 过期时间。为空表示长期有效。
         */
        private LocalDateTime expiresAt;
    }

    /**
     * 创建或更新 workspace 的请求体。
     */
    @Data
    public static class WorkspaceRequest {
        /**
         * 可选 workspace ID。为空时后端生成 UUID。
         */
        private String workspaceId;
        /**
         * 可选组织 ID。为空时后端生成 UUID。
         */
        private String orgId;
        /**
         * 新建组织时使用的组织名称。
         */
        private String orgName;
        /**
         * workspace 名称。
         */
        private String name;
        /**
         * 套餐编码。为空表示个人/无套餐规则。
         */
        private String planCode;
        /**
         * workspace 状态。
         */
        private String status;
        /**
         * 创建 workspace 时的初始 OWNER 用户。
         */
        private String ownerUserId;
    }

    /**
     * workspace 成员请求体。
     */
    @Data
    public static class WorkspaceMemberRequest {
        /**
         * 成员用户 ID。
         */
        private String userId;
        /**
         * 空间内角色，推荐 OWNER、ADMIN、MEMBER、VIEWER。
         */
        private String memberRole;
    }

    /**
     * 套餐权益请求体。
     */
    @Data
    public static class PlanEntitlementRequest {
        /**
         * 权益类型：ALLOWED_MODEL、ALLOWED_PROVIDER、CAPABILITY、LIMIT。
         */
        private String entitlementType;
        /**
         * 权益 key，例如模型编码、provider 编码、DAILY_BUDGET。
         */
        private String entitlementKey;
        /**
         * 文本值，供扩展能力使用。
         */
        private String valueText;
        /**
         * 数值，LIMIT 类型常用。金额单位为 CNY。
         */
        private BigDecimal valueNumber;
        /**
         * 是否启用。
         */
        private Boolean enabled;
    }
}
