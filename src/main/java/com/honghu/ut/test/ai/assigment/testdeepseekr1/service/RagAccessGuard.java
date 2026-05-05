package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * RAG 接口的"调用者鉴权守卫"。
 *
 * <p>项目当前未引入完整 Spring Security 栈，沿用 {@code X-User-Id} 请求头约定。
 * 这个组件把"header → 真实用户实体 + path/body userId 一致性"两件事集中在一个地方，
 * 避免在每个 controller 里重复实现，也方便单测覆盖。</p>
 *
 * <p>错误统一抛 {@link RagAccessDeniedException}，由 controller 层翻译成 HTTP 401/403。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagAccessGuard {

    private final UserRepository userRepository;
    private final ChatSessionRepository chatSessionRepository;

    /**
     * 仅校验请求头里的 user_id 是真实用户。
     *
     * @param headerUserId {@code X-User-Id} 请求头
     * @return 对应的 {@link User} 实体
     * @throws RagAccessDeniedException 头缺失或用户不存在
     */
    public User requireUser(String headerUserId) {
        if (!StringUtils.hasText(headerUserId)) {
            throw new RagAccessDeniedException("缺少调用者 userId 请求头");
        }
        return userRepository.findById(headerUserId)
                .orElseThrow(() -> new RagAccessDeniedException("用户不存在: " + headerUserId));
    }

    /**
     * 校验请求头里的 userId 必须等于 path/body 上的 targetUserId，并返回该用户。
     *
     * <p>典型场景：{@code POST /api/v1/rag/{userId}/upload-url} —— path 上的
     * userId 不能由调用者随意伪造为别人。</p>
     */
    public User requireSameUser(String headerUserId, String targetUserId) {
        User user = requireUser(headerUserId);
        if (!Objects.equals(headerUserId, targetUserId)) {
            log.warn("跨用户调用被拒绝: header={}, target={}", headerUserId, targetUserId);
            throw new RagAccessDeniedException("无权代表该用户操作");
        }
        return user;
    }

    /**
     * 校验给定 sessionId 必须存在且归属调用者。
     */
    public ChatSession requireOwnedSession(String callerUserId, String sessionId) {
        requireUser(callerUserId);
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RagAccessDeniedException("无权访问该会话"));
        if (!Objects.equals(session.getUserId(), callerUserId)) {
            log.warn("拒绝访问非本人会话: callerUserId={}, sessionId={}, ownerUserId={}",
                    callerUserId, sessionId, session.getUserId());
            throw new RagAccessDeniedException("无权访问该会话");
        }
        return session;
    }

    /**
     * 确保会话存在，若不存在则以当前调用者身份创建一个占位会话。
     */
    @Transactional
    public ChatSession requireOrCreateSession(User caller, String sessionId) {
        if (caller == null || !StringUtils.hasText(caller.getUserId())) {
            throw new RagAccessDeniedException("调用者 userId 无效");
        }
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        ChatSession existing = chatSessionRepository.findById(sessionId).orElse(null);
        if (existing != null) {
            verifySessionOwnership(caller.getUserId(), sessionId, existing);
            return existing;
        }

        ChatSession newSession = ChatSession.builder()
                .sessionId(sessionId)
                .userId(caller.getUserId())
                .userName(caller.getUsername())
                .sessionStatus("active")
                .build();
        try {
            return chatSessionRepository.save(newSession);
        } catch (DataIntegrityViolationException ex) {
            ChatSession raced = chatSessionRepository.findById(sessionId)
                    .orElseThrow(() -> ex);
            verifySessionOwnership(caller.getUserId(), sessionId, raced);
            return raced;
        }
    }

    private void verifySessionOwnership(String callerUserId, String sessionId, ChatSession session) {
        if (!Objects.equals(session.getUserId(), callerUserId)) {
            log.warn("拒绝占用他人会话: callerUserId={}, sessionId={}, ownerUserId={}",
                    callerUserId, sessionId, session.getUserId());
            throw new RagAccessDeniedException("无权访问该会话");
        }
    }
}
