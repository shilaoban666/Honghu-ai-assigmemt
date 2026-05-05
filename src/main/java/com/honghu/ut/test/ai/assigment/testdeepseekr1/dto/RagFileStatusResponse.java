package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 文件摄取状态响应。
 *
 * <p>这个 DTO 专门提供给前端轮询/展示使用，把分散在 `rag_document` 和 `rag_ingestion_event`
 * 两张表里的状态聚合为一个稳定响应，前端无需理解后端表结构。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "RAG 文件摄取状态响应")
public class RagFileStatusResponse {

    @Schema(description = "上传时生成的文件唯一标识", example = "550e8400-e29b-41d4-a716-446655440000")
    private String fileId;

    @Schema(description = "所属会话 ID", example = "sess_abc123")
    private String sessionId;

    @Schema(description = "原始文件名", example = "report.pdf")
    private String fileName;

    @Schema(description = "文件扩展名", example = "pdf")
    private String fileType;

    @Schema(description = "S3 桶名", example = "honghu-ai-document-upload")
    private String bucketName;

    @Schema(description = "S3 objectKey", example = "admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf")
    private String objectKey;

    @Schema(description = "文档主记录状态", example = "PROCESSING")
    private String documentStatus;

    @Schema(description = "摄取事件整体状态", example = "PROCESSING")
    private String ingestionStatus;

    @Schema(description = "RAG 内部阶段状态", example = "CHUNKING")
    private String ragStatus;

    @Schema(description = "是否已进入终态（INDEXED / FAILED / SKIPPED 等）", example = "false")
    private boolean completed;

    @Schema(description = "是否已经可被聊天检索使用", example = "true")
    private boolean availableForChat;

    @Schema(description = "分块数量，仅在成功索引后通常有值", example = "12")
    private Integer chunkCount;

    @Schema(description = "抽取出的字符总数", example = "8456")
    private Integer extractedCharacterCount;

    @Schema(description = "文件大小（字节）", example = "53210")
    private Long fileSize;

    @Schema(description = "详细状态说明/错误信息", example = "开始写入 RAG 索引分块")
    private String detailMessage;

    @Schema(description = "最后一次成功索引时间")
    private LocalDateTime lastIndexedAt;

    @Schema(description = "最近更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "记录创建时间")
    private LocalDateTime createdAt;
}

