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
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 工具调用审计日志实体，对应数据库 {@code tool_invocation_log} 表。
 *
 * <p>每次模型触发工具调用时，{@code ToolExecutorService} 都会在 finally 中写入一条日志。
 * 日志覆盖调用归属、工具名称、原始参数、结果预览、耗时、状态和错误摘要，可用于会话侧边栏
 * 展示、问题排查、调用量统计、慢工具分析和后续高风险工具审计。</p>
 *
 * <p>该表保存的是审计摘要而不是完整业务结果。大结果会被截断到预览字段，避免工具返回大量文本
 * 或结构化数据时把日志表变成非受控的大对象存储。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tool_invocation_log")
public class ToolInvocationLog {
    // 调用日志主键。
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 发起工具调用的用户；匿名或内部调用可以为空。
    @Column(name = "user_id", length = 64)
    private String userId;

    // 工具调用所属会话，用于前端侧边栏按会话展示工具调用历史。
    @Column(name = "session_id", length = 64)
    private String sessionId;

    // 触发工具调用的消息 id；当前没有消息上下文时允许为空。
    @Column(name = "message_id")
    private Long messageId;

    // 实际被模型调用的全局工具名，例如 builtin__kb__searchKnowledgeBase。
    @Column(name = "tool_qualified_name", length = 300)
    private String toolQualifiedName;

    // 所属技能 id，便于后续统计某个 Skill 的调用量。
    @Column(name = "skill_id")
    private Long skillId;

    // 模型生成的原始 JSON arguments，用 JSONB 保存便于后续按字段排查。
    @Column(name = "arguments", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String arguments;

    // 工具结果预览，执行器会截断到 2KB，防止大结果撑爆日志表。
    @Column(name = "result_preview")
    private String resultPreview;

    // 工具执行耗时，单位毫秒。
    @Column(name = "duration_ms")
    private Integer durationMs;

    // SUCCESS / ERROR / DENIED 等状态；当前执行器先写 SUCCESS 和 ERROR。
    @Column(name = "status", length = 20)
    private String status;

    // 失败时的错误摘要；成功调用保持为空。
    @Column(name = "error_message")
    private String errorMessage;

    // 日志创建时间由 Hibernate 自动生成。
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
