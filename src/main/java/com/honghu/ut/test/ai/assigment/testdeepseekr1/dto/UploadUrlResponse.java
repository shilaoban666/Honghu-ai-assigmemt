package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件上传预签名 URL 响应 DTO。
 *
 * <p>前端拿到该响应后，完整的三步上传流程如下：</p>
 * <ol>
 *     <li>本接口返回此 DTO</li>
 *     <li>前端用 HTTP PUT 请求 {@code uploadUrl}，请求头携带 {@code Content-Type: {contentType}}，Body 为文件二进制内容</li>
 *     <li>上传成功后，前端携带 {@code objectKey} 和文件名调用 POST /api/v1/rag/files 通知后端写 DB 记录</li>
 * </ol>
 *
 * @author shilaoban
 * @since 2026-04-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件上传预签名 URL 响应")
public class UploadUrlResponse {

    @Schema(description = "预签名上传 URL，前端使用 HTTP PUT 方法直传文件到该地址", example = "https://s3.amazonaws.com/...")
    private String uploadUrl;

    @Schema(description = "文件在 S3 中的完整路径 key，上传完成后 POST /files 时必须携带此字段",
            example = "admin/sess_abc123/550e8400-xxx/pdf/report.pdf")
    private String objectKey;

    @Schema(description = "文件 MIME 类型，前端 PUT 时必须在 Content-Type 请求头中携带此值，否则预签名校验失败",
            example = "application/pdf")
    private String contentType;

    @Schema(description = "文件扩展名", example = "pdf")
    private String fileType;

    @Schema(description = "URL 有效时长（分钟），超时后需重新获取", example = "30")
    private int expirationMinutes;

    @Schema(description = "文件的UUID", example = "UUID")
    private String fileId;
}

