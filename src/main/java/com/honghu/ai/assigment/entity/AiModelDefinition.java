package com.honghu.ai.assigment.entity;

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
 * AI 模型目录实体。
 *
 * <p>用于保存系统中可接入的所有模型元数据，包含：</p>
 * <ul>
 *     <li>展示名称</li>
 *     <li>所属 provider</li>
 *     <li>真正调用时使用的 api model name</li>
 *     <li>level：模型梯队，数值越小优先级越高</li>
 * </ul>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_model_definition")
public class AiModelDefinition {

    /** 模型编码，作为系统内部唯一标识。 */
    @Id
    @Column(name = "model_code", length = 100)
    private String modelCode;

    /** 模型展示名称，供前端下拉框或列表展示。 */
    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    /** provider 编码，对应配置文件里的 provider key。 */
    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    /** 真正发给上游 API 的模型名称。 */
    @Column(name = "api_model_name", nullable = false, length = 160)
    private String apiModelName;

    /** 模型梯队，1 表示第一梯队，2 表示第二梯队。 */
    @Column(name = "level", nullable = false)
    private Integer level;

     /**
     * 模型得分。
     *
     * <p>该值用于同一梯队内部排序，数值越大越靠前。
     * 例如两个模型都属于第二梯队时，会优先把 score 更高的模型排在前面。</p>
     */
    @Builder.Default
      //noinspection JpaDataSourceORMInspection
    @Column(name = "score", nullable = false)
    private Integer score = 0;

    /** 是否是本地模型（localhost / Ollama）。 */
    @Builder.Default
    @Column(name = "local_model", nullable = false)
    private Boolean localModel = Boolean.FALSE;

    /** 是否支持流式输出。 */
    @Builder.Default
    @Column(name = "supports_stream", nullable = false)
    private Boolean supportsStream = Boolean.TRUE;

    /** 是否启用。 */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /** 模型说明，便于后续管理与排查。 */
    @Column(name = "description", length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

