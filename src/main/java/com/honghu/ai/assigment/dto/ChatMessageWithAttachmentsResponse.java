package com.honghu.ai.assigment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话历史接口返回的消息 + 附件聚合 DTO。
 */
@Schema(description = "聊天消息及其附件")
public record ChatMessageWithAttachmentsResponse(
        @Schema(description = "聊天消息主键") Long chatId,
        @Schema(description = "所属会话 ID") String sessionId,
        @Schema(description = "消息角色") String chatRole,
        @Schema(description = "内容类型") String contentType,
        @Schema(description = "消息内容") String content,
        @Schema(description = "消息状态") String status,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "消息下绑定的附件列表") List<AttachmentDto> attachments) {

    @Schema(description = "聊天消息下展示的附件元数据")
    public record AttachmentDto(
            @Schema(description = "文件唯一 ID") String fileId,
            @Schema(description = "原始文件名") String fileName,
            @Schema(description = "文件类型") String fileType,
            @Schema(description = "文件大小") Long fileSize,
            @Schema(description = "文档处理状态") String documentStatus,
            @Schema(description = "预签名下载 URL，失败/跳过时可能为空") String downloadUrl) {
    }
}

