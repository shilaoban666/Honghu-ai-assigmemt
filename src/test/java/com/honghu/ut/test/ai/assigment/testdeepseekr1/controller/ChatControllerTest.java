package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.ChatService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.RagAccessGuard;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.RagDownloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    private ChatService chatService;
    private ChatMessageRepository chatMessageRepository;
    private RagDocumentRepository ragDocumentRepository;
    private RagAccessGuard ragAccessGuard;
    private RagDownloadService ragDownloadService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        ragDocumentRepository = mock(RagDocumentRepository.class);
        ragAccessGuard = mock(RagAccessGuard.class);
        ragDownloadService = mock(RagDownloadService.class);
        ChatController controller = new ChatController(chatService, chatMessageRepository, ragDocumentRepository, ragAccessGuard, ragDownloadService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void historyShouldReturn401WhenHeaderMissing() throws Exception {
        when(ragAccessGuard.requireOwnedSession(null, "sess-1"))
                .thenThrow(new RagAccessDeniedException("缺少调用者 userId 请求头"));

        mockMvc.perform(get("/api/v1/chat/history/{sessionId}", "sess-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void historyShouldReturnMessagesWithAttachments() throws Exception {
        when(ragAccessGuard.requireOwnedSession("u-1", "sess-1"))
                .thenReturn(ChatSession.builder().sessionId("sess-1").userId("u-1").build());
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc("sess-1")).thenReturn(List.of(
                ChatMessage.builder().chatId(10086L).sessionId("sess-1").chatRole("user").contentType("text").content("请总结 PDF").status("active").createdAt(LocalDateTime.now()).build()
        ));
        when(ragDocumentRepository.findByChatIdIn(List.of(10086L))).thenReturn(List.of(
                RagDocument.builder().chatId(10086L).fileId("file-1").fileName("report.pdf").fileType("pdf").fileSize(123L).status(RagDocument.Status.INDEXED).createdAt(LocalDateTime.now()).build()
        ));
        when(ragDownloadService.generatePresignedDownloadUrl("u-1", "file-1", null)).thenReturn("https://s3/download");

        mockMvc.perform(get("/api/v1/chat/history/{sessionId}", "sess-1")
                        .header("X-User-Id", "u-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chatId").value(10086))
                .andExpect(jsonPath("$[0].attachments[0].fileId").value("file-1"))
                .andExpect(jsonPath("$[0].attachments[0].downloadUrl").value("https://s3/download"));
    }
}


