package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.UserDefaultWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserDefaultWorkspaceRepository extends JpaRepository<UserDefaultWorkspace, String> {
}
