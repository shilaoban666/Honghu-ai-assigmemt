package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.UserModelPermission;
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
                .localModel(true)
                .enabled(true)
                .build();
        remoteTier2 = AiModelDefinition.builder()
                .modelCode("gpt-5-mini")
                .displayName("remote t2")
                .providerCode("openai")
                .apiModelName("gpt-5-mini")
                .level(2)
                .localModel(false)
                .enabled(true)
                .build();
        remoteTier1 = AiModelDefinition.builder()
                .modelCode("gpt-5.1")
                .displayName("remote t1")
                .providerCode("openai")
                .apiModelName("gpt-5.1")
                .level(1)
                .localModel(false)
                .enabled(true)
                .build();

        when(aiModelDefinitionRepository.findByEnabledTrueOrderByLevelAscDisplayNameAsc())
                .thenReturn(List.of(remoteTier1, localTier2, remoteTier2));
    }

    @Test
    void guestShouldOnlyAccessLocalTierTwoModels() {
        List<AiModelDefinition> accessible = aiModelAccessService.listAccessibleModels(
                User.builder().userRole(User.UserRole.GUEST).build()
        );

        assertThat(accessible)
                .extracting(AiModelDefinition::getModelCode)
                .containsExactly("deepseek-r1:8b");
    }

    @Test
    void normalUserShouldOnlyAccessTierTwoModels() {
        User user = User.builder().userId("u1").userRole(User.UserRole.USER).build();
        when(userModelPermissionRepository.findByUserIdAndEnabledTrue("u1")).thenReturn(List.of());

        List<AiModelDefinition> accessible = aiModelAccessService.listAccessibleModels(user);

        assertThat(accessible)
                .extracting(AiModelDefinition::getModelCode)
                .containsExactly("deepseek-r1:8b", "gpt-5-mini");
    }

    @Test
    void vipWithExplicitPermissionShouldBeIntersectedByPermissionTable() {
        User user = User.builder().userId("u2").userRole(User.UserRole.VIP).build();
        when(userModelPermissionRepository.findByUserIdAndEnabledTrue("u2")).thenReturn(List.of(
                UserModelPermission.builder().userId("u2").modelCode("gpt-5.1").enabled(true).build()
        ));

        List<AiModelDefinition> accessible = aiModelAccessService.listAccessibleModels(user);

        assertThat(accessible)
                .extracting(AiModelDefinition::getModelCode)
                .containsExactly("gpt-5.1");
    }
}

