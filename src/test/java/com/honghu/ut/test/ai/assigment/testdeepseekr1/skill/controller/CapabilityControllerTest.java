package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.CapabilityService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.CapabilityCategoryDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.CapabilityDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.CapabilityPageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityControllerTest {

    private CapabilityService capabilityService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        capabilityService = mock(CapabilityService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CapabilityController(capabilityService)).build();
    }

    @Test
    void marketplaceShouldReturnUnifiedCapabilityPage() throws Exception {
        CapabilityPageDto page = CapabilityPageDto.builder()
                .items(List.of(cliGit(true)))
                .page(0)
                .size(24)
                .total(1)
                .hasMore(false)
                .categories(List.of(CapabilityCategoryDto.builder()
                        .key("all")
                        .label("All")
                        .icon("All")
                        .count(1)
                        .build()))
                .build();
        when(capabilityService.marketplace("u-1", "sess-1", "cli", "developer", "git", "popular", 0, 24))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/skills/capabilities/marketplace")
                        .header("X-User-Id", "u-1")
                        .param("sessionId", "sess-1")
                        .param("kind", "cli")
                        .param("category", "developer")
                        .param("q", "git")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].skillKey").value("cli:git"))
                .andExpect(jsonPath("$.items[0].kind").value("cli"))
                .andExpect(jsonPath("$.items[0].metadata.command").value("git"))
                .andExpect(jsonPath("$.categories[0].key").value("all"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void installShouldAcceptCapabilityKeysWithColon() throws Exception {
        CapabilityDto skill = CapabilityDto.builder()
                .id("skill:document-writer")
                .skillKey("skill:document-writer")
                .kind("skill")
                .source("CLAUDE_SKILL")
                .name("Document Writer Skill")
                .installed(true)
                .enabled(true)
                .toolNames(List.of())
                .metadata(Map.of("runtime", "prompt"))
                .build();
        when(capabilityService.install("u-1", "sess-1", "skill:document-writer", null)).thenReturn(skill);

        mockMvc.perform(post("/api/v1/skills/capabilities/install")
                        .header("X-User-Id", "u-1")
                        .param("skillKey", "skill:document-writer")
                        .param("sessionId", "sess-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillKey").value("skill:document-writer"))
                .andExpect(jsonPath("$.kind").value("skill"))
                .andExpect(jsonPath("$.installed").value(true));
    }

    @Test
    void sessionToggleShouldPassEnabledFlagToService() throws Exception {
        when(capabilityService.setSessionEnabled("u-1", "sess-1", "cli:git", false))
                .thenReturn(cliGit(false));

        mockMvc.perform(put("/api/v1/sessions/{sessionId}/skills", "sess-1")
                        .header("X-User-Id", "u-1")
                        .param("skillKey", "cli:git")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillKey").value("cli:git"))
                .andExpect(jsonPath("$.enabled").value(false));

        verify(capabilityService).setSessionEnabled("u-1", "sess-1", "cli:git", false);
    }

    private CapabilityDto cliGit(boolean enabled) {
        return CapabilityDto.builder()
                .skillId(10L)
                .id("cli:git")
                .kind("cli")
                .skillKey("cli:git")
                .source("CLI")
                .name("Git CLI")
                .slug("cli-git")
                .description("Controlled Git status and diff helper.")
                .icon("git")
                .accent("#d7f8ef")
                .category("developer")
                .origin("community")
                .publisher("Local")
                .version("v1")
                .rating(new BigDecimal("4.8"))
                .downloads(0)
                .verified(false)
                .installed(true)
                .enabled(enabled)
                .mandatory(false)
                .defaultEnabled(false)
                .requiredRole("USER")
                .toolNames(List.of("gitStatus"))
                .metadata(Map.of(
                        "command", "git",
                        "toolCount", 1,
                        "sandboxed", true
                ))
                .build();
    }
}
