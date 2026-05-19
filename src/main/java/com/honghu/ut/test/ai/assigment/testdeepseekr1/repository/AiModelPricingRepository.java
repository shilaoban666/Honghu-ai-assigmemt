package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelPricing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 模型价格快照仓库。
 *
 * <p>核心任务是按“模型编码 + 调用发生时间”找出当时生效的那条价格配置。</p>
 */
@Repository
public interface AiModelPricingRepository extends JpaRepository<AiModelPricing, Long> {

    /** 查询某个时间点上所有命中的启用价格候选，按生效开始时间倒序排列。 */
    @Query("""
            select p from AiModelPricing p
            where p.modelCode = :modelCode
              and p.enabled = true
              and p.effectiveFrom <= :at
              and (p.effectiveTo is null or p.effectiveTo > :at)
            order by p.effectiveFrom desc
            """)
    List<AiModelPricing> findActiveCandidates(@Param("modelCode") String modelCode, @Param("at") LocalDateTime at);

    /** 取某个时间点真正应该生效的第一条价格。 */
    default Optional<AiModelPricing> findActivePricing(String modelCode, LocalDateTime at) {
        return findActiveCandidates(modelCode, at).stream().findFirst();
    }

    /** 查看某个模型的价格历史，方便后台价格管理页面展示时间轴。 */
    List<AiModelPricing> findByModelCodeOrderByEffectiveFromDesc(String modelCode);

    /** 删除某个模型全部价格快照，通常只在后台删除模型时联动调用。 */
    void deleteByModelCode(String modelCode);
}
