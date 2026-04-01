package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSessionProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionProfileRepository extends JpaRepository<ChatSessionProfile, String> {
    List<ChatSessionProfile> findBySessionIdIn(List<String> sessionIds);
}

