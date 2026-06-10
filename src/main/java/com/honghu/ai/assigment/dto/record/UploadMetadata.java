package com.honghu.ai.assigment.dto.record;

/**
 * 应用侧上传完成消息里的扩展元数据。
 *
 * <p>注意：RAG 主链路当前仍以上面的标准七元组为准；这个载体的目标是“保留更多业务字段”，
 * 供后续状态展示、审计、灰度兼容或补充逻辑使用，而不是替代 objectKey 路径解析。</p>
 */
public record UploadMetadata(String fileId,
                             String userId,
                             String username,
                             String sessionId,
                             String originalFilename,
                             String s3Key,
                             String bucket,
                             String objectEtag,
                             String sequencer,
                             String fileType,
                             String contentType,
                             Long fileSize,
                             String uploadedAt,
                             String versionGroupId,
                             Integer version,
                             String rawPayloadJson) {
}
