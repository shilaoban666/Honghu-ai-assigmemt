package com.honghu.ut.test.ai.assigment.testdeepseekr1.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 单个会话维度的中期记忆画像。
 *
 * <p>它保存的是“当前 session 的高浓度摘要”，
 * 用于把已经较长的历史对话压缩为可复用的中期上下文。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chat_session_profile")
public class ChatSessionProfile {

    /** 会话 ID，同时作为主键，一条会话只保留一份最新摘要。 */
    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 用户 ID，便于后续聚合成用户主体画像。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 当前会话的压缩摘要，重点保留用户偏好、特征和已解决事项。 */
    @Column(name = "profile_summary", nullable = false, columnDefinition = "TEXT")
    private String profileSummary;

    /** 本次摘要已经覆盖到的最新 chat_id，用于增量总结和幂等判断。 */
    @Column(name = "last_summarized_chat_id")
    private Long lastSummarizedChatId;

    /** 本次摘要所覆盖的消息总数，用于阈值判断和观测。 */
    @Column(name = "summarized_message_count")
    private Integer summarizedMessageCount;

    /** 生成摘要时使用的小模型名称，便于排查效果与成本。 */
    @Column(name = "summary_model", length = 64)
    private String summaryModel;

    /** 首次生成该 session 摘要的时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最近一次刷新该 session 摘要的时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}


