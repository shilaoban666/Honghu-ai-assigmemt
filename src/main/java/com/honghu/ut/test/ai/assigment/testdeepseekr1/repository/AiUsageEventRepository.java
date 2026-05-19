package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiUsageEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 用量流水仓库。
 *
 * <p>这里既承担单条流水的保存/查询，也承担后台报表和配额统计所需的聚合查询。</p>
 */
@Repository
public interface AiUsageEventRepository extends JpaRepository<AiUsageEvent, Long> {

    /** 检查 requestId + attemptNo 这组幂等键是否已经写过流水。 */
    boolean existsByRequestIdAndAttemptNo(String requestId, Integer attemptNo);

    /** 统计个人或个人 workspace 在某个时间窗口内已成功扣费的金额。 */
    @Query(value = """
            select coalesce(sum(cost_billed), 0)
            from ai_usage_event
            where user_id = cast(:userId as text)
              and status = cast(:status as text)
              and created_at >= cast(:fromTime as timestamp)
              and (
                (cast(:workspaceId as text) is null and workspace_id is null)
                or workspace_id = cast(:workspaceId as text)
              )
            """, nativeQuery = true)
    BigDecimal sumSuccessfulCostByUser(@Param("userId") String userId,
                                       @Param("workspaceId") String workspaceId,
                                       @Param("status") String status,
                                       @Param("fromTime") LocalDateTime from);

    /** 统计企业/团队 workspace 在某个时间窗口内已成功扣费的金额。 */
    @Query(value = """
            select coalesce(sum(cost_billed), 0)
            from ai_usage_event
            where workspace_id = cast(:workspaceId as text)
              and status = cast(:status as text)
              and created_at >= cast(:fromTime as timestamp)
            """, nativeQuery = true)
    BigDecimal sumSuccessfulCostByWorkspace(@Param("workspaceId") String workspaceId,
                                            @Param("status") String status,
                                            @Param("fromTime") LocalDateTime from);

    /** 统计个人或个人 workspace 在某个时间窗口内产生的真实 token。 */
    @Query(value = """
            select coalesce(sum(total_tokens), 0)
            from ai_usage_event
            where user_id = cast(:userId as text)
              and status = cast(:status as text)
              and created_at >= cast(:fromTime as timestamp)
              and (
                (cast(:workspaceId as text) is null and workspace_id is null)
                or workspace_id = cast(:workspaceId as text)
              )
            """, nativeQuery = true)
    BigDecimal sumSuccessfulTokensByUser(@Param("userId") String userId,
                                         @Param("workspaceId") String workspaceId,
                                         @Param("status") String status,
                                         @Param("fromTime") LocalDateTime from);

    /** 统计企业/团队 workspace 在某个时间窗口内产生的真实 token。 */
    @Query(value = """
            select coalesce(sum(total_tokens), 0)
            from ai_usage_event
            where workspace_id = cast(:workspaceId as text)
              and status = cast(:status as text)
              and created_at >= cast(:fromTime as timestamp)
            """, nativeQuery = true)
    BigDecimal sumSuccessfulTokensByWorkspace(@Param("workspaceId") String workspaceId,
                                              @Param("status") String status,
                                              @Param("fromTime") LocalDateTime from);

    /** 查看某个用户最近的用量流水，供后台或个人中心分页查看。 */
    Page<AiUsageEvent> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** 统计模型最近 24 小时等窗口内的调用次数，供后台模型概览使用。 */
    long countByModelCodeAndCreatedAtAfter(String modelCode, LocalDateTime createdAt);

    /** 删除某个模型的历史流水，通常只在后台删除模型时联动清理。 */
    void deleteByModelCode(String modelCode);

    /** 后台用量流水多条件检索。 */
    @Query(value = """
            select *
            from ai_usage_event
            where (cast(:userId as text) is null or user_id = cast(:userId as text))
              and (cast(:workspaceId as text) is null or workspace_id = cast(:workspaceId as text))
              and (cast(:modelCode as text) is null or model_code = cast(:modelCode as text))
              and (cast(:status as text) is null or status = cast(:status as text))
              and (cast(:fromTime as timestamp) is null or created_at >= cast(:fromTime as timestamp))
              and (cast(:toTime as timestamp) is null or created_at < cast(:toTime as timestamp))
            order by created_at desc
            """,
            countQuery = """
            select count(*)
            from ai_usage_event
            where (cast(:userId as text) is null or user_id = cast(:userId as text))
              and (cast(:workspaceId as text) is null or workspace_id = cast(:workspaceId as text))
              and (cast(:modelCode as text) is null or model_code = cast(:modelCode as text))
              and (cast(:status as text) is null or status = cast(:status as text))
              and (cast(:fromTime as timestamp) is null or created_at >= cast(:fromTime as timestamp))
              and (cast(:toTime as timestamp) is null or created_at < cast(:toTime as timestamp))
            """,
            nativeQuery = true)
    Page<AiUsageEvent> search(@Param("userId") String userId,
                              @Param("workspaceId") String workspaceId,
                              @Param("modelCode") String modelCode,
                              @Param("status") String status,
                              @Param("fromTime") LocalDateTime from,
                              @Param("toTime") LocalDateTime to,
                              Pageable pageable);

    /** 后台报表聚合接口，可按 user/workspace/provider/model 四种维度汇总。 */
    @Query(value = """
            select
              case
                when cast(:groupBy as text) = 'user' then coalesce(user_id, 'anonymous')
                when cast(:groupBy as text) = 'workspace' then coalesce(workspace_id, 'none')
                when cast(:groupBy as text) = 'provider' then coalesce(provider_code, 'unknown')
                else coalesce(model_code, 'unknown')
              end as dimension,
              count(*) as calls,
              coalesce(sum(total_tokens), 0) as tokens,
              coalesce(sum(cost_billed), 0) as cost,
              coalesce(sum(cost_billed), 0) * 1000000 as standard_tokens,
              coalesce(sum(case when status <> 'SUCCESS' then 1 else 0 end), 0) as failed_calls,
              coalesce(sum(case when status = 'BLOCKED_BY_QUOTA' then 1 else 0 end), 0) as blocked_calls,
              coalesce(sum(case when status = 'SUCCESS' then 1 else 0 end), 0) as success_calls
            from ai_usage_event
            where (cast(:fromTime as timestamp) is null or created_at >= cast(:fromTime as timestamp))
              and (cast(:toTime as timestamp) is null or created_at < cast(:toTime as timestamp))
            group by dimension
            order by cost desc
            """, nativeQuery = true)
    List<Object[]> aggregate(@Param("groupBy") String groupBy,
                             @Param("fromTime") LocalDateTime fromTime,
                             @Param("toTime") LocalDateTime toTime);
}
