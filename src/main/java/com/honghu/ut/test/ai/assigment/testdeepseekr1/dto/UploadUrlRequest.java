package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取文件上传预签名 URL 的请求参数。
 *
 * @author shilaoban
 * @since 2026-04-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "获取文件上传预签名 URL 请求")
public class UploadUrlRequest {


    @NotBlank(message = "会话 ID 不能为空")
    @Schema(description = "会话 ID", example = "sess_abc123", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sessionId;

    @NotBlank(message = "文件类型不能为空")
    @Schema(description = "文件类型/扩展名", example = "pdf", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileType;

    @Schema(description = "文件尺寸", example = "54544")
    private long fileSize;

    @Schema(description = "原始文件名（可选）", example = "report.pdf")
    private String fileName;
}
