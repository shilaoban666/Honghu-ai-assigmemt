package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.security.RagAccessGuard;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.ChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatSessionControllerTest {

    private ChatSessionService chatSessionService;
    private RagAccessGuard ragAccessGuard;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatSessionService = mock(ChatSessionService.class);
        ragAccessGuard = mock(RagAccessGuard.class);
        ChatSessionController controller = new ChatSessionController(chatSessionService, ragAccessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listSessionsShouldReturn401WhenHeaderMissing() throws Exception {
        when(ragAccessGuard.requireSameUser(null, "u-1"))
                .thenThrow(new RagAccessDeniedException("missing user header"));

        mockMvc.perform(get("/api/v1/sessions/user/{userId}", "u-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listSessionsShouldRequireSameUserAndReturnData() throws Exception {
        when(chatSessionService.getAllSessions("u-1")).thenReturn(List.of(
                ChatSession.builder().sessionId("sess-1").userId("u-1").sessionName("first").build()
        ));

        mockMvc.perform(get("/api/v1/sessions/user/{userId}", "u-1")
                        .header("X-User-Id", "u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("sess-1"));

        verify(ragAccessGuard).requireSameUser("u-1", "u-1");
    }

    @Test
    void sessionDetailShouldReturn403WhenSessionIsOwnedByAnotherUser() throws Exception {
        when(ragAccessGuard.requireOwnedSession("u-1", "sess-2"))
                .thenThrow(new RagAccessDeniedException("forbidden"));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}", "sess-2")
                        .header("X-User-Id", "u-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteSessionShouldVerifyOwnershipBeforeDeleting() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/{sessionId}", "sess-1")
                        .header("X-User-Id", "u-1"))
                .andExpect(status().isOk());

        verify(ragAccessGuard).requireOwnedSession("u-1", "sess-1");
        verify(chatSessionService).deleteSession("sess-1");
    }

    @Test
    void renameSessionShouldVerifyOwnershipBeforeRenaming() throws Exception {
        when(chatSessionService.renameSession("sess-1", "new name"))
                .thenReturn(ChatSession.builder().sessionId("sess-1").userId("u-1").sessionName("new name").build());

        mockMvc.perform(put("/api/v1/sessions/{sessionId}/rename", "sess-1")
                        .header("X-User-Id", "u-1")
                        .param("name", "new name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionName").value("new name"));

        verify(ragAccessGuard).requireOwnedSession("u-1", "sess-1");
    }
}
