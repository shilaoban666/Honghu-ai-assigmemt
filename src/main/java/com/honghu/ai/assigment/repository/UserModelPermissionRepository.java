package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.UserModelPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserModelPermissionRepository extends JpaRepository<UserModelPermission, Long> {

    List<UserModelPermission> findByUserIdAndEnabledTrue(String userId);

    List<UserModelPermission> findByUserId(String userId);

    List<UserModelPermission> findByUserIdAndWorkspaceId(String userId, String workspaceId);

    Optional<UserModelPermission> findByUserIdAndModelCode(String userId, String modelCode);

    Optional<UserModelPermission> findByUserIdAndWorkspaceIdIsNullAndModelCode(String userId, String modelCode);

    Optional<UserModelPermission> findByUserIdAndWorkspaceIdAndModelCode(String userId, String workspaceId, String modelCode);

    List<UserModelPermission> findByUserIdAndModelCodeIn(String userId, Collection<String> modelCodes);

    void deleteByModelCode(String modelCode);

    @Query(value = """
            select *
            from user_model_permission p
            where p.user_id = cast(:userId as text)
              and p.enabled = true
              and (p.expires_at is null or p.expires_at > cast(:nowTime as timestamp))
              and (p.workspace_id is null or p.workspace_id = cast(:workspaceId as text))
            """, nativeQuery = true)
    List<UserModelPermission> findActiveOverrides(@Param("userId") String userId,
                                                  @Param("workspaceId") String workspaceId,
                                                  @Param("nowTime") LocalDateTime now);
}

