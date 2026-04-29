package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.PresignedUploadResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.UploadUrlRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.UploadUrlResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.S3UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * RAG 系统控制器 —— 文件上传入口。
 *
 * <p>前端调用 {@code GET /api/v1/rag/{userId}/upload-url} 获取 S3 预签名上传 URL，
 * 然后使用 HTTP PUT 方法将文件直传到该 URL。</p>
 *
 * <p>文件存储路径规则：</p>
 * <pre>
 *   {bucket}/{username}/{sessionId}/{uuid}/{fileType}/{fileName}
 *   例如: honghu-ai-document-upload/admin/sess_abc123/550e8400.../pdf/report.pdf
 * </pre>
 *
 * @author shilaoban
 * @since 2026-04-17
 */
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "RAG系统", description = "基于 Retrieval-Augmented Generation 的问答系统，包含文件上传")
public class RagController {

    private final S3UploadService s3UploadService;
    private final AwsProperties awsProperties;

    /**
     * 获取文件上传的预签名 URL。
     *
     * <p>根据用户名、sessionId、文件类型自动创建对应文件夹，
     * 返回一个带有效期的 S3 PUT 预签名 URL。前端拿到后直接 PUT 上传文件。</p>
     *
     * @param userId  用户名（如 admin、user1）
     * @param request 请求参数（包含 sessionId、fileType、fileName）
     * @return 预签名上传 URL 及相关信息
     */
    @PostMapping("/{userId}/upload-url")
    @Operation(summary = "获取文件上传预签名 URL",
            description = "根据用户、会话、文件类型生成 S3 预签名上传 URL。" +
                    "路径格式: {bucket}/{username}/{sessionId}/{fileType}/{uuid}/{fileName}")
    public ResponseEntity<UploadUrlResponse> getUploadUrl(
            @PathVariable @Parameter(description = "用户名", example = "admin") String userId,
            @RequestBody @Parameter(description = "上传请求参数", required = true) UploadUrlRequest request) {

        log.info("获取上传 URL: userId={}, sessionId={}, fileType={}, fileName={}", 
                userId, request.getSessionId(), request.getFileType(), request.getFileName());

        // 调用 service 生成预签名 URL，同时拿到 objectKey 和 contentType
        // objectKey 前端必须保存下来，上传完成后 POST /files 时需要传回
        PresignedUploadResult result = s3UploadService.generatePresignedUploadUrl(userId,
                request.getSessionId(),
                request.getFileType(),
                request.getFileName());

        UploadUrlResponse response = UploadUrlResponse.builder()
                .uploadUrl(result.uploadUrl())        // 前端 PUT 直传的目标 URL
                .objectKey(result.objectKey())        // 上传完成后 POST /files 时带上此 key
                .contentType(result.contentType())
                // 前端 PUT 请求头 Content-Type 必须是这个值
                .fileId(result.objectKey().split("/")[3]) // 从 objectKey 中提取 UUID 作为 fileId
                .fileType(request.getFileType().toLowerCase().trim())
                .expirationMinutes(awsProperties.getS3().getPresignedUrlExpirationMinutes())
                .build();

        return ResponseEntity.ok(response);
    }
}
