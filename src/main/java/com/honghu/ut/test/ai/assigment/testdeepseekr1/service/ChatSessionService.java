package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 聊天会话管理服务
 * <p>
 * 负责处理聊天会话的增删查改业务逻辑
 * </p>
 *
 * @author shilaoban
 * @since 2026-03-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class  ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMemoryService chatMemoryService;

    /**
     * 获取指定用户的所有聊天会话
     *
     * @param userId 用户 ID
     * @return 按创建时间倒序排列的聊天会话列表
     */
    public List<ChatSession> getAllSessions(String userId) {
        log.info("获取用户 {} 的所有会话", userId);
        return chatSessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 根据 ID 获取聊天会话
     *
     * @param sessionId 会话 ID
     * @return 聊天会话
     * @throws RuntimeException 当会话不存在时抛出
     */
    public ChatSession getSessionById(String sessionId) {
        log.info("获取会话详情：{}", sessionId);
        return chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> {
                    String errorMsg = String.format("会话不存在：%s", sessionId);
                    log.warn(errorMsg);
                    return new RuntimeException(errorMsg);
                });
    }

    /**
     * 根据 ID 获取聊天会话（返回 Optional）
     *
     * @param sessionId 会话 ID
     * @return 聊天会话的 Optional 包装
     */
    public Optional<ChatSession> getSessionByIdOptional(String sessionId) {
        log.info("获取会话详情：{}", sessionId);
        return chatSessionRepository.findById(sessionId);
    }

    /**
     * 删除聊天会话及其所有消息
     * <p>
     * 该方法会先删除会话下的所有消息，再删除会话本身。
     *
     * <p>一致性说明：</p>
     * <ul>
     *     <li>数据库删除是主动作，Redis 只是短期记忆缓存</li>
     *     <li>因此先完成数据库删除，再在事务提交后清理 Redis</li>
     *     <li>这样可以避免“数据库回滚，但缓存已被提前删除”的不一致问题</li>
     * </ul>
     * </p>
     *
     * @param sessionId 会话 ID
     * @throws RuntimeException 当会话不存在时抛出
     */
    @Transactional
    public void deleteSession(String sessionId) {
        log.info("删除会话：{} 及其所有消息", sessionId);
        
        // 检查会话是否存在
        if (!chatSessionRepository.existsById(sessionId)) {
            String errorMsg = String.format("会话不存在：%s", sessionId);
            log.warn(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        // 先删除该会话下的所有消息
        chatMessageRepository.deleteBySessionId(sessionId);
        log.debug("已删除会话 {} 的所有消息", sessionId);
        
        // 再删除会话本身
        chatSessionRepository.deleteById(sessionId);
        // 事务提交后再清理 Redis，避免数据库删除失败回滚但缓存被提前删掉
        chatMemoryService.clearSessionMemoryAfterCommit(sessionId);
        log.info("会话 {} 已成功删除", sessionId);
    }

    /**
     * 重命名聊天会话
     *
     * @param sessionId 会话 ID
     * @param newName   新的会话名称
     * @return 重命名后的会话
     * @throws RuntimeException 当会话不存在时抛出
     */
    @Transactional
    public ChatSession renameSession(String sessionId, String newName) {
        log.info("重命名会话 {} 为：{}", sessionId, newName);
        
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> {
                    String errorMsg = String.format("会话不存在：%s", sessionId);
                    log.warn(errorMsg);
                    return new RuntimeException(errorMsg);
                });
        
        session.setSessionName(newName);
        ChatSession savedSession = chatSessionRepository.save(session);
        log.info("会话 {} 已重命名为：{}", sessionId, newName);
        
        return savedSession;
    }

    /**
     * 创建新会话
     *
     * @param session 会话对象
     * @return 保存后的会话
     */
    @Transactional
    public ChatSession createSession(ChatSession session) {
        log.info("创建新会话：{}", session.getSessionId());
        return chatSessionRepository.save(session);
    }

    /**
     * 更新会话信息
     *
     * @param session 会话对象
     * @return 更新后的会话
     */
    @Transactional
    public ChatSession updateSession(ChatSession session) {
        log.info("更新会话：{}", session.getSessionId());
        
        if (!chatSessionRepository.existsById(session.getSessionId())) {
            String errorMsg = String.format("会话不存在：%s", session.getSessionId());
            log.warn(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        return chatSessionRepository.save(session);
    }

    /**
     * 检查会话是否存在
     *
     * @param sessionId 会话 ID
     * @return 是否存在
     */
    public boolean existsById(String sessionId) {
        return chatSessionRepository.existsById(sessionId);
    }
}
