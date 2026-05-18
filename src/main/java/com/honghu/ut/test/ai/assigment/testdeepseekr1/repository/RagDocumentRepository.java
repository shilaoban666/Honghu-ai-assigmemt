package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
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
}
