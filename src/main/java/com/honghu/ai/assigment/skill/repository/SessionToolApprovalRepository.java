package com.honghu.ai.assigment.skill.repository;

import com.honghu.ai.assigment.skill.entity.SessionToolApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 会话级危险工具审批 {@link SessionToolApproval} 的持久化入口。
 *
 * <p>{@code ToolGuard} 会在每次 DANGEROUS 工具调用前查询这里，确认当前 sessionId 是否已经批准过
 * 当前 toolQualifiedName，且审批没有过期。</p>
 */
public interface SessionToolApprovalRepository extends JpaRepository<SessionToolApproval, Long> {

    /**
     * 判断某个会话/工具组合是否存在仍然有效的审批。
     *
     * @param sessionId 即将执行危险工具的会话 id
     * @param toolQualifiedName 精确的全局工具名
     * @param now 当前时间，用于排除已过期审批
     * @return true 表示危险工具可以通过审批闸门
     */
    @Query("""
            select count(a) > 0
            from SessionToolApproval a
            where a.sessionId = :sessionId
              and a.toolQualifiedName = :toolQualifiedName
              and (a.expiresAt is null or a.expiresAt > :now)
            """)
    boolean hasValidApproval(@Param("sessionId") String sessionId,
                             @Param("toolQualifiedName") String toolQualifiedName,
                             @Param("now") LocalDateTime now);
}
