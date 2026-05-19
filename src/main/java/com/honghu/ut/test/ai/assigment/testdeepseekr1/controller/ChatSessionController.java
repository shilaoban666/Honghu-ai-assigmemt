package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.security.RagAccessGuard;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
@Tag(name = "Chat Session Management", description = "Chat session list/detail/rename/delete APIs")
public class ChatSessionController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final ChatSessionService chatSessionService;
    private final RagAccessGuard ragAccessGuard;

    @GetMapping
    @Operation(summary = "List current user's chat sessions")
    public List<ChatSession> getAllSessions(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @RequestParam(defaultValue = "default-user") String userId) {
        requireSameUser(headerUserId, userId);
        return chatSessionService.getAllSessions(userId);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "List chat sessions by user id")
    public List<ChatSession> getSessionsByUserId(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String userId) {
        requireSameUser(headerUserId, userId);
        log.info("List chat sessions for userId={}", userId);
        return chatSessionService.getAllSessions(userId);
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get chat session detail")
    public ChatSession getSession(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String sessionId) {
        requireOwnedSession(headerUserId, sessionId);
        return chatSessionService.getSessionById(sessionId);
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Delete chat session and its messages")
    public void deleteSession(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String sessionId) {
        requireOwnedSession(headerUserId, sessionId);
        chatSessionService.deleteSession(sessionId);
    }

    @PutMapping("/{sessionId}/rename")
    @Operation(summary = "Rename chat session")
    public ChatSession renameSession(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String sessionId,
            @RequestParam String name) {
        requireOwnedSession(headerUserId, sessionId);
        return chatSessionService.renameSession(sessionId, name);
    }

    private void requireSameUser(String headerUserId, String userId) {
        try {
            ragAccessGuard.requireSameUser(headerUserId, userId);
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(headerUserId);
        }
    }

    private void requireOwnedSession(String headerUserId, String sessionId) {
        try {
            ragAccessGuard.requireOwnedSession(headerUserId, sessionId);
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(headerUserId);
        }
    }

    private static ResponseStatusException toHttpAccessError(String headerUserId) {
        if (headerUserId == null || headerUserId.isBlank()) {
            return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing " + USER_ID_HEADER + " header");
        }
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
    }
}
