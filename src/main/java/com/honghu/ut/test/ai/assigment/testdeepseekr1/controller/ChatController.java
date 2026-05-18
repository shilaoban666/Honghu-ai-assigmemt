package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatMessageWithAttachmentsResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.RagDocumentProcessService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.ChatService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.security.RagAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI聊天控制器
 * 提供基于Spring AI的聊天接口
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Tag(name = "AI 聊天接口", description = "基于 Spring AI 的聊天服务接口")
public class ChatController {

    /**
     * 请求头中用户 ID 的键名
     */
    private static final String USER_ID_HEADER = "X-User-Id";

    private final ChatService chatService;
    private final ChatMessageRepository chatMessageRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagAccessGuard ragAccessGuard;
    private final RagDocumentProcessService ragDocumentProcessService;

    /**
     * 从请求头提取 userId 并设置到请求对象中
     * <p>
     * 优先级：请求头 X-User-Id > 请求体 userId 字段
     * </p>
     *
     * @param request 聊天请求对象
     * @param headerUserId 请求头中的用户 ID
     */
    private void bindUserIdToRequest(ChatRequest request, String headerUserId) {
        if (headerUserId != null && !headerUserId.isEmpty()) {
            request.setUserId(headerUserId);
            log.info("从请求头获取用户 ID: {}", headerUserId);
        } else {
            log.info("使用请求体中的用户 ID: {}", request.getUserId());
        }
    }

    /**
     * 简单聊天接口
     *
     * @param message 用户输入的消息
     * @return AI回复内容
     */
    @GetMapping("/simple")
    @Operation(summary = "简单聊天", description = "发送消息获取AI回复")
    @ApiResponse(responseCode = "200", description = "成功获取回复",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ChatResponse.class)))
    public ResponseEntity<ChatResponse> simpleChat(
            @Parameter(description = "用户消息") @RequestParam String message) {

        log.info("收到简单聊天请求: {}", message);
        ChatResponse response = chatService.simpleChat(message);
        return ResponseEntity.ok(response);
    }

    /**
     * 结构化聊天接口
     *
     * @param request 聊天请求对象
     * @return AI回复内容
     */
    @PostMapping("/structured")
    @Operation(summary = "结构化聊天", description = "发送结构化请求获取 AI回复")
    @ApiResponse(responseCode = "200", description = "成功获取回复",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ChatResponse.class)))
    public ResponseEntity<ChatResponse> structuredChat(
            @Parameter(description = "聊天请求") @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        
        bindUserIdToRequest(request, userId);
        log.info("收到结构化聊天请求：{}", request);
        ChatResponse response = chatService.structuredChat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 流式聊天接口
     *
     * @param message 用户输入的消息
     * @return SSE流式响应
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式聊天", description = "获取流式的AI回复")
    @ApiResponse(responseCode = "200", description = "成功开始流式传输")
    public Flux<String> streamChat(
            @Parameter(description = "用户消息") @RequestParam String message) {

        log.info("收到流式聊天请求: {}", message);
        return chatService.streamChat(message);
    }

    /**
     * 结构化流式聊天接口
     *
     * @param request 聊天请求对象
     * @return AI回复内容
     */
    @PostMapping("/structured/stream")
    @Operation(summary = "结构化流式聊天", description = "发送结构化请求获取流式AI回复")
    @ApiResponse(responseCode = "200", description = "成功获取回复",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(implementation = ChatResponse.class)))
    public Flux<ChatResponse> structuredStreamChat(
            @Parameter(description = "聊天请求") @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        
        bindUserIdToRequest(request, userId);
        log.info("收到结构化聊天请求：{}", request);
        return chatService.structuredStreamChat(request);
    }

    /**
     * 持久化结构化流式聊天接口
     * 每一个 ChatResponse 对象都会被序列化为一条 data: {...} 的消息
     *
     * @param request 聊天请求对象
     * @return SSE 流式响应 (内部数据为 JSON 格式的 ChatResponse)
     */
    @PostMapping(value = "/structured/stream/persistent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "持久化结构化流式聊天", description = "带会话记忆和数据库存储的流式对话")
    public Flux<ChatResponse> structuredStreamChatPersistent(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        
        bindUserIdToRequest(request, userId);
        log.info("收到持久化结构化流式聊天请求：{}", request);
        return chatService.structuredStreamChatWithPersistence(request);
    }

    /**
     * 查询指定会话的对话历史
     * 
     * @param sessionId 会话 ID，用于唯一标识一个聊天会话
     * @return 按创建时间升序排列的聊天消息列表，包含该会话的所有历史消息
     */
    @GetMapping("/history/{sessionId}")
    @Operation(summary = "查询对话历史")
    public List<ChatMessageWithAttachmentsResponse> getChatHistory(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable String sessionId) {
        try {
            ragAccessGuard.requireOwnedSession(userId, sessionId);
        } catch (RagAccessDeniedException ex) {
            throw toHttpAccessError(userId, ex);
        }

        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Long> chatIds = messages.stream()
                .map(ChatMessage::getChatId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, List<RagDocument>> attachmentsByChatId = new HashMap<>();
        if (!chatIds.isEmpty()) {
            for (RagDocument document : ragDocumentRepository.findByChatIdIn(chatIds)) {
                if (document.getChatId() == null) {
                    continue;
                }
                attachmentsByChatId.computeIfAbsent(document.getChatId(), ignored -> new java.util.ArrayList<>()).add(document);
            }
            attachmentsByChatId.values().forEach(documents -> documents.sort(Comparator.comparing(RagDocument::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))));
        }

        return messages.stream()
                .map(message -> new ChatMessageWithAttachmentsResponse(
                        message.getChatId(),
                        message.getSessionId(),
                        message.getChatRole(),
                        message.getContentType(),
                        message.getContent(),
                        message.getStatus(),
                        message.getCreatedAt(),
                        attachmentsByChatId.getOrDefault(message.getChatId(), Collections.emptyList()).stream()
                                .map(document -> new ChatMessageWithAttachmentsResponse.AttachmentDto(
                                        document.getFileId(),
                                        document.getFileName(),
                                        document.getFileType(),
                                        document.getFileSize(),
                                        document.getStatus() == null ? null : document.getStatus().name(),
                                        maybeGenerateDownloadUrl(userId, document)
                                ))
                                .toList()
                ))
                .toList();
    }

    private String maybeGenerateDownloadUrl(String callerUserId, RagDocument document) {
        if (document == null || document.getStatus() == RagDocument.Status.FAILED || document.getStatus() == RagDocument.Status.SKIPPED) {
            return null;
        }
        try {
            return ragDocumentProcessService.generatePresignedDownloadUrl(callerUserId, document.getFileId(), null);
        } catch (Exception ex) {
            log.warn("生成附件下载链接失败，已返回空链接: fileId={}, chatId={}, error={}",
                    document.getFileId(), document.getChatId(), ex.getMessage());
            return null;
        }
    }

    private static ResponseStatusException toHttpAccessError(String headerUserId, RagAccessDeniedException ex) {
        if (headerUserId == null || headerUserId.isBlank()) {
            return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少 " + USER_ID_HEADER + " 请求头");
        }
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该资源");
    }
}
