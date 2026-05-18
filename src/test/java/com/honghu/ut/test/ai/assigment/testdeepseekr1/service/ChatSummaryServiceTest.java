package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.ChatMemoryConfig;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.DefaultSystemPromptProvider;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSessionProfile;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.UserProfileSnapshot;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.memory.MemorySummaryClient;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionProfileRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserProfileSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSummaryServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private ChatSessionProfileRepository chatSessionProfileRepository;
    @Mock
    private UserProfileSnapshotRepository userProfileSnapshotRepository;
    @Mock
    private MemorySummaryClient memorySummaryClient;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private DefaultSystemPromptProvider defaultSystemPromptProvider;

    private ChatSummaryService chatSummaryService;

    @BeforeEach
    void setUp() {
        ChatMemoryConfig config = new ChatMemoryConfig();
        config.setSummaryTriggerRounds(20);
        config.setSummaryModel("deepseek-r1:8b");
        config.setSummaryTemperature(0.2);
        config.setSummaryMaxTokens(512);
        config.setSummaryMaxCharacters(300);
        config.setSummaryContextTokenLimit(4000);
        config.setSessionSummaryRedisKeyPrefix("chat:summary:session:");
        config.setUserProfileRedisKeyPrefix("chat:summary:user:");
        config.setSummaryRedisTtlHours(24 * 30L);
        config.setSummaryLockSeconds(60);
        config.setUserProfileSessionWindow(10);
        config.setSummaryAsyncEnabled(true);

        chatSummaryService = new ChatSummaryService(
                chatMessageRepository,
                chatSessionRepository,
                chatSessionProfileRepository,
                userProfileSnapshotRepository,
                memorySummaryClient,
                stringRedisTemplate,
                config,
                defaultSystemPromptProvider,
                Runnable::run
        );
    }

    @Test
    void shouldSkipSummaryWhenConversationRoundsBelowThreshold() {
        when(chatMessageRepository.countBySessionId("s1")).thenReturn(10L);

        chatSummaryService.refreshSessionSummaryIfNeeded("s1", "u1");

        verify(memorySummaryClient, never()).generateSummary(anyString(), anyString(), anyString());
        verify(chatSessionProfileRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldGenerateSessionAndUserProfilesAndCacheThem() {
        when(defaultSystemPromptProvider.getSessionSummarySystemPromptTemplate()).thenReturn("session-summary-system-%d");
        when(defaultSystemPromptProvider.getUserProfileSystemPromptTemplate()).thenReturn("user-profile-system-%d");
        when(defaultSystemPromptProvider.getSessionUpdateUserPromptTemplate()).thenReturn("%s会话更新:%s|%d");
        when(defaultSystemPromptProvider.getUserProfileAggregationUserPromptTemplate()).thenReturn("用户画像聚合:%s|%d");
        when(defaultSystemPromptProvider.getUserProfileSessionItemPromptTemplate()).thenReturn("最近会话 %d：\n%s\n\n");
        when(defaultSystemPromptProvider.getSessionSummaryWrapperTemplate()).thenReturn("会话中期记忆摘要:%s");
        when(defaultSystemPromptProvider.getUserProfileWrapperTemplate()).thenReturn("用户主体画像:%s");

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("chat:summary:session:lock:s1"), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq("chat:summary:user:lock:u1"), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);

        ChatMessage first = ChatMessage.builder()
                .chatId(1L)
                .sessionId("s1")
                .chatRole("user")
                .content("我更喜欢中文回答")
                .createdAt(LocalDateTime.of(2026, 3, 29, 10, 0))
                .build();
        ChatMessage second = ChatMessage.builder()
                .chatId(2L)
                .sessionId("s1")
                .chatRole("assistant")
                .content("好的，我以后优先使用中文")
                .createdAt(LocalDateTime.of(2026, 3, 29, 10, 1))
                .build();

        when(chatMessageRepository.countBySessionId("s1")).thenReturn(40L);
        when(chatMessageRepository.findMaxChatIdBySessionId("s1")).thenReturn(2L);
        when(chatSessionProfileRepository.findById("s1")).thenReturn(Optional.empty());
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(List.of(first, second));
        when(memorySummaryClient.generateSummary(eq("deepseek-r1:8b"), anyString(), anyString()))
                .thenReturn("用户偏好中文，问题已解决。", "用户长期偏好中文沟通。");
        when(chatSessionRepository.findByUserIdOrderByCreatedAtDesc(eq("u1"), eq(PageRequest.of(0, 10)))).thenReturn(
                List.of(ChatSession.builder().sessionId("s1").userId("u1").build())
        );
        when(chatSessionProfileRepository.findBySessionIdIn(List.of("s1"))).thenReturn(
                List.of(ChatSessionProfile.builder().sessionId("s1").userId("u1").profileSummary("用户偏好中文，问题已解决。").build())
        );

        chatSummaryService.refreshSessionSummaryIfNeeded("s1", "u1");

        verify(chatSessionProfileRepository).save(org.mockito.ArgumentMatchers.argThat(profile ->
                "s1".equals(profile.getSessionId())
                        && Long.valueOf(2L).equals(profile.getLastSummarizedChatId())
                        && profile.getProfileSummary().contains("中文")
        ));
        verify(userProfileSnapshotRepository).save(org.mockito.ArgumentMatchers.argThat(profile ->
                "u1".equals(profile.getUserId())
                        && profile.getProfileSummary().contains("中文")
        ));
        verify(valueOperations).set(eq("chat:summary:session:s1"), anyString(), eq(Duration.ofHours(24 * 30L)));
        verify(valueOperations).set(eq("chat:summary:user:u1"), anyString(), eq(Duration.ofHours(24 * 30L)));
    }

    @Test
    void shouldReadSummaryFromDatabaseAndRepairRedisWhenCacheMissed() {
        when(defaultSystemPromptProvider.getSessionSummaryWrapperTemplate()).thenReturn("会话中期记忆摘要:%s");
        when(defaultSystemPromptProvider.getUserProfileWrapperTemplate()).thenReturn("用户主体画像:%s");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:summary:session:s1")).thenReturn(null);
        when(chatSessionProfileRepository.findById("s1")).thenReturn(Optional.of(
                ChatSessionProfile.builder().sessionId("s1").userId("u1").profileSummary("会话摘要").build()
        ));
        when(valueOperations.get("chat:summary:user:u1")).thenReturn(null);
        when(userProfileSnapshotRepository.findById("u1")).thenReturn(Optional.of(
                UserProfileSnapshot.builder().userId("u1").profileSummary("用户画像").build()
        ));

        List<String> prompts = chatSummaryService.getMemorySystemPrompts("s1", "u1");

        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0)).contains("主体画像");
        assertThat(prompts.get(1)).contains("会话中期记忆摘要");
        verify(valueOperations).set(eq("chat:summary:session:s1"), anyString(), eq(Duration.ofHours(24 * 30L)));
        verify(valueOperations).set(eq("chat:summary:user:u1"), anyString(), eq(Duration.ofHours(24 * 30L)));
    }
}



