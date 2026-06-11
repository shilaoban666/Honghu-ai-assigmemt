package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanRepository extends JpaRepository<Plan, String> {

    List<Plan> findByEnabledTrueOrderByTierAscPlanCodeAsc();
}
