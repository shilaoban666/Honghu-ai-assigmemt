package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagIngestionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RagIngestionEventRepository extends JpaRepository<RagIngestionEvent, Long> {

    /** 按消息幂等键定位某次上传事件。 */
    Optional<RagIngestionEvent> findByDeduplicationKey(String deduplicationKey);

    /**
     * 根据 S3 objectKey 取最近一次摄取事件，便于给前端展示最新阶段。
     *
     * <p>已过时：仅按 objectKey 排序在多 bucket 共用 DB 的环境下会拿错事件；
     * 新调用方请使用 {@link #findFirstByBucketNameAndObjectKeyOrderByCreatedAtDesc}。</p>
     */
    @Deprecated
    Optional<RagIngestionEvent> findFirstByObjectKeyOrderByCreatedAtDesc(String objectKey);

    /** 按 (bucket, objectKey) 取最近一次摄取事件，避免不同 bucket 同 key 错位。 */
    Optional<RagIngestionEvent> findFirstByBucketNameAndObjectKeyOrderByCreatedAtDesc(String bucketName, String objectKey);
}
