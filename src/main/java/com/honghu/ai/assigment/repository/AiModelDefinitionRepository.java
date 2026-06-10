package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.AiModelDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiModelDefinitionRepository extends JpaRepository<AiModelDefinition, String> {

    List<AiModelDefinition> findByEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc();

    List<AiModelDefinition> findByLocalModelTrueAndEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc();

    List<AiModelDefinition> findByModelCodeInAndEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc(Collection<String> modelCodes);

    Optional<AiModelDefinition> findByModelCodeAndEnabledTrue(String modelCode);

    /** 统计引用某 provider 的模型数量；删除 provider 前用于安全校验。 */
    long countByProviderCode(String providerCode);
}

