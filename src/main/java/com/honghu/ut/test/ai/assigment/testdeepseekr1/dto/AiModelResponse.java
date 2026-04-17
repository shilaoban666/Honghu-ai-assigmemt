package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 模型响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI 模型信息")
public class AiModelResponse {

	@Schema(description = "模型编码", example = "gpt-5.1")
	private String modelCode;

	@Schema(description = "展示名称", example = "GPT-5.1")
	private String displayName;

	@Schema(description = "provider 编码", example = "openai")
	private String providerCode;

	@Schema(description = "真实 API model name", example = "gpt-5.1")
	private String apiModelName;

	@Schema(description = "模型梯队，1 为第一梯队，2 为第二梯队", example = "1")
	private Integer level;

	@Schema(description = "模型得分，同一梯队内按得分倒序排序", example = "57")
	private Integer score;

	@Schema(description = "是否本地模型", example = "false")
	private Boolean localModel;

	@Schema(description = "是否支持流式输出", example = "true")
	private Boolean supportsStream;

	@Schema(description = "模型说明")
	private String description;

	public static AiModelResponse fromEntity(AiModelDefinition model) {
		return AiModelResponse.builder()
				.modelCode(model.getModelCode())
				.displayName(model.getDisplayName())
				.providerCode(model.getProviderCode())
				.apiModelName(model.getApiModelName())
				.level(model.getLevel())
				.score(model.getScore())
				.localModel(model.getLocalModel())
				.supportsStream(model.getSupportsStream())
				.description(model.getDescription())
				.build();
	}
}

