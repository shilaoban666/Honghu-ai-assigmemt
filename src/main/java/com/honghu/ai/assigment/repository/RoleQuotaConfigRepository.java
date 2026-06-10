package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.RoleQuotaConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleQuotaConfigRepository extends JpaRepository<RoleQuotaConfig, String> {
}
