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
 * AI 模型目录与用户权限服务。
 *
 * <p>该服务负责三类事情：</p>
 * <ol>
 *     <li>读取系统中可用的模型目录</li>
 *     <li>根据用户身份角色计算“理论可用模型范围”</li>
 *     <li>在存在 {@code user_model_permission} 个性化授权时，对角色能力做进一步收口</li>
 * </ol>
 *
 * <p>当前身份规则：</p>
 * <ul>
 *     <li>GUEST：全部第二梯队模型</li>
 *     <li>USER：全部第二梯队模型</li>
 *     <li>VIP：全部启用模型</li>
 *     <li>ADMIN：全部启用模型</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelAccessService {

    private final AiModelDefinitionRepository aiModelDefinitionRepository;
    private final UserModelPermissionRepository userModelPermissionRepository;
    private final AiProviderProperties aiProviderProperties;

    /** 查询全部已启用模型目录。 */
    public List<AiModelDefinition> listAllEnabledModels() {
        return aiModelDefinitionRepository.findByEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc();
    }

    /**
     * 查询当前用户可用模型。
     *
     * <p>这里不是简单读权限表，而是先算"角色基线能力"，再与个性化授权求交集：</p>
     * <ul>
     *     <li>ADMIN 和 VIP 用户：直接返回所有启用的模型，不受权限表限制</li>
     *     <li>GUEST 用户：返回第二梯队模型，不受权限表限制</li>
     *     <li>USER 用户：没有个性化授权记录时，直接返回角色基线能力；有个性化授权记录时，返回"角色基线 ∩ 显式授权"</li>
     * </ul>
     */
    public List<AiModelDefinition> listAccessibleModels(User user) {
        List<AiModelDefinition> roleBasedModels = listRoleScopedModels(user);

        // ADMIN 和 VIP 用户拥有所有模型的完整权限，不受权限表限制
        if (user != null && (user.getUserRole() == User.UserRole.ADMIN || user.getUserRole() == User.UserRole.VIP)) {
            return roleBasedModels;
        }
        // USER 用户需要检查权限表
        List<String> explicitPermissionCodes = userModelPermissionRepository.findByUserIdAndEnabledTrue(user.getUserId())
                .stream()
                .map(UserModelPermission::getModelCode)
                .distinct()
                .toList();
        if (explicitPermissionCodes.isEmpty()) {
            return roleBasedModels;
        }

        Set<String> permissionSet = new LinkedHashSet<>(explicitPermissionCodes);
        return roleBasedModels.stream()
                .filter(model -> permissionSet.contains(model.getModelCode()))
                .toList();
    }

    /** 按编码查找已启用模型定义。 */
    public AiModelDefinition requireEnabledModel(String modelCode) {
        return aiModelDefinitionRepository.findByModelCodeAndEnabledTrue(modelCode)
                .orElseThrow(() -> new IllegalArgumentException("模型不存在或未启用: " + modelCode));
    }

    /**
     * 解析一次聊天真正可用的模型。
     *
     * <p>规则：</p>
     * <ol>
     *     <li>如果用户显式传了 model，则必须校验权限；无权限直接报错</li>
     *     <li>如果是系统自动路由结果，则优先尝试该模型；无权限时回退到默认模型或首个可用模型</li>
     *     <li>匿名用户只允许使用 localModel=true 的本地模型</li>
     * </ol>
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

    /** ADMIN 判断。 */
    public boolean isAdmin(User user) {
        return user != null && user.getUserRole() == User.UserRole.ADMIN;
    }

    /**
     * 计算角色中文名称。
     */
    public String resolveIdentityLabel(User.UserRole role) {
        if (role == null) {
            return "游客";
        }
        return switch (role) {
            case GUEST -> "游客";
            case USER -> "普通用户";
            case VIP -> "VIP用户";
            case ADMIN -> "admin用户";
        };
    }

    /**
     * 计算角色权限摘要说明，用于 login 接口直接返回给前端展示。
     */
    public String resolvePermissionSummary(User.UserRole role) {
        if (role == null) {
            return "游客只能使用第二梯队普通模型";
        }
        return switch (role) {
            case GUEST -> "游客只能使用第二梯队普通模型";
            case USER -> "普通用户只能使用第二梯队模型";
            case VIP -> "VIP用户可以使用全部梯队模型";
            case ADMIN -> "admin用户可以使用全部梯队模型，并拥有全量管理权限";
        };
    }

    /**
     * 在本地 Ollama 不可用时，解析一个当前用户仍可使用的云端回退模型。
     *
     * <p>优先级：</p>
     * <ol>
     *     <li>优先使用配置中指定的首选回退模型（且该模型对当前用户可用）</li>
     *     <li>否则退化为当前用户可用模型列表中的第一个“非本地模型”</li>
     *     <li>如果当前用户没有任何远端模型权限，则返回空，由上层决定是否继续尝试本地模型</li>
     * </ol>
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
     * 为新用户授予默认本地模型权限。
     *
     * <p>这里不是最终能力边界，最终仍会受 {@link #listAccessibleModels(User)} 的角色规则约束。</p>
     */
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
                        .build());
                continue;
            }
            if (!Boolean.TRUE.equals(permission.getEnabled())) {
                permission.setEnabled(Boolean.TRUE);
                toSave.add(permission);
            }
        }

        if (!toSave.isEmpty()) {
            userModelPermissionRepository.saveAll(toSave);
            log.info("已为用户授予默认本地模型权限：userId={}, count={}", userId, toSave.size());
        }
    }

    /**
     * 替换指定用户的模型权限集合。
     *
     * <p>注意：这里是“显式授权集”，不是越权授权。即使给 USER 授权了第一梯队模型，
     * 最终也会在角色规则阶段被拦下。</p>
     */
    @Transactional
    public List<AiModelDefinition> replaceUserPermissions(String userId, List<String> modelCodes) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        Set<String> targetCodes = modelCodes == null
                ? Set.of()
                : modelCodes.stream().filter(StringUtils::hasText).map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));

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
                        .build());
                continue;
            }
            if (!Boolean.TRUE.equals(permission.getEnabled())) {
                permission.setEnabled(Boolean.TRUE);
                toSave.add(permission);
            }
        }

        if (!toSave.isEmpty()) {
            userModelPermissionRepository.saveAll(toSave);
        }
        return targetModels;
    }

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

    /**
     * 仅按角色规则计算可用模型范围，不考虑个性化授权表。
     */
    private List<AiModelDefinition> listRoleScopedModels(User user) {
        List<AiModelDefinition> allModels = listAllEnabledModels();
        User.UserRole role = user == null || user.getUserRole() == null ? User.UserRole.GUEST : user.getUserRole();

        return switch (role) {
            case ADMIN, VIP -> allModels;
            case USER -> allModels.stream()
                    .filter(model -> model.getLevel() != null && model.getLevel() >= 2)
                    .toList();
            case GUEST -> allModels.stream()
                    .filter(model -> model.getLevel() != null && model.getLevel() >= 2)
                    .toList();
        };
    }
}


