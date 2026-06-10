package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.UserProfileSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserProfileSnapshotRepository extends JpaRepository<UserProfileSnapshot, String> {
}

