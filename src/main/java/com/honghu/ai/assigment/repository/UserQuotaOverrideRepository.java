package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.UserQuotaOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserQuotaOverrideRepository extends JpaRepository<UserQuotaOverride, Long> {

    List<UserQuotaOverride> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query(value = """
            select *
            from user_quota_override o
            where o.user_id = cast(:userId as text)
              and (
                (cast(:workspaceId as text) is null and o.workspace_id is null)
                or o.workspace_id = cast(:workspaceId as text)
              )
              and (o.expires_at is null or o.expires_at > cast(:nowTime as timestamp))
            """, nativeQuery = true)
    List<UserQuotaOverride> findActiveOverrides(@Param("userId") String userId,
                                                @Param("workspaceId") String workspaceId,
                                                @Param("nowTime") LocalDateTime now);
}
