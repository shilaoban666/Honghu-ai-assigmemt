package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端上传成功后，回调后端登记文件主记录的请求体。
 *
 * <p>这一步的核心价值是：即使 SQS 事件还没到、后端异步摄取还没开始，
 * 前端也能立刻在状态接口里查到一条 {@code RECEIVED} 记录，而不是 404。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登记已上传文件请求")
public class RegisterUploadedFileRequest {

    @NotBlank(message = "objectKey 不能为空")
    @Schema(description = "文件上传到 S3 后的 objectKey", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf")
    private String objectKey;

    @Schema(description = "原始文件名，可选；为空时后端会从 objectKey 最后一段回退解析",
            example = "report.pdf")
    private String fileName;
}

