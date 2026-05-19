package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.UserDefaultWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserDefaultWorkspaceRepository extends JpaRepository<UserDefaultWorkspace, String> {
}
