package com.honghu.ai.assigment.entity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.Map;
/**
 * RAG 文档分块表。
 *
 * <p>文档被切分后的每一小段内容都保存在这里，后续简单检索先从这里召回。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "rag_document_chunk",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_document_chunk_index",
                columnNames = {"document_id", "chunk_index"}
        )
)
public class RagDocumentChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chunk_id")
    private Long chunkId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rag_document_chunk_document"))
    private RagDocument document;
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;
    @Column(name = "char_count")
    private Integer charCount;
    @Column(name = "token_estimate")
    private Integer tokenEstimate;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
