package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    List<ChatMessage> findBySessionIdAndChatIdGreaterThanOrderByCreatedAtAsc(String sessionId, Long chatId);
    long countBySessionId(String sessionId);

    @Query("select max(cm.chatId) from ChatMessage cm where cm.sessionId = :sessionId")
    Long findMaxChatIdBySessionId(@Param("sessionId") String sessionId);

    void deleteBySessionId(String sessionId);
}

