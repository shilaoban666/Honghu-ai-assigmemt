package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 会话级危险工具预审批实体，对应 {@code session_tool_approval} 表。
 *
 * <p>当前 HITL（Human-In-The-Loop，人类确认）策略刻意保守：只要工具被标记为 DANGEROUS，
 * 就必须在当前会话中存在一条“精确工具名”的有效审批记录，否则 {@code ToolGuard} 会拒绝执行。
 * 后续前端可以在展示确认卡片后创建这类记录，而运行时拦截逻辑无需改变。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "session_tool_approval")
public class SessionToolApproval {

    /** 审批记录数据库主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 审批所属会话；审批只在这个 sessionId 内有效，不能跨会话复用。 */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 精确的模型可见工具名，例如 {@code cli__git__status}；必须完全匹配才放行。 */
    @Column(name = "tool_qualified_name", nullable = false, length = 300)
    private String toolQualifiedName;

    /** 批准该危险操作的用户 id；系统或测试创建时可以为空。 */
    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    /** 可选过期时间；为空表示不过期，直到记录被删除。 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 创建时间，由 Hibernate 自动写入，用于审计和排查。 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
