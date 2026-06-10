package com.honghu.ai.assigment.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 任务路由关键词实体
 * 用于维护任务分类与其对应的识别关键词。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_task_keyword",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_task_keyword_type_keyword", columnNames = {"task_type", "keyword"}))
@Schema(description = "AI 任务路由关键词")
public class AiTaskKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    @Schema(description = "主键 ID")
    private Long id;

    @Column(name = "task_type", nullable = false, length = 32)
    @Schema(description = "任务类型", example = "CODE")
    private String taskType;

    @Column(name = "keyword", nullable = false, length = 128)
    @Schema(description = "关键词", example = "java")
    private String keyword;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    @Schema(description = "是否启用")
    private Boolean enabled = true;

    @Builder.Default
    @Column(name = "priority", nullable = false)
    @Schema(description = "匹配优先级，越小越优先")
    private Integer priority = 100;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}

