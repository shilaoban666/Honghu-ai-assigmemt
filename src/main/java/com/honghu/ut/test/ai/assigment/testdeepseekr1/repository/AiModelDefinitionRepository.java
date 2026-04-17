package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
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
}

