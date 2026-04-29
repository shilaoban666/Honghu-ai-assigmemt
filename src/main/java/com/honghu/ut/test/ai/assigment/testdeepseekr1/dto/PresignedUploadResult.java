package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

/**
 * S3 预签名上传结果（内部服务层传输对象）。
 *
 * <p>由 {@code S3UploadService} 生成，包含前端完成三步上传所需的全部信息：</p>
 * <ul>
 *     <li>{@code uploadUrl}   — 前端用 HTTP PUT 直传 S3 的预签名 URL</li>
 *     <li>{@code objectKey}   — 文件在 S3 中的完整路径，上传完成后 POST 到后端用于写 DB</li>
 *     <li>{@code contentType} — 前端 PUT 时 Content-Type 请求头必须携带此值，否则预签名签名校验失败</li>
 * </ul>
 *
 * @author shilaoban
 * @since 2026-04-18
 */
public record PresignedUploadResult(
        String uploadUrl,
        String objectKey,
        String contentType
) {}

