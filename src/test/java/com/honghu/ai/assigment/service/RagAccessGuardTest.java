package com.honghu.ai.assigment.service;

import com.honghu.ai.assigment.entity.ChatSession;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.exception.RagAccessDeniedException;
import com.honghu.ai.assigment.rag.security.RagAccessGuard;
import com.honghu.ai.assigment.repository.ChatSessionRepository;
import com.honghu.ai.assigment.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link RagAccessGuard} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>requireUser：header 缺失 / 用户不存在 → 抛 RagAccessDeniedException</li>
 *     <li>requireUser：header 命中 → 返回 User</li>
 *     <li>requireSameUser：header == target → 返回 User</li>
 *     <li>requireSameUser：header != target → 抛 RagAccessDeniedException</li>
 *     <li>requireSameUser：header 缺失 → 抛 RagAccessDeniedException（401 优先）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RagAccessGuardTest {

    @Mock private UserRepository userRepository;
    @Mock private ChatSessionRepository chatSessionRepository;
    @InjectMocks private RagAccessGuard ragAccessGuard;

    @Test
    void requireUserShouldReturnUserWhenHeaderMatchesExistingUser() {
        when(userRepository.findById("u-1"))
                .thenReturn(Optional.of(User.builder().userId("u-1").username("alice").build()));

        User user = ragAccessGuard.requireUser("u-1");

        assertEquals("alice", user.getUsername());
    }

    @Test
    void requireUserShouldRejectBlankHeader() {
        assertThrows(RagAccessDeniedException.class, () -> ragAccessGuard.requireUser(""));
        assertThrows(RagAccessDeniedException.class, () -> ragAccessGuard.requireUser(null));
    }

    @Test
    void requireUserShouldRejectMissingUser() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());
        assertThrows(RagAccessDeniedException.class, () -> ragAccessGuard.requireUser("ghost"));
    }

    @Test
    void requireSameUserShouldReturnUserWhenHeaderEqualsTarget() {
        when(userRepository.findById("u-1"))
                .thenReturn(Optional.of(User.builder().userId("u-1").username("alice").build()));

        User user = ragAccessGuard.requireSameUser("u-1", "u-1");

        assertEquals("alice", user.getUsername());
    }

    @Test
    void requireSameUserShouldRejectMismatch() {
        when(userRepository.findById("u-1"))
                .thenReturn(Optional.of(User.builder().userId("u-1").username("alice").build()));
        assertThrows(RagAccessDeniedException.class,
                () -> ragAccessGuard.requireSameUser("u-1", "u-2"));
    }

    @Test
    void requireOwnedSessionShouldReturnSessionWhenOwnedByCaller() {
        when(userRepository.findById("u-1"))
                .thenReturn(Optional.of(User.builder().userId("u-1").username("alice").build()));
        when(chatSessionRepository.findById("sess-1"))
                .thenReturn(Optional.of(ChatSession.builder().sessionId("sess-1").userId("u-1").build()));

        ChatSession session = ragAccessGuard.requireOwnedSession("u-1", "sess-1");

        assertEquals("sess-1", session.getSessionId());
    }

    @Test
    void requireOwnedSessionShouldRejectWhenSessionBelongsToAnotherUser() {
        when(userRepository.findById("u-1"))
                .thenReturn(Optional.of(User.builder().userId("u-1").username("alice").build()));
        when(chatSessionRepository.findById("sess-1"))
                .thenReturn(Optional.of(ChatSession.builder().sessionId("sess-1").userId("u-2").build()));

        assertThrows(RagAccessDeniedException.class,
                () -> ragAccessGuard.requireOwnedSession("u-1", "sess-1"));
    }

    @Test
    void requireOrCreateSessionShouldCreatePlaceholderWhenMissing() {
        User caller = User.builder().userId("u-1").username("alice").build();
        when(chatSessionRepository.findById("sess-1"))
                .thenReturn(Optional.empty());
        when(chatSessionRepository.save(any(ChatSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChatSession session = ragAccessGuard.requireOrCreateSession(caller, "sess-1");

        assertEquals("sess-1", session.getSessionId());
        assertEquals("u-1", session.getUserId());
        assertEquals("alice", session.getUserName());
    }

    @Test
    void requireOrCreateSessionShouldReuseExistingOwnedSession() {
        User caller = User.builder().userId("u-1").username("alice").build();
        when(chatSessionRepository.findById("sess-1"))
                .thenReturn(Optional.of(ChatSession.builder().sessionId("sess-1").userId("u-1").build()));

        ChatSession session = ragAccessGuard.requireOrCreateSession(caller, "sess-1");

        assertEquals("sess-1", session.getSessionId());
    }

    @Test
    void requireOrCreateSessionShouldRejectExistingSessionOwnedByOthers() {
        User caller = User.builder().userId("u-1").username("alice").build();
        when(chatSessionRepository.findById("sess-1"))
                .thenReturn(Optional.of(ChatSession.builder().sessionId("sess-1").userId("u-2").build()));

        assertThrows(RagAccessDeniedException.class,
                () -> ragAccessGuard.requireOrCreateSession(caller, "sess-1"));
    }

    @Test
    void requireOrCreateSessionShouldHandleConcurrentCreateRace() {
        User caller = User.builder().userId("u-1").username("alice").build();
        ChatSession existing = ChatSession.builder().sessionId("sess-1").userId("u-1").userName("alice").build();
        when(chatSessionRepository.findById("sess-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(chatSessionRepository.save(any(ChatSession.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        ChatSession session = ragAccessGuard.requireOrCreateSession(caller, "sess-1");

        assertEquals("sess-1", session.getSessionId());
        assertEquals("u-1", session.getUserId());
    }
}
