package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.UserModelPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserModelPermissionRepository extends JpaRepository<UserModelPermission, Long> {

    List<UserModelPermission> findByUserIdAndEnabledTrue(String userId);

    List<UserModelPermission> findByUserId(String userId);

    Optional<UserModelPermission> findByUserIdAndModelCode(String userId, String modelCode);

    List<UserModelPermission> findByUserIdAndModelCodeIn(String userId, Collection<String> modelCodes);
}

