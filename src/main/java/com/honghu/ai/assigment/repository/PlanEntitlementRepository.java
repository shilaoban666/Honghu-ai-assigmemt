package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.PlanEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 套餐权益仓库。
 *
 * <p>用于查询某个 plan 允许哪些模型、哪些 provider、以及额度类 LIMIT 配置。</p>
 */
@Repository
public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, Long> {

    /** 查询某个套餐下所有已启用权益。 */
    List<PlanEntitlement> findByPlanCodeAndEnabledTrue(String planCode);

    /** 按权益类型筛选，例如只查 ALLOWED_MODEL 或 LIMIT。 */
    List<PlanEntitlement> findByPlanCodeAndEntitlementTypeAndEnabledTrue(
            String planCode,
            PlanEntitlement.EntitlementType entitlementType);

    /** 精确查询某个已启用的权益键。 */
    Optional<PlanEntitlement> findByPlanCodeAndEntitlementTypeAndEntitlementKeyAndEnabledTrue(
            String planCode,
            PlanEntitlement.EntitlementType entitlementType,
            String entitlementKey);

    /** 精确查询某个权益键，不区分 enabled 状态，便于后台编辑已有配置。 */
    Optional<PlanEntitlement> findByPlanCodeAndEntitlementTypeAndEntitlementKey(
            String planCode,
            PlanEntitlement.EntitlementType entitlementType,
            String entitlementKey);

    /** 当模型或 provider 被删除时，按权益类型 + key 清理相关套餐授权。 */
    void deleteByEntitlementTypeAndEntitlementKey(PlanEntitlement.EntitlementType entitlementType, String entitlementKey);
}
