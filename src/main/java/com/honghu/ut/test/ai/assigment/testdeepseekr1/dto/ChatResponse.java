package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应DTO
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "聊天响应结果")
public class ChatResponse {

    @Schema(description = "AI回复内容")
    private String content;

    @Schema(description = "使用的模型名称")
    private String model;

    @Schema(description = "响应时间戳")
    private Long timestamp;

    @Schema(description = "是否成功")
    private Boolean success = true;

    @Schema(description = "错误信息(如有)")
    private String errorMessage;

    @Schema(description = "token使用情况")
    private TokenUsage tokenUsage;

    /**
     * Token使用情况内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Token使用统计")
    public static class TokenUsage {
        @Schema(description = "提示词token数")
        private Integer promptTokens;

        @Schema(description = "生成token数")
        private Integer completionTokens;

        @Schema(description = "总token数")
        private Integer totalTokens;
    }

    /**
     * 构建成功响应
     */
    public static ChatResponse success(String content, String model) {
        return ChatResponse.builder()
                .content(content)
                .model(model)
                .timestamp(System.currentTimeMillis())
                .success(true)
                .build();
    }

    /**
     * 构建错误响应
     */
    public static ChatResponse error(String errorMessage) {
        return ChatResponse.builder()
                .content(null)
                .model(null)
                .timestamp(System.currentTimeMillis())
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}