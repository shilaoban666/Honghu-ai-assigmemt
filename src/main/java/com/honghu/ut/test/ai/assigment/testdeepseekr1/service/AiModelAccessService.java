package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.UserModelPermission;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.AiModelDefinitionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserModelPermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 模型目录访问兼容门面。
 *
 * <p>这个类过去承担了“按角色硬编码模型权限”的职责。
 * 现在模型权限已经下沉到 {@link EntitlementService}，这里主要保留旧方法签名，
 * 让 ChatService、Controller 和旧测试不用一次性大改。</p>
 *
 * <p>新代码如果只关心“用户最终能用哪些模型”，优先调用 {@link EntitlementService}；
 * 如果还需要模型路由、默认模型、provider 兜底等兼容逻辑，可以继续使用这个类。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelAccessService {

    private final AiModelDefinitionRepository aiModelDefinitionRepository;
    private final UserModelPermissionRepository userModelPermissionRepository;
    private final AiProviderProperties aiProviderProperties;
    private final EntitlementService entitlementService;

    /** 查询全部已启用模型，不做用户权限过滤。主要给后台和内部兜底逻辑使用。 */
    public List<AiModelDefinition> listAllEnabledModels() {
        return aiModelDefinitionRepository.findByEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc();
    }

    /**
     * 查询用户最终可用模型。
     *
     * <p>方法签名保留在这里，但实际委托给 EntitlementService。
     * 这样旧调用方不用知道 role、plan、workspace、override 的细节。</p>
     */
    public List<AiModelDefinition> listAccessibleModels(User user) {
        return entitlementService.listAccessibleModels(user);
    }

    /** 按模型编码查启用中的模型；不存在或禁用时抛业务异常。 */
    public AiModelDefinition requireEnabledModel(String modelCode) {
        return aiModelDefinitionRepository.findByModelCodeAndEnabledTrue(modelCode)
                .orElseThrow(() -> new IllegalArgumentException("模型不存在或未启用: " + modelCode));
    }

    /**
     * 解析一次聊天最终要使用的模型。
     *
     * <p>优先级是：用户显式指定模型 > 自动路由模型 > 配置里的默认模型 > 用户可用模型中的第一个。
     * 每一步都会检查当前用户是否有权限使用该模型。</p>
     */
    public AiModelDefinition resolveModelForChat(User user, String explicitModelCode, String autoRoutedModelCode) {
        if (StringUtils.hasText(explicitModelCode)) {
            return requireAccessibleModel(user, explicitModelCode, true);
        }

        if (StringUtils.hasText(autoRoutedModelCode)) {
            try {
                return requireAccessibleModel(user, autoRoutedModelCode, false);
            } catch (IllegalArgumentException ignored) {
                log.info("自动路由模型当前用户不可用，准备回退：userId={}, model={}",
                        user != null ? user.getUserId() : "anonymous", autoRoutedModelCode);
            }
        }

        if (StringUtils.hasText(aiProviderProperties.getDefaultModel())) {
            try {
                return requireAccessibleModel(user, aiProviderProperties.getDefaultModel(), false);
            } catch (IllegalArgumentException ignored) {
                log.info("默认模型当前用户不可用，继续回退到首个可用模型：userId={}, model={}",
                        user != null ? user.getUserId() : "anonymous", aiProviderProperties.getDefaultModel());
            }
        }

        return listAccessibleModels(user).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前用户没有任何可用模型，请先分配模型权限"));
    }

    /** 判断用户是否是平台后台管理员。 */
    public boolean isAdmin(User user) {
        return user != null && user.getUserRole() == User.UserRole.ADMIN;
    }

    /** 把平台角色翻译成适合前端展示的中文身份名称。 */
    public String resolveIdentityLabel(User.UserRole role) {
        if (role == null) {
            return "游客";
        }
        return switch (role) {
            case GUEST -> "游客";
            case USER -> "普通用户";
            case PRO -> "PRO 用户";
            case PLUS -> "PLUS 用户";
            case PRO_PLUS -> "PRO_PLUS 用户";
            case VIP -> "VIP用户";
            case ADMIN -> "admin用户";
        };
    }

    /**
     * 生成角色权限摘要文案。
     *
     * <p>这个方法主要服务登录接口返回值和后台概览展示，帮助前端直接显示“这个角色大概能做什么”。</p>
     */
    public String resolvePermissionSummary(User.UserRole role) {
        if (role == null) {
            return "游客只能使用第二梯队普通模型";
        }
        return switch (role) {
            case GUEST -> "游客只能使用第二梯队普通模型";
            case USER -> "普通用户只能使用第二梯队模型";
            case PRO -> "PRO 用户拥有更高个人配额和订阅模型权限";
            case PLUS -> "PLUS 用户拥有增强个人配额和更多订阅模型权限";
            case PRO_PLUS -> "PRO_PLUS 用户拥有最高个人订阅模型权限";
            case VIP -> "VIP用户可以使用全部梯队模型";
            case ADMIN -> "admin用户可以使用全部梯队模型，并拥有全量管理权限";
        };
    }

    /**
     * 当本地模型不可用时，从“当前用户仍然有权限使用的云端模型”里选一个回退目标。
     *
     * <p>如果 preferredFallbackModelCode 可用，则优先返回它；否则退回到第一个非本地模型。</p>
     */
    public AiModelDefinition resolveFallbackModelWhenLocalUnavailable(User user, String preferredFallbackModelCode) {
        List<AiModelDefinition> accessibleModels = listAccessibleModels(user);
        if (accessibleModels.isEmpty()) {
            return null;
        }

        if (StringUtils.hasText(preferredFallbackModelCode)) {
            AiModelDefinition preferredModel = accessibleModels.stream()
                    .filter(model -> preferredFallbackModelCode.equals(model.getModelCode()))
                    .findFirst()
                    .orElse(null);
            if (preferredModel != null) {
                return preferredModel;
            }
        }

        return accessibleModels.stream()
                .filter(model -> !Boolean.TRUE.equals(model.getLocalModel()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 旧版默认授权本地模型的方法。
     *
     * <p>新版注册流程不再给用户逐条写 user_model_permission，
     * 而是创建个人 workspace 后通过角色默认模型和套餐权益统一计算可用模型。
     * 这个方法保留为回滚路径和旧数据修复工具，不建议新流程继续调用。</p>
     */
    @Deprecated(since = "billing-quota-entitlement")
    @Transactional
    public void grantDefaultLocalModels(String userId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }

        List<AiModelDefinition> defaultLocalModels = aiModelDefinitionRepository.findByLocalModelTrueAndEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc();
        if (defaultLocalModels.isEmpty()) {
            return;
        }

        Map<String, UserModelPermission> existingPermissions = userModelPermissionRepository.findByUserId(userId)
                .stream()
                .collect(Collectors.toMap(UserModelPermission::getModelCode, permission -> permission, (left, right) -> right, LinkedHashMap::new));

        List<UserModelPermission> toSave = new ArrayList<>();
        for (AiModelDefinition model : defaultLocalModels) {
            UserModelPermission permission = existingPermissions.get(model.getModelCode());
            if (permission == null) {
                toSave.add(UserModelPermission.builder()
                        .userId(userId)
                        .modelCode(model.getModelCode())
                        .enabled(Boolean.TRUE)
                        .overrideType("GRANT")
                        .build());
                continue;
            }
            if (!Boolean.TRUE.equals(permission.getEnabled())) {
                permission.setEnabled(Boolean.TRUE);
                permission.setOverrideType("GRANT");
                toSave.add(permission);
            }
        }

        if (!toSave.isEmpty()) {
            userModelPermissionRepository.saveAll(toSave);
            log.info("已为用户授予默认本地模型权限：userId={}, count={}", userId, toSave.size());
        }
    }

    /**
     * 用新的目标模型集合替换用户当前的显式授权覆盖。
     *
     * <p>这里操作的是 {@code user_model_permission} 的 GRANT 规则，主要给后台做“批量替换授权模型”。</p>
     */
    @Transactional
    public List<AiModelDefinition> replaceUserPermissions(String userId, List<String> modelCodes) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        Set<String> targetCodes = modelCodes == null
                ? Set.of()
                : modelCodes.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<AiModelDefinition> targetModels = targetCodes.isEmpty()
                ? List.of()
                : aiModelDefinitionRepository.findByModelCodeInAndEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc(targetCodes);
        if (targetModels.size() != targetCodes.size()) {
            Set<String> foundCodes = targetModels.stream().map(AiModelDefinition::getModelCode).collect(Collectors.toSet());
            Set<String> missingCodes = new LinkedHashSet<>(targetCodes);
            missingCodes.removeAll(foundCodes);
            throw new IllegalArgumentException("以下模型不存在或未启用: " + missingCodes);
        }

        Map<String, UserModelPermission> existingMap = userModelPermissionRepository.findByUserId(userId)
                .stream()
                .collect(Collectors.toMap(UserModelPermission::getModelCode, permission -> permission, (left, right) -> right, LinkedHashMap::new));

        List<UserModelPermission> toSave = new ArrayList<>();
        for (Map.Entry<String, UserModelPermission> entry : existingMap.entrySet()) {
            if (!targetCodes.contains(entry.getKey()) && Boolean.TRUE.equals(entry.getValue().getEnabled())) {
                entry.getValue().setEnabled(Boolean.FALSE);
                toSave.add(entry.getValue());
            }
        }

        for (String targetCode : targetCodes) {
            UserModelPermission permission = existingMap.get(targetCode);
            if (permission == null) {
                toSave.add(UserModelPermission.builder()
                        .userId(userId)
                        .modelCode(targetCode)
                        .enabled(Boolean.TRUE)
                        .overrideType("GRANT")
                        .build());
                continue;
            }
            if (!Boolean.TRUE.equals(permission.getEnabled())) {
                permission.setEnabled(Boolean.TRUE);
                permission.setOverrideType("GRANT");
                toSave.add(permission);
            }
        }

        if (!toSave.isEmpty()) {
            userModelPermissionRepository.saveAll(toSave);
        }
        return targetModels;
    }

    /**
     * 校验用户是否有权使用某个具体模型。
     *
     * <p>explicitRequest=true 表示这是用户主动点选的模型；false 表示系统自动路由出来的模型，
     * 两种情况返回的错误文案会略有区别，方便前端或日志快速判断问题出在哪一层。</p>
     */
    private AiModelDefinition requireAccessibleModel(User user, String modelCode, boolean explicitRequest) {
        AiModelDefinition model = requireEnabledModel(modelCode);
        boolean accessible = listAccessibleModels(user).stream()
                .anyMatch(item -> item.getModelCode().equals(modelCode));
        if (accessible) {
            return model;
        }

        throw new IllegalArgumentException(explicitRequest
                ? "当前用户无权使用模型: " + modelCode
                : "自动路由模型当前用户无权使用: " + modelCode);
    }
}
