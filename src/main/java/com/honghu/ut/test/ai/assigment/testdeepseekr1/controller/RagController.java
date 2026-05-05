package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.DownloadUrlResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.RagFileStatusResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.RegisterUploadedFileRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.UploadUrlRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.UploadUrlResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.PresignedUploadResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.RagAccessGuard;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.RagDownloadService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.RagFileRegistrationService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.RagFileStatusStreamer;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.RagIngestionStatusService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.S3UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * RAG 系统控制器 —— <strong>瘦控制器</strong>。
 *
 * <h3>职责边界（重要）</h3>
 * <p>本类只做三件事：</p>
 * <ol>
 *     <li>HTTP 路由 + DTO 解析</li>
 *     <li>调用 service 完成业务（鉴权 / 业务校验 / 数据库读写）</li>
 *     <li>把 service 抛出的异常翻译成合适的 HTTP 状态码 + <strong>脱敏</strong>响应</li>
 * </ol>
 *
 * <p><strong>不</strong>在这里做的事情：</p>
 * <ul>
 *     <li>具体鉴权逻辑（含 UserRepository 查询） → 由 {@link RagAccessGuard} 负责</li>
 *     <li>SSE 调度 / 线程池 / 状态变化判定 → 由 {@link RagFileStatusStreamer} 负责</li>
 *     <li>文件登记 / 文件状态聚合 → 由 service 层负责</li>
 * </ul>
 *
 * <h3>鉴权约定</h3>
 * <p>所有接口必须携带 {@code X-User-Id} 请求头：</p>
 * <ul>
 *     <li>{@code /upload-url}：path 上的 userId 必须等于该头</li>
 *     <li>{@code /files}：登记请求体里的 objectKey 第一段必须等于该用户 username</li>
 *     <li>{@code /files/&#123;fileId&#125;/status} 和 SSE：fileId 对应文档 ownerFolder 必须等于该 username</li>
 *     <li>{@code /sessions/&#123;sessionId&#125;/files}：sessionId 必须由该用户拥有</li>
 * </ul>
 * <p>失败统一以 401 / 403 / 404 返回，<strong>不</strong>暴露底层错误信息。</p>
 *
 * @author shilaoban
 * @since 2026-04-17
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
@Tag(name = "RAG系统", description = "基于 Retrieval-Augmented Generation 的问答系统，包含文件上传")
public class RagController {

    /** 鉴权请求头键名，与 ChatController 保持一致。 */
    static final String USER_ID_HEADER = "X-User-Id";

    private final S3UploadService s3UploadService;
    private final RagFileRegistrationService ragFileRegistrationService;
    private final RagIngestionStatusService ragIngestionStatusService;
    private final RagFileStatusStreamer ragFileStatusStreamer;
    private final RagAccessGuard ragAccessGuard;
    private final RagDownloadService ragDownloadService;
    private final AwsProperties awsProperties;

    // ===========================================================
    //                     1) 预签名上传 URL
    // ===========================================================

    /**
     * 获取文件上传的预签名 URL。
     *
     * <p><strong>鉴权：</strong>path 上的 userId 必须等于 {@code X-User-Id} 头，
     * 否则任意调用者都能为别人 username 路径生成预签名 URL。</p>
     */
    @PostMapping("/{userId}/upload-url")
    @Operation(summary = "获取文件上传预签名 URL",
            description = "根据用户、会话、文件类型生成 S3 预签名上传 URL。" +
                    "路径格式: {bucket}/{username}/{sessionId}/{fileType}/{uuid}/{fileName}")
    public ResponseEntity<UploadUrlResponse> getUploadUrl(
            @RequestHeader(value = USER_ID_HEADER, required = false) @Parameter(description = "调用者 user_id") String headerUserId,
            @PathVariable @Parameter(description = "目标 user_id（必须等于请求头）") String userId,
            @Valid @RequestBody @Parameter(description = "上传请求参数", required = true) UploadUrlRequest request) {

        try {
            ragAccessGuard.requireSameUser(headerUserId, userId);
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(headerUserId, ex);
        }

        // 这里只 log 不会泄露隐私的元数据；原始 fileName 不进 log，避免前端塞 PII。
        log.info("获取上传 URL: userId={}, sessionId={}, fileType={}",
                userId, request.getSessionId(), request.getFileType());

        try {
            PresignedUploadResult result = s3UploadService.generatePresignedUploadUrl(
                    userId, request.getSessionId(), request.getFileType(), request.getFileName());
            return ResponseEntity.ok(toUploadUrlResponse(result, request));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    // ===========================================================
    //                  2) 已上传文件登记
    // ===========================================================

    /**
     * 前端文件直传成功后的登记接口。
     *
     * <p>真正的解析、分块、入库仍由 SQS 异步链路完成；本接口只先写一条
     * {@code RECEIVED} 主记录，让前端立刻能查到状态。</p>
     */
    @PostMapping("/files")
    @Operation(summary = "登记已上传文件", description = "前端完成 S3 PUT 直传后调用，先创建/补齐文件主记录，状态初始为 RECEIVED")
    public ResponseEntity<RagFileStatusResponse> registerUploadedFile(
            @RequestHeader(value = USER_ID_HEADER, required = false) @Parameter(description = "调用者 user_id") String headerUserId,
            @Valid @RequestBody @Parameter(description = "已上传文件登记请求", required = true) RegisterUploadedFileRequest request) {
        try {
            // 鉴权由 service 层做（需要解析 objectKey 才能拿到 ownerFolder），controller 只兜底转 HTTP 错误。
            String fileId = ragFileRegistrationService.registerUploadedFile(
                    headerUserId, request.getObjectKey(), request.getFileName());
            return ResponseEntity.status(HttpStatus.CREATED).body(loadStatusOrThrow(headerUserId, fileId));
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(headerUserId, ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            log.error("文件登记内部状态错误", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂不可用");
        }
    }

    // ===========================================================
    //                3) 状态查询（REST + SSE）
    // ===========================================================

    @GetMapping("/files/{fileId}/status")
    @Operation(summary = "查询单文件 RAG 状态", description = "根据 fileId 查询文件当前是否已完成解析与索引")
    public ResponseEntity<RagFileStatusResponse> getFileStatus(
            @RequestHeader(value = USER_ID_HEADER, required = false) @Parameter(description = "调用者 user_id") String headerUserId,
            @PathVariable @Parameter(description = "文件唯一 ID") String fileId) {
        return ResponseEntity.ok(loadStatusOrThrow(headerUserId, fileId));
    }

    @GetMapping("/files/{fileId}/download-url")
    @Operation(summary = "获取文件下载预签名 URL", description = "为当前用户拥有的附件文件生成短期可用的预签名下载地址")
    public ResponseEntity<DownloadUrlResponse> getDownloadUrl(
            @RequestHeader(value = USER_ID_HEADER, required = false) @Parameter(description = "调用者 user_id") String headerUserId,
            @PathVariable @Parameter(description = "文件唯一 ID") String fileId) {
        try {
            String downloadUrl = ragDownloadService.generatePresignedDownloadUrl(headerUserId, fileId, null);
            long expiresInSeconds = java.time.Duration.ofMinutes(awsProperties.getS3().getPresignedUrlExpirationMinutes()).getSeconds();
            return ResponseEntity.ok(new DownloadUrlResponse(downloadUrl, expiresInSeconds));
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(headerUserId, ex);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到文件");
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @GetMapping("/sessions/{sessionId}/files")
    @Operation(summary = "查询会话下文件状态列表", description = "返回某个 session 下所有文件的最新 RAG 摄取状态")
    public ResponseEntity<List<RagFileStatusResponse>> listSessionFiles(
            @RequestHeader(value = USER_ID_HEADER, required = false) @Parameter(description = "调用者 user_id") String headerUserId,
            @PathVariable @Parameter(description = "会话 ID") String sessionId) {
        try {
            ragAccessGuard.requireUser(headerUserId);
            return ResponseEntity.ok(ragIngestionStatusService.listSessionFiles(headerUserId, sessionId));
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(headerUserId, ex);
        }
    }

    /**
     * 单向实时状态流（SSE）。
     *
     * <p>真正的调度 / 状态变化判定 / 资源回收都委托给 {@link RagFileStatusStreamer}。</p>
     */
    @GetMapping(value = "/files/{fileId}/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅单文件 RAG 状态流", description = "使用 SSE 持续推送文件处理状态，完成后自动结束连接")
    public SseEmitter streamFileStatus(
            @RequestHeader(value = USER_ID_HEADER, required = false) @Parameter(description = "调用者 user_id") String headerUserId,
            @PathVariable @Parameter(description = "文件唯一 ID") String fileId,
            @RequestParam(defaultValue = "60") @Parameter(description = "最大等待秒数") long timeoutSeconds,
            @RequestParam(defaultValue = "1000") @Parameter(description = "轮询数据库的间隔毫秒数") long pollIntervalMillis) {
        try {
            ragAccessGuard.requireUser(headerUserId);
            return ragFileStatusStreamer.stream(headerUserId, fileId, timeoutSeconds, pollIntervalMillis);
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(headerUserId, ex);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到文件状态");
        }
    }

    // ===========================================================
    //                       内部工具方法
    // ===========================================================

    /**
     * 状态查询统一入口：把 service 抛出的鉴权 / 不存在异常翻成 HTTP 错误。
     */
    private RagFileStatusResponse loadStatusOrThrow(String callerUserId, String fileId) {
        try {
            ragAccessGuard.requireUser(callerUserId);
            return ragIngestionStatusService.getFileStatus(callerUserId, fileId);
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(callerUserId, ex);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到文件状态");
        }
    }

    /**
     * 把鉴权异常翻成 HTTP：缺 header → 401，其他越权 → 403。
     *
     * <p>无论哪种情况都不暴露原始 message，避免泄露用户存在性等细节。</p>
     */
    private static ResponseStatusException toHttpAccessError(String headerUserId, RagAccessDeniedException ex) {
        if (headerUserId == null || headerUserId.isBlank()) {
            return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少 " + USER_ID_HEADER + " 请求头");
        }
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该资源");
    }

    /**
     * Service result → REST DTO 的纯映射，不含业务判断。
     */
    private UploadUrlResponse toUploadUrlResponse(PresignedUploadResult result, UploadUrlRequest request) {
        return UploadUrlResponse.builder()
                .uploadUrl(result.uploadUrl())
                .objectKey(result.objectKey())
                .contentType(result.contentType())
                .fileId(result.fileId())
                .fileType(request.getFileType().toLowerCase().trim())
                .expirationMinutes(awsProperties.getS3().getPresignedUrlExpirationMinutes())
                .build();
    }
}
