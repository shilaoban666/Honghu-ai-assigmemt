package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SessionToolApprovalRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security.ToolAccessDecision;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security.ToolGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 验证危险等级门 {@link ToolGuard} 的核心放行/拦截规则。
 */
@ExtendWith(MockitoExtension.class)
class ToolGuardTest {

    @Mock
    private SessionToolApprovalRepository approvalRepository;

    private ToolGuard toolGuard() {
        return new ToolGuard(approvalRepository);
    }

    private ToolCallbackRegistration registration(DangerLevel dangerLevel) {
        return new ToolCallbackRegistration(null, 1L, "cli:git", "cli__git__run", dangerLevel, "CLI");
    }

    private ToolExecutionContext context(String sessionId) {
        return new ToolExecutionContext("u1", sessionId, null, null);
    }

    @Test
    void safeToolsAreAllowed() {
        assertThat(toolGuard().check(registration(DangerLevel.SAFE), context("s1")).allowed()).isTrue();
    }

    @Test
    void cautionToolsAreAllowed() {
        assertThat(toolGuard().check(registration(DangerLevel.CAUTION), context("s1")).allowed()).isTrue();
    }

    @Test
    void dangerousToolsAreDeniedWithoutSession() {
        ToolAccessDecision decision = toolGuard().check(registration(DangerLevel.DANGEROUS), context(null));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isNotBlank();
    }

    @Test
    void dangerousToolsAreDeniedWithoutApproval() {
        when(approvalRepository.hasValidApproval(eq("s1"), eq("cli__git__run"), any(LocalDateTime.class)))
                .thenReturn(false);
        assertThat(toolGuard().check(registration(DangerLevel.DANGEROUS), context("s1")).allowed()).isFalse();
    }

    @Test
    void dangerousToolsAreAllowedWithValidApproval() {
        when(approvalRepository.hasValidApproval(eq("s1"), eq("cli__git__run"), any(LocalDateTime.class)))
                .thenReturn(true);
        assertThat(toolGuard().check(registration(DangerLevel.DANGEROUS), context("s1")).allowed()).isTrue();
    }
}
