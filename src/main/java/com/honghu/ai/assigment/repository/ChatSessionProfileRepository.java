package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.ChatSessionProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionProfileRepository extends JpaRepository<ChatSessionProfile, String> {
    List<ChatSessionProfile> findBySessionIdIn(List<String> sessionIds);
}

