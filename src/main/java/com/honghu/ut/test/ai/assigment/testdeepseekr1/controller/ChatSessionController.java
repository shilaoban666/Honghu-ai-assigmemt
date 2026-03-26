package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
@Tag(name = "会话管理", description = "聊天会话的增删查改")
@Slf4j
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @GetMapping
    @Operation(summary = "获取所有会话", description = "获取当前用户的所有聊天会话")
    public List<ChatSession> getAllSessions(@RequestParam(defaultValue = "default-user") String userId) {
        return chatSessionService.getAllSessions(userId);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "获取指定用户的所有会话", description = "根据用户 ID 获取该用户下的所有聊天会话")
    public List<ChatSession> getSessionsByUserId(@PathVariable String userId) {
        log.info("获取用户 {} 的所有会话", userId);
        return chatSessionService.getAllSessions(userId);
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "获取会话详情")
    public ChatSession getSession(@PathVariable String sessionId) {
        return chatSessionService.getSessionById(sessionId);
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "删除会话", description = "物理删除会话及其对应的所有聊天记录")
    public void deleteSession(@PathVariable String sessionId) {
        chatSessionService.deleteSession(sessionId);
    }

    @PutMapping("/{sessionId}/rename")
    @Operation(summary = "重命名会话")
    public ChatSession renameSession(@PathVariable String sessionId, @RequestParam String name) {
        return chatSessionService.renameSession(sessionId, name);
    }
}

