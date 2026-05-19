package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagIngestionEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * RAG 文件摄取事件仓库。
 *
 * <p>这张表记录 SQS 上传事件在 RAG 管道中的处理状态，后台监控页会用它展示
 * “收到多少文件、多少成功、多少失败、卡在哪个阶段”。</p>
 */
@Repository
public interface RagIngestionEventRepository extends JpaRepository<RagIngestionEvent, Long> {

    /** 按消息幂等键定位某次上传事件。 */
    Optional<RagIngestionEvent> findByDeduplicationKey(String deduplicationKey);

    /** 旧方法：仅按 objectKey 查询，跨 bucket 时可能不够精确。 */
    @Deprecated
    Optional<RagIngestionEvent> findFirstByObjectKeyOrderByCreatedAtDesc(String objectKey);

    /** 按 bucket + objectKey 查询最近一次事件，避免不同 bucket 同名 key 错位。 */
    Optional<RagIngestionEvent> findFirstByBucketNameAndObjectKeyOrderByCreatedAtDesc(String bucketName, String objectKey);

    /** 后台最近事件列表，按创建时间倒序。 */
    Page<RagIngestionEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按文件最终状态聚合，用于 RAG 监控图表。 */
    @Query(value = """
            select cast(file_status as text) as dimension, count(*) as count
            from rag_ingestion_event
            group by cast(file_status as text)
            order by count desc
            """, nativeQuery = true)
    List<Object[]> aggregateByFileStatus();

    /** 按 RAG 管道阶段聚合，用于观察文件卡在解析、分块、向量化还是入库。 */
    @Query(value = """
            select cast(rag_status as text) as dimension, count(*) as count
            from rag_ingestion_event
            group by cast(rag_status as text)
            order by count desc
            """, nativeQuery = true)
    List<Object[]> aggregateByRagStatus();
}
