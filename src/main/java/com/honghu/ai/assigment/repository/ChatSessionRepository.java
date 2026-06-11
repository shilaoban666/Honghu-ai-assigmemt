package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.ChatSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    /**
     * 根据用户 ID 查询会话列表，按创建时间倒序排列
     *
     * @param userId 用户 ID
     * @return 按创建时间倒序排列的会话列表
     */
    List<ChatSession> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * 按创建时间倒序获取用户最近若干个会话。
     */
    List<ChatSession> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}

