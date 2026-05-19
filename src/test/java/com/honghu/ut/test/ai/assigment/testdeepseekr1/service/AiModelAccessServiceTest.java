package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.AiModelDefinitionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserModelPermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 AiModelAccessService 在新权益体系下仍保留旧门面行为。
 *
 * <p>重点校验两件事：</p>
 * <ul>
 *     <li>列出可用模型时，确实委托给新的 EntitlementService，而不是继续走旧硬编码。</li>
 *     <li>本地模型不可用时，能够从用户有权限的模型集合里选出合适的云端回退模型。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AiModelAccessServiceTest {

    @Mock
    private AiModelDefinitionRepository aiModelDefinitionRepository;
    @Mock
    private UserModelPermissionRepository userModelPermissionRepository;
    @Mock
    private EntitlementService entitlementService;

    private AiModelAccessService aiModelAccessService;

    private AiModelDefinition localTier2;
    private AiModelDefinition remoteTier2;
    private AiModelDefinition deepseekFallback;

    @BeforeEach
    void setUp() {
        aiModelAccessService = new AiModelAccessService(
                aiModelDefinitionRepository,
                userModelPermissionRepository,
                new AiProviderProperties(),
                entitlementService
        );

        localTier2 = AiModelDefinition.builder()
                .modelCode("deepseek-r1:8b")
                .displayName("local t2")
                .providerCode("ollama-local")
                .apiModelName("deepseek-r1:8b")
                .level(2)
                .score(45)
                .localModel(true)
                .enabled(true)
                .build();
        remoteTier2 = AiModelDefinition.builder()
                .modelCode("gpt-5-mini")
                .displayName("remote t2")
                .providerCode("openai")
                .apiModelName("gpt-5-mini")
                .level(2)
                .score(30)
                .localModel(false)
                .enabled(true)
                .build();
        deepseekFallback = AiModelDefinition.builder()
                .modelCode("deepseek-v3.2")
                .displayName("deepseek fallback")
                .providerCode("deepseek-cloud")
                .apiModelName("deepseek-v3.2")
                .level(2)
                .score(60)
                .localModel(false)
                .enabled(true)
                .build();
    }

    /** 验证旧入口 listAccessibleModels(User) 现在已经委托到新的权益服务。 */
    @Test
    void listAccessibleModelsShouldDelegateToEntitlementService() {
        User user = User.builder().userId("u1").userRole(User.UserRole.USER).build();
        when(entitlementService.listAccessibleModels(user)).thenReturn(List.of(deepseekFallback, localTier2));

        List<AiModelDefinition> accessible = aiModelAccessService.listAccessibleModels(user);

        assertThat(accessible).extracting(AiModelDefinition::getModelCode)
                .containsExactly("deepseek-v3.2", "deepseek-r1:8b");
        verify(entitlementService).listAccessibleModels(user);
    }

    /** 验证本地模型不可用时，会优先采用显式配置且用户有权限的回退模型。 */
    @Test
    void shouldPreferConfiguredRemoteFallbackWhenLocalUnavailable() {
        User guest = User.builder().userRole(User.UserRole.GUEST).build();
        when(entitlementService.listAccessibleModels(guest)).thenReturn(List.of(localTier2, deepseekFallback, remoteTier2));

        AiModelDefinition fallback = aiModelAccessService.resolveFallbackModelWhenLocalUnavailable(guest, "deepseek-v3.2");

        assertThat(fallback).isNotNull();
        assertThat(fallback.getModelCode()).isEqualTo("deepseek-v3.2");
    }
}
