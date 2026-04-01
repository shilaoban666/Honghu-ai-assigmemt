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
 * 用户主体画像快照。
 *
 * <p>它不是单次会话摘要，而是聚合最近若干个 session 的稳定特征、偏好和问题领域，
 * 供后续所有新会话快速建立长期用户上下文。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_profile_snapshot")
public class UserProfileSnapshot {

    /** 用户 ID，同时作为主体画像主键。 */
    @Id
    @Column(name = "user_id", length = 64)
    private String userId;

    /** 最近多个会话聚合后的主体画像。 */
    @Column(name = "profile_summary", nullable = false, columnDefinition = "TEXT")
    private String profileSummary;

    /** 本次画像聚合所使用的 session 数量。 */
    @Column(name = "source_session_count")
    private Integer sourceSessionCount;

    /** 本次画像使用到的 sessionId 列表，便于排查来源窗口。 */
    @Column(name = "source_session_ids", columnDefinition = "TEXT")
    private String sourceSessionIds;

    /** 生成主体画像时使用的小模型名称。 */
    @Column(name = "summary_model", length = 64)
    private String summaryModel;

    /** 首次生成用户主体画像的时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最近一次刷新用户主体画像的时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}