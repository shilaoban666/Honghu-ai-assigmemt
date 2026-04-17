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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelAccessServiceTest {

    @Mock
    private AiModelDefinitionRepository aiModelDefinitionRepository;
    @Mock
    private UserModelPermissionRepository userModelPermissionRepository;

    private AiModelAccessService aiModelAccessService;

    private AiModelDefinition localTier2;
    private AiModelDefinition remoteTier2;
    private AiModelDefinition deepseekFallback;
    private AiModelDefinition remoteTier1;

    @BeforeEach
    void setUp() {
        aiModelAccessService = new AiModelAccessService(
                aiModelDefinitionRepository,
                userModelPermissionRepository,
                new AiProviderProperties()
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
        remoteTier1 = AiModelDefinition.builder()
                .modelCode("gpt-5.1")
                .displayName("remote t1")
                .providerCode("openai")
                .apiModelName("gpt-5.1")
                .level(1)
                .score(80)
                .localModel(false)
                .enabled(true)
                .build();

        when(aiModelDefinitionRepository.findByEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc())
                .thenReturn(List.of(remoteTier1, deepseekFallback, localTier2, remoteTier2));
    }

    @Test
    void guestShouldOnlyAccessTierTwoModels() {
        List<AiModelDefinition> accessible = aiModelAccessService.listAccessibleModels(
                User.builder().userRole(User.UserRole.GUEST).build()
        );

        assertThat(accessible)
                .extracting(AiModelDefinition::getModelCode)
                .containsExactly("deepseek-v3.2", "deepseek-r1:8b", "gpt-5-mini");
    }

    @Test
    void normalUserShouldOnlyAccessTierTwoModels() {
        User user = User.builder().userId("u1").userRole(User.UserRole.USER).build();
        when(userModelPermissionRepository.findByUserIdAndEnabledTrue("u1")).thenReturn(List.of());

        List<AiModelDefinition> accessible = aiModelAccessService.listAccessibleModels(user);

        assertThat(accessible)
                .extracting(AiModelDefinition::getModelCode)
                .containsExactly("deepseek-v3.2", "deepseek-r1:8b", "gpt-5-mini");
    }

    @Test
    void vipShouldStillAccessAllModelsEvenWhenPermissionTableHasSubset() {
        User user = User.builder().userId("u2").userRole(User.UserRole.VIP).build();

        List<AiModelDefinition> accessible = aiModelAccessService.listAccessibleModels(user);

        assertThat(accessible)
                .extracting(AiModelDefinition::getModelCode)
                .containsExactly("gpt-5.1", "deepseek-v3.2", "deepseek-r1:8b", "gpt-5-mini");
    }

    @Test
    void shouldPreferConfiguredRemoteFallbackWhenLocalUnavailable() {
        User guest = User.builder().userRole(User.UserRole.GUEST).build();

        AiModelDefinition fallback = aiModelAccessService.resolveFallbackModelWhenLocalUnavailable(guest, "deepseek-v3.2");

        assertThat(fallback).isNotNull();
        assertThat(fallback.getModelCode()).isEqualTo("deepseek-v3.2");
    }
}
