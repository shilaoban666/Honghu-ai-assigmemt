package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    Optional<WorkspaceMember> findByWorkspaceIdAndUserIdAndStatus(String workspaceId, String userId, String status);

    List<WorkspaceMember> findByUserIdAndStatus(String userId, String status);

    List<WorkspaceMember> findByWorkspaceIdAndStatus(String workspaceId, String status);

    void deleteByWorkspaceIdAndUserId(String workspaceId, String userId);
}
