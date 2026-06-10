package com.honghu.ai.assigment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.config.ChatMemoryConfig;
import com.honghu.ai.assigment.entity.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMemoryServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ListOperations<String, String> listOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ChatMemoryService chatMemoryService;

    @BeforeEach
    void setUp() {
        ChatMemoryConfig config = new ChatMemoryConfig();
        config.setRedisKeyPrefix("chat:memory:");
        config.setRedisTtlMinutes(30);
        config.setRebuildLockSeconds(15);
        chatMemoryService = new ChatMemoryService(stringRedisTemplate, objectMapper, config);
    }

    @Test
    void shouldRefreshListAndDedupTtlWhenReadingRedisHistory() throws Exception {
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("chat:memory:s1", 0, -1)).thenReturn(List.of("payload"));
        when(objectMapper.readValue(eq("payload"), eq(ChatMemoryService.RedisChatMemoryItem.class))).thenReturn(
                ChatMemoryService.RedisChatMemoryItem.builder()
                        .chatId(1L)
                        .sessionId("s1")
                        .chatRole("user")
                        .contentType("text")
                        .status("active")
                        .content("hello")
                        .createdAt(LocalDateTime.of(2026, 3, 29, 10, 0))
                        .build()
        );

        List<ChatMessage> history = chatMemoryService.getMessages("s1");

        assertThat(history)
                .hasSize(1)
                .extracting(ChatMessage::getContent)
                .containsExactly("hello");
        verify(stringRedisTemplate).expire("chat:memory:s1", Duration.ofMinutes(30));
        verify(stringRedisTemplate).expire("chat:memory:s1:dedup", Duration.ofMinutes(30));
    }

    @Test
    void shouldSkipRebuildWhenAnotherNodeAlreadyReheatedRedisAfterLockAcquired() {
        ChatMessage message = ChatMessage.builder()
                .chatId(1L)
                .sessionId("s1")
                .chatRole("user")
                .content("hello")
                .createdAt(LocalDateTime.of(2026, 3, 29, 10, 0))
                .build();

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(15)))).thenReturn(true);
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size("chat:memory:s1")).thenReturn(2L);
        when(stringRedisTemplate.execute(any(), anyList(), any())).thenReturn(1L);

        chatMemoryService.rebuildSessionMemory("s1", List.of(message));

        verify(stringRedisTemplate, never()).delete("chat:memory:s1");
        verify(stringRedisTemplate, never()).delete("chat:memory:s1:dedup");
        verify(stringRedisTemplate).expire("chat:memory:s1", Duration.ofMinutes(30));
        verify(stringRedisTemplate).expire("chat:memory:s1:dedup", Duration.ofMinutes(30));
    }
}

