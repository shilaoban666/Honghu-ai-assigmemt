package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.ToolInvocationLog;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.ToolInvocationLogRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security.ToolAccessDecision;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security.ToolGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证统一审计/管控装饰器 {@link AuditingToolCallback} 的行为：
 * 注入可信上下文、成功/拒绝都落库、执行后清理 ThreadLocal。
 */
@ExtendWith(MockitoExtension.class)
class AuditingToolCallbackTest {

    @Mock
    private ToolCallback delegate;
    @Mock
    private ToolGuard toolGuard;
    @Mock
    private ToolInvocationLogRepository logRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuditingToolCallback callback() {
        ToolCallbackRegistration registration = new ToolCallbackRegistration(
                delegate, 5L, "time", "builtin__time__now", DangerLevel.SAFE, "BUILTIN");
        return new AuditingToolCallback(registration, toolGuard, logRepository, objectMapper);
    }

    @Test
    void allowedCallInjectsContextAndLogsSuccess() {
        when(toolGuard.check(any(), any())).thenReturn(ToolAccessDecision.allow());
        when(delegate.call(anyString(), any())).thenAnswer(invocation -> {
            // 工具执行期间应能从 ThreadLocal 读到可信上下文。
            ToolExecutionContext ctx = ToolExecutionContextHolder.get();
            assertThat(ctx).isNotNull();
            assertThat(ctx.userId()).isEqualTo("u1");
            assertThat(ctx.sessionId()).isEqualTo("s1");
            return "{\"ok\":true}";
        });

        ToolContext toolContext = new ToolContext(Map.of("userId", "u1", "sessionId", "s1", "messageId", 42L));
        String result = callback().call("{\"a\":1}", toolContext);

        assertThat(result).isEqualTo("{\"ok\":true}");
        // 执行后必须清理 ThreadLocal，避免线程复用串号。
        assertThat(ToolExecutionContextHolder.get()).isNull();
        verify(logRepository).save(org.mockito.ArgumentMatchers.<ToolInvocationLog>argThat(log ->
                "SUCCESS".equals(log.getStatus())
                        && "u1".equals(log.getUserId())
                        && "s1".equals(log.getSessionId())
                        && Long.valueOf(42L).equals(log.getMessageId())));
    }

    @Test
    void deniedCallReturnsStructuredJsonAndSkipsDelegate() {
        when(toolGuard.check(any(), any())).thenReturn(ToolAccessDecision.deny("需要二次确认"));

        String result = callback().call("{}", new ToolContext(Map.of("sessionId", "s1")));

        assertThat(result).contains("DENIED");
        assertThat(result).contains("需要二次确认");
        verify(delegate, never()).call(anyString(), any());
        verify(logRepository).save(org.mockito.ArgumentMatchers.<ToolInvocationLog>argThat(log ->
                "DENIED".equals(log.getStatus())));
        assertThat(ToolExecutionContextHolder.get()).isNull();
    }
}
