package com.honghu.ut.test.ai.assigment.testdeepseekr1.entity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
/**
 * RAG 摄取事件表。
 *
 * <p>用于记录 SQS 消息消费状态，解决至少一次投递场景下的幂等问题。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "rag_ingestion_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_ingestion_event_deduplication_key",
                columnNames = "deduplication_key"
        )
)
public class RagIngestionEvent {
    /**
     * SQS 文件事件整体处理状态。
     *
     * <p>这是“消息级”的终态，主要回答：这条上传事件最终有没有处理完。</p>
     */
    public enum FileStatus {
        RECEIVED,
        PROCESSING,
        SUCCESS,
        FAILED,
        SKIPPED
    }

    /**
     * RAG 内部阶段状态。
     *
     * <p>相比 {@link FileStatus} 更细，用来向前端展示“现在走到哪一步了”。</p>
     */
    public enum RagStatus {
        RECEIVED,
        PARSING,
        DOWNLOADING,
        EXTRACTING,
        CHUNKING,
        EMBEDDING,
        INDEXING,
        SUCCESS,
        SKIPPED,
        FAILED,
    }
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;
    @Column(name = "queue_message_id", length = 128)
    private String queueMessageId;
    @Column(name = "deduplication_key", nullable = false, length = 2048)
    private String deduplicationKey;
    @Column(name = "bucket_name", length = 128)
    private String bucketName;
    @Column(name = "object_key", length = 1024)
    private String objectKey;
    @Column(name = "event_name", length = 128)
    private String eventName;
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "file_status", nullable = false, length = 32)
    private FileStatus fileStatus = FileStatus.RECEIVED;

    @Enumerated(EnumType.STRING)
    @Column(name = "rag_status", nullable = false, length = 32)
    private RagStatus ragStatus = RagStatus.RECEIVED;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "processed_at")
    private LocalDateTime processedAt;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
