package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocumentChunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface RagDocumentChunkRepository extends JpaRepository<RagDocumentChunk, Long> {
    void deleteByDocument_DocumentId(Long documentId);

    @Query("select c from RagDocumentChunk c join c.document d where d.sessionId = :sessionId and d.status = :status order by d.updatedAt desc, c.chunkIndex asc")
    List<RagDocumentChunk> findCandidateChunksBySessionId(@Param("sessionId") String sessionId,
                                                          @Param("status") RagDocument.Status status,
                                                          Pageable pageable);

    /**
     * 与 {@link #findCandidateChunksBySessionId} 类似，但额外按 owner（即 S3 路径第一段 username）过滤。
     *
     * <p>RAG 检索调用方<strong>必须</strong>使用这个方法而不是只按 sessionId 的版本，
     * 否则一旦攻击者拿到别人的 sessionId（路径可枚举或日志泄漏），即可读到对方文档原文。</p>
     */
    @Query("select c from RagDocumentChunk c join c.document d where d.sessionId = :sessionId and d.ownerFolder = :ownerFolder and d.status = :status order by d.updatedAt desc, c.chunkIndex asc")
    List<RagDocumentChunk> findCandidateChunksBySessionIdAndOwner(@Param("sessionId") String sessionId,
                                                                  @Param("ownerFolder") String ownerFolder,
                                                                  @Param("status") RagDocument.Status status,
                                                                  Pageable pageable);

    @Query("select c from RagDocumentChunk c join fetch c.document d where d.fileId in :fileIds and d.ownerFolder = :ownerFolder and d.status = :status order by d.updatedAt desc, c.chunkIndex asc")
    List<RagDocumentChunk> findCandidateChunksByFileIdsAndOwner(@Param("fileIds") List<String> fileIds,
                                                                 @Param("ownerFolder") String ownerFolder,
                                                                 @Param("status") RagDocument.Status status,
                                                                 Pageable pageable);

    @Query("select c from RagDocumentChunk c join fetch c.document d where d.chatId = :chatId and d.ownerFolder = :ownerFolder and d.status = :status order by d.updatedAt desc, c.chunkIndex asc")
    List<RagDocumentChunk> findCandidateChunksByChatIdAndOwner(@Param("chatId") Long chatId,
                                                                @Param("ownerFolder") String ownerFolder,
                                                                @Param("status") RagDocument.Status status,
                                                                Pageable pageable);

    @Query("select c from RagDocumentChunk c join fetch c.document d where d.documentId = :documentId order by c.chunkIndex asc")
    List<RagDocumentChunk> findByDocumentIdWithDocument(@Param("documentId") Long documentId);

    /**
     * 按 chunkId 游标分页扫描“已 INDEXED 文档”的 chunk。
     *
     * <p>这个查询主要服务于历史向量回填：相比 OFFSET/LIMIT，基于自增主键的游标分页在大表上更稳定，
     * 也更适合长时间运行的后台任务。</p>
     */
    @Query("select c from RagDocumentChunk c join fetch c.document d where c.chunkId > :lastId and d.status = :status order by c.chunkId asc")
    List<RagDocumentChunk> findBatchAfterId(@Param("lastId") Long lastId,
                                            @Param("status") RagDocument.Status status,
                                            Pageable pageable);
}
