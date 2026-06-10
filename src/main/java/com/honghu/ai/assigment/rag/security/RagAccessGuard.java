package com.honghu.ai.assigment.rag.security;

import com.honghu.ai.assigment.entity.ChatSession;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.exception.RagAccessDeniedException;
import com.honghu.ai.assigment.repository.ChatSessionRepository;
import com.honghu.ai.assigment.repository.UserRepository;
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
        // X-User-Id 是当前项目的调用者身份入口，缺失时直接拒绝。
        if (!StringUtils.hasText(headerUserId)) {
            throw new RagAccessDeniedException("缺少调用者 userId 请求头");
        }

        // 必须能在用户表里找到真实用户，否则不接受伪造的 userId。
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
        // 先确认 headerUserId 对应的是一个真实存在的用户。
        User user = requireUser(headerUserId);

        // 再确认 path/body 里的 targetUserId 与 header 声称的身份完全一致。
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
        // 调用者 userId 必须先是合法用户。
        requireUser(callerUserId);
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        // 会话不存在时，也统一按“无权访问”处理，避免向外暴露过多存在性信息。
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RagAccessDeniedException("无权访问该会话"));

        // 会话存在但不归属于当前调用者，也必须拒绝。
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
        // 只有已经通过身份验证的真实用户，才允许创建或接管会话。
        if (caller == null || !StringUtils.hasText(caller.getUserId())) {
            throw new RagAccessDeniedException("调用者 userId 无效");
        }
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        ChatSession existing = chatSessionRepository.findById(sessionId).orElse(null);
        if (existing != null) {
            // 如果会话已经存在，就只允许真正的 owner 继续复用它。
            verifySessionOwnership(caller.getUserId(), sessionId, existing);
            return existing;
        }

        // 不存在则创建一个“占位会话”，让上传、状态查询等流程能挂到这条会话下。
        ChatSession newSession = ChatSession.builder()
                .sessionId(sessionId)
                .userId(caller.getUserId())
                .userName(caller.getUsername())
                .sessionStatus("active")
                .build();
        try {
            return chatSessionRepository.save(newSession);
        } catch (DataIntegrityViolationException ex) {
            // 并发创建同一个 sessionId 时，唯一键会让其中一方失败；
            // 这里再查一次并校验归属即可，避免把正常竞态当成系统错误。
            ChatSession raced = chatSessionRepository.findById(sessionId)
                    .orElseThrow(() -> ex);
            verifySessionOwnership(caller.getUserId(), sessionId, raced);
            return raced;
        }
    }

    /**
     * 校验已有会话是否属于当前调用者。
     *
     * <p>这个私有方法被两个路径复用：</p>
     * <ol>
     *     <li>会话原本就存在，调用者准备继续使用它；</li>
     *     <li>并发创建同一个 sessionId 时，当前线程插入失败后重新查到了别人刚创建的会话。</li>
     * </ol>
     *
     * <p>无论哪种路径，只要 session.userId 与 callerUserId 不一致，都必须拒绝。
     * 这能防止攻击者猜测或复用别人的 sessionId，把文件挂到他人会话下。</p>
     *
     * @param callerUserId 当前请求调用者 userId
     * @param sessionId 请求中携带的 sessionId，仅用于日志定位
     * @param session 数据库中已存在的会话实体
     */
    private void verifySessionOwnership(String callerUserId, String sessionId, ChatSession session) {
        // 这是所有“复用已有会话”路径的最后一道所有权校验。
        if (!Objects.equals(session.getUserId(), callerUserId)) {
            log.warn("拒绝占用他人会话: callerUserId={}, sessionId={}, ownerUserId={}",
                    callerUserId, sessionId, session.getUserId());
            throw new RagAccessDeniedException("无权访问该会话");
        }
    }
}
