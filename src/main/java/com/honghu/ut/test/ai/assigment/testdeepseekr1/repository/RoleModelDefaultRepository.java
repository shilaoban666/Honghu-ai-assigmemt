package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RoleModelDefault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoleModelDefaultRepository extends JpaRepository<RoleModelDefault, Long> {

    List<RoleModelDefault> findByRoleAndEnabledTrue(String role);

    Optional<RoleModelDefault> findByRoleAndModelCode(String role, String modelCode);

    List<RoleModelDefault> findByModelCodeAndEnabledTrue(String modelCode);

    void deleteByRole(String role);

    void deleteByModelCode(String modelCode);

    List<RoleModelDefault> findByRoleInAndEnabledTrue(Collection<String> roles);
}
