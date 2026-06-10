package com.honghu.ai.assigment.entity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
/**
 * RAG 文档主表实体类。
 *
 * <p>一条记录代表 S3 中的一个上传对象，以及它当前的解析/索引状态。</p>
 * <p>该实体用于跟踪从S3存储桶中上传的文档文件的状态和元数据信息。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "rag_document",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_document_bucket_object_key",
                columnNames = {"bucket_name", "object_key"}
        )
)
public class RagDocument {
    /**
     * 文档处理状态枚举。
     * RECEIVED: 已接收，等待处理
     * PROCESSING: 正在处理中
     * INDEXED: 已建立索引，可用于检索
     * FAILED: 处理失败
     * SKIPPED: 被跳过处理（如重复文件）
     */
    public enum Status {
        RECEIVED,
        PROCESSING,
        INDEXED,
        FAILED,
        SKIPPED
    }
    /**
     * 文档ID，主键，自增。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Long documentId;
    /**
     * S3存储桶名称。
     */
    @Column(name = "bucket_name", nullable = false, length = 128)
    private String bucketName;
    /**
     * S3对象键（路径），唯一标识S3中的对象。
     */
    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;
    /**
     * S3对象的ETag，用于验证文件完整性。
     */
    @Column(name = "object_etag", length = 128)
    private String objectEtag;
    /**
     * 原始文件名。
     */
    @Column(name = "file_name", length = 255)
    private String fileName;
    /**
     * 文件类型/扩展名。
     */
    @Column(name = "file_type", length = 32)
    private String fileType;
    /**
     * 文件大小（字节）。
     */
    @Column(name = "file_size")
    private Long fileSize;
    /** 
     * S3 路径第一段，对应上传时按用户隔离的目录名。
     * 用于区分不同用户的文件存储空间。
     */
    @Column(name = "owner_folder", length = 128)
    private String ownerFolder;
    /**
     * 关联的会话ID，可选。
     */
    @Column(name = "session_id", length = 128)
    private String sessionId;
    /**
     * 关联的聊天消息 ID。
     *
     * <p>上传发生在 ChatMessage 落库前，因此该字段允许短暂为 {@code null}；
     * 一旦绑定到某条 chat_message，业务上即视为永久不可改写。</p>
     */
    @Column(name = "chat_id")
    private Long chatId;
    /**
     * 上传时生成的文件唯一标识（通常是 UUID）。
     * 用于在系统内唯一标识一个文件实例。
     */
    @Column(name = "file_id", length = 64)
    private String fileId;
    /**
     * 文档当前处理状态，默认为RECEIVED。
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.RECEIVED;
    /**
     * 提取的字符总数。
     */
    @Column(name = "extracted_character_count")
    private Integer extractedCharacterCount;
    /**
     * 分块数量，即文档被分割成的片段数。
     */
    @Column(name = "chunk_count")
    private Integer chunkCount;
    /**
     * 错误信息，当处理失败时记录具体错误原因。
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    /**
     * 最后一次成功建立索引的时间。
     */
    @Column(name = "last_indexed_at")
    private LocalDateTime lastIndexedAt;
    /**
     * 记录创建时间，自动设置且不可更新。
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    /**
     * 记录最后更新时间，每次更新时自动刷新。
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
