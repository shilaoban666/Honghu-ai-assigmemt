package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiTaskKeyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AI 任务路由关键词数据访问层
 */
@Repository
public interface AiTaskKeywordRepository extends JpaRepository<AiTaskKeyword, Long> {

    /**
     * 查询所有启用的关键词，并按优先级排序。
     */
    List<AiTaskKeyword> findByEnabledTrueOrderByPriorityAscIdAsc();
}

