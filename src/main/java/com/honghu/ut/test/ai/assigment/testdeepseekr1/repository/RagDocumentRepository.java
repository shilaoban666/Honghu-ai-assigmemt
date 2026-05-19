package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    /** 根据 bucket + objectKey 定位同一个 S3 对象。 */
    Optional<RagDocument> findByBucketNameAndObjectKey(String bucketName, String objectKey);

    /**
     * 根据 fileId 查文档。
     *
     * <p>fileId 来自上传时生成的 UUID，前端最适合作为“单文件状态查询主键”。</p>
     */
    List<RagDocument> findByFileIdOrderByUpdatedAtDesc(String fileId);

    /** 按会话查询该 session 下所有被上传过的文档，用于前端文件列表展示。 */
    List<RagDocument> findBySessionIdOrderByUpdatedAtDescCreatedAtDesc(String sessionId);

    /** 批量查询若干消息下绑定的附件文档。 */
    List<RagDocument> findByChatIdIn(Collection<Long> chatIds);

    @Query("select d.documentId from RagDocument d where d.fileId in :fileIds and d.ownerFolder = :ownerFolder and d.status = :status")
    List<Long> findDocumentIdsByFileIdsAndOwner(@Param("fileIds") List<String> fileIds,
                                                 @Param("ownerFolder") String ownerFolder,
                                                 @Param("status") RagDocument.Status status);

    @Query("select d.documentId from RagDocument d where d.chatId = :chatId and d.ownerFolder = :ownerFolder and d.status = :status")
    List<Long> findDocumentIdsByChatIdAndOwner(@Param("chatId") Long chatId,
                                                @Param("ownerFolder") String ownerFolder,
                                                @Param("status") RagDocument.Status status);

    @Query(value = """
            select *
            from rag_document d
            where (cast(:ownerFolder as text) is null or d.owner_folder = cast(:ownerFolder as text))
              and (cast(:sessionId as text) is null or d.session_id = cast(:sessionId as text))
              and (cast(:fileId as text) is null or d.file_id = cast(:fileId as text))
              and (cast(:status as text) is null or cast(d.status as text) = cast(:status as text))
              and (cast(:q as text) is null
                or lower(coalesce(d.file_name, '')) like concat('%', lower(cast(:q as text)), '%')
                or lower(coalesce(d.object_key, '')) like concat('%', lower(cast(:q as text)), '%'))
            order by d.updated_at desc
            """,
            countQuery = """
            select count(*)
            from rag_document d
            where (cast(:ownerFolder as text) is null or d.owner_folder = cast(:ownerFolder as text))
              and (cast(:sessionId as text) is null or d.session_id = cast(:sessionId as text))
              and (cast(:fileId as text) is null or d.file_id = cast(:fileId as text))
              and (cast(:status as text) is null or cast(d.status as text) = cast(:status as text))
              and (cast(:q as text) is null
                or lower(coalesce(d.file_name, '')) like concat('%', lower(cast(:q as text)), '%')
                or lower(coalesce(d.object_key, '')) like concat('%', lower(cast(:q as text)), '%'))
            """,
            nativeQuery = true)
    Page<RagDocument> searchAdminDocuments(@Param("q") String q,
                                           @Param("ownerFolder") String ownerFolder,
                                           @Param("sessionId") String sessionId,
                                           @Param("fileId") String fileId,
                                           @Param("status") String status,
                                           Pageable pageable);

    @Query(value = """
            select cast(status as text) as dimension, count(*) as count
            from rag_document
            group by cast(status as text)
            order by count desc
            """, nativeQuery = true)
    List<Object[]> aggregateByStatus();

    @Query(value = """
            select coalesce(owner_folder, 'unknown') as dimension, count(*) as documents, coalesce(sum(file_size), 0) as bytes
            from rag_document
            group by coalesce(owner_folder, 'unknown')
            order by documents desc
            """, nativeQuery = true)
    List<Object[]> aggregateByOwner();

    @Query(value = "select coalesce(sum(chunk_count), 0) from rag_document", nativeQuery = true)
    Long sumChunkCount();
}
