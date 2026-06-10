package com.honghu.ai.assigment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文件下载预签名 URL 响应。
 */
@Schema(description = "文件下载地址响应")
public record DownloadUrlResponse(
        @Schema(description = "预签名下载 URL") String downloadUrl,
        @Schema(description = "链接剩余有效秒数") long expiresInSeconds) {
}

