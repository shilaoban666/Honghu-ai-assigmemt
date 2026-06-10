package com.honghu.ai.assigment.skill.security;

import com.honghu.ai.assigment.skill.core.AuditingToolCallback;
import com.honghu.ai.assigment.skill.core.DangerLevel;
import com.honghu.ai.assigment.skill.core.ToolCallbackRegistration;
import com.honghu.ai.assigment.skill.core.ToolExecutionContext;
import com.honghu.ai.assigment.skill.repository.SessionToolApprovalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 每个“模型可见工具”的统一风险闸门。
 *
 * <p>危险等级如果只存在数据库里，就只是展示用元数据；模型一旦拿到工具定义，仍然可能直接调用。
 * 本类把 {@link DangerLevel} 转换成真正的运行时决策：安全工具直接放行，危险工具必须在当前会话中
 * 先产生有效审批记录，否则不会进入真实执行器。</p>
 *
 * <p>这个守卫不区分工具来源。内置 Java、远程 MCP、沙箱 CLI 和未来自定义 HTTP 工具都会先经过
 * {@link AuditingToolCallback}，再调用这里的 {@link #check(ToolCallbackRegistration, ToolExecutionContext)}。</p>
 */
@Component
@RequiredArgsConstructor
public class ToolGuard {

    /** 会话级危险工具审批记录仓库；未来确认卡片或管理后台会往这里写批准记录。 */
    private final SessionToolApprovalRepository approvalRepository;

    /**
     * 应用当前危险等级策略。
     *
     * <p>{@link DangerLevel#SAFE} 和 {@link DangerLevel#CAUTION} 当前会立即放行。
     * CAUTION 仍保留在元数据和日志里，便于后续添加限流或警告横幅而不用改 provider。
     * DANGEROUS 必须在当前会话中存在精确工具名匹配的审批记录；没有 sessionId 时直接拒绝。</p>
     *
     * @param tool 即将执行的工具注册信息
     * @param ctx 可信调用上下文
     * @return 允许或拒绝决策
     */
    public ToolAccessDecision check(ToolCallbackRegistration tool, ToolExecutionContext ctx) {
        // 缺少注册信息或危险等级时按 SAFE 处理，避免空指针让普通工具不可用。
        DangerLevel dangerLevel = tool == null || tool.dangerLevel() == null
                ? DangerLevel.SAFE
                : tool.dangerLevel();
        // SAFE/CAUTION 当前直接放行；DANGEROUS 必须额外检查会话审批。
        return switch (dangerLevel) {
            case SAFE, CAUTION -> ToolAccessDecision.allow();
            case DANGEROUS -> checkDangerous(tool, ctx);
        };
    }

    /**
     * 检查危险工具是否在当前会话中获得过有效审批。
     */
    private ToolAccessDecision checkDangerous(ToolCallbackRegistration tool, ToolExecutionContext ctx) {
        // 审批是会话级的，没有 sessionId 就无法确认用户在哪个对话里批准过该动作。
        String sessionId = ctx == null ? null : ctx.sessionId();
        if (!StringUtils.hasText(sessionId)) {
            return ToolAccessDecision.deny("该工具属于高风险操作，需要在具体会话中二次确认后才能执行。");
        }
        // 查询 exact toolQualifiedName，避免用户批准 A 工具后误放行同会话里的 B 工具。
        boolean approved = approvalRepository.hasValidApproval(
                sessionId,
                tool.toolQualifiedName(),
                LocalDateTime.now());
        // 有有效审批才放行；否则返回可展示给模型/前端的拒绝原因。
        return approved
                ? ToolAccessDecision.allow()
                : ToolAccessDecision.deny("该操作需要用户二次确认。请先在当前会话中批准工具 " + tool.toolQualifiedName() + "。");
    }
}
