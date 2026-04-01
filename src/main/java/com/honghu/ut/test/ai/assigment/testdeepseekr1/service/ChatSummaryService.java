package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.ChatMemoryConfig;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.DefaultSystemPromptProvider;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSessionProfile;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.UserProfileSnapshot;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionProfileRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserProfileSnapshotRepository;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 第二层中期记忆服务。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>当会话超过指定轮数后，异步把长对话压缩成会话级摘要</li>
 *     <li>把最近十个 session 的摘要再聚合成用户主体画像</li>
 *     <li>数据库保存真源，Redis 保存可快速注入 Prompt 的长期缓存副本</li>
 * </ul>
 *
 * <p>一致性策略：</p>
 * <ul>
 *     <li>数据库是最终真源，Redis 只是加速缓存</li>
 *     <li>摘要写入顺序是“先 DB，后 Redis”，避免 Redis 比 DB 更早可见</li>
 *     <li>读取时支持 DB 回源 + 回填 Redis，修复缓存丢失</li>
 *     <li>使用 Redis 分布式锁防止多节点重复压缩同一个 session / user</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatSummaryService {

    private static final EncodingRegistry TOKEN_REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding TOKEN_ENCODING = TOKEN_REGISTRY.getEncoding(EncodingType.CL100K_BASE);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
    private static final String SESSION_SUMMARY_LOCK_PREFIX = "chat:summary:session:lock:";
    private static final String USER_SUMMARY_LOCK_PREFIX = "chat:summary:user:lock:";

    static {
        RELEASE_LOCK_SCRIPT.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('DEL', KEYS[1]); " +
                        "else return 0; end"
        );
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
    }

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionProfileRepository chatSessionProfileRepository;
    private final UserProfileSnapshotRepository userProfileSnapshotRepository;
    private final MemorySummaryClient memorySummaryClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ChatMemoryConfig chatMemoryConfig;
    private final DefaultSystemPromptProvider promptProvider;
    private final Executor memorySummaryTaskExecutor;

    public ChatSummaryService(
            ChatMessageRepository chatMessageRepository,
            ChatSessionRepository chatSessionRepository,
            ChatSessionProfileRepository chatSessionProfileRepository,
            UserProfileSnapshotRepository userProfileSnapshotRepository,
            MemorySummaryClient memorySummaryClient,
            StringRedisTemplate stringRedisTemplate,
            ChatMemoryConfig chatMemoryConfig,
            DefaultSystemPromptProvider promptProvider,
            @Qualifier("memorySummaryTaskExecutor") Executor memorySummaryTaskExecutor) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatSessionProfileRepository = chatSessionProfileRepository;
        this.userProfileSnapshotRepository = userProfileSnapshotRepository;
        this.memorySummaryClient = memorySummaryClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.chatMemoryConfig = chatMemoryConfig;
        this.promptProvider = promptProvider;
        this.memorySummaryTaskExecutor = memorySummaryTaskExecutor;
    }

    /**
     * 异步触发会话摘要刷新。
     *
     * <p>这里不直接使用 {@code @Async} 标注方法，而是手动切换到线程池：</p>
     * <ul>
     *     <li>这样 {@code summaryAsyncEnabled} 开关才能真正控制“异步/同步”行为</li>
     *     <li>也便于在线程池拒绝任务时做同步降级，避免摘要任务静默丢失</li>
     * </ul>
     */
    public void triggerRefreshSessionSummaryAsync(String sessionId, String userId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId)) {
            return;
        }

        if (!chatMemoryConfig.isSummaryAsyncEnabled()) {
            safeRefreshSessionSummary(sessionId, userId);
            return;
        }

        try {
            memorySummaryTaskExecutor.execute(() -> safeRefreshSessionSummary(sessionId, userId));
        } catch (RuntimeException e) {
            // 当线程池繁忙或拒绝任务时，退化为当前线程执行，保证摘要任务不会因为排队失败而直接丢失。
            log.warn("异步线程池提交摘要任务失败，改为同步执行，sessionId={}, userId={}: {}", sessionId, userId, e.getMessage());
            safeRefreshSessionSummary(sessionId, userId);
        }
    }

    private void safeRefreshSessionSummary(String sessionId, String userId) {
        try {
            refreshSessionSummaryIfNeeded(sessionId, userId);
        } catch (Exception e) {
            log.warn("刷新中期记忆失败，sessionId={}, userId={}: {}", sessionId, userId, e.getMessage(), e);
        }
    }

    /**
     * 同步检查并刷新指定会话的中期记忆。
     *
     * <p>这里既可以被异步入口调用，也可以在单元测试中直接调用，便于验证逻辑。</p>
     */
    public void refreshSessionSummaryIfNeeded(String sessionId, String userId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId)) {
            return;
        }

        long totalMessages = chatMessageRepository.countBySessionId(sessionId);
        if (totalMessages < resolveSummaryTriggerMessageCount()) {
            log.debug("会话尚未达到中期记忆压缩阈值，sessionId={}, messageCount={}, threshold={}",
                    sessionId, totalMessages, resolveSummaryTriggerMessageCount());
            return;
        }

        Long latestChatId = chatMessageRepository.findMaxChatIdBySessionId(sessionId);
        if (latestChatId == null) {
            return;
        }

        String lockKey = SESSION_SUMMARY_LOCK_PREFIX + sessionId;
        String lockValue = UUID.randomUUID().toString();
        if (!acquireLock(lockKey, lockValue)) {
            log.debug("检测到其他节点正在生成 session 摘要，跳过本次刷新，sessionId={}", sessionId);
            return;
        }

        try {
            ChatSessionProfile existingProfile = chatSessionProfileRepository.findById(sessionId).orElse(null);
            if (existingProfile != null
                    && existingProfile.getLastSummarizedChatId() != null
                    && existingProfile.getLastSummarizedChatId() >= latestChatId) {
                // 当前 DB 摘要已经覆盖到最新消息，说明本次事件可能是重复触发。
                // 这里仅做 session 摘要缓存自愈，不再重复刷新 user 画像，
                // 避免每轮请求都把最近 10 个 session 再聚合一遍，造成额外模型开销。
                ensureSessionSummaryCache(sessionId, existingProfile.getProfileSummary());
                return;
            }

            List<ChatMessage> messagesToSummarize = resolveMessagesToSummarize(sessionId, existingProfile);
            if (messagesToSummarize.isEmpty()) {
                return;
            }

            String summary = buildSessionSummary(existingProfile != null ? existingProfile.getProfileSummary() : null, messagesToSummarize);
            if (!StringUtils.hasText(summary)) {
                log.warn("生成出的 session 摘要为空，已跳过写入，sessionId={}", sessionId);
                return;
            }

            ChatSessionProfile sessionProfile = ChatSessionProfile.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .profileSummary(summary)
                    .lastSummarizedChatId(latestChatId)
                    .summarizedMessageCount(safeToInt(totalMessages))
                    .summaryModel(chatMemoryConfig.getSummaryModel())
                    .build();
            chatSessionProfileRepository.save(sessionProfile);

            // 先落数据库，再更新 Redis 长存摘要缓存；Redis 丢失时可从 DB 回源修复。
            cacheSessionSummarySystemPrompt(sessionId, summary);
            refreshUserProfileSnapshot(userId);
            log.info("中期记忆 session 摘要已刷新，sessionId={}, latestChatId={}, messageCount={}",
                    sessionId, latestChatId, totalMessages);
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    /**
     * 读取当前请求应注入 Prompt 的中期记忆 SystemMessage。
     *
     * <p>顺序上先注入“用户主体画像”，再注入“当前 session 摘要”，
     * 这样模型会先获得稳定偏好，再获得会话局部上下文。</p>
     */
    public List<String> getMemorySystemPrompts(String sessionId, String userId) {
        List<String> prompts = new ArrayList<>(2);

        String userProfilePrompt = getCachedUserProfileSystemPrompt(userId);
        if (StringUtils.hasText(userProfilePrompt)) {
            prompts.add(userProfilePrompt);
        }

        String sessionSummaryPrompt = getCachedSessionSummarySystemPrompt(sessionId);
        if (StringUtils.hasText(sessionSummaryPrompt)) {
            prompts.add(sessionSummaryPrompt);
        }

        return prompts;
    }

    private List<ChatMessage> resolveMessagesToSummarize(String sessionId, ChatSessionProfile existingProfile) {
        List<ChatMessage> messages;
        if (existingProfile != null && existingProfile.getLastSummarizedChatId() != null) {
            messages = chatMessageRepository.findBySessionIdAndChatIdGreaterThanOrderByCreatedAtAsc(
                    sessionId,
                    existingProfile.getLastSummarizedChatId()
            );
        } else {
            messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        }

        messages = new ArrayList<>(messages);

        messages.sort(Comparator
                .comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(msg -> msg.getChatId() == null ? Long.MAX_VALUE : msg.getChatId()));
        return messages;
    }

    private String buildSessionSummary(String existingSummary, List<ChatMessage> messages) {
        List<String> transcripts = splitConversationIntoChunks(messages);
        if (transcripts.isEmpty()) {
            return normalizeSummary(existingSummary);
        }

        String sessionSystemPrompt = String.format(
                promptProvider.getSessionSummarySystemPromptTemplate(),
                chatMemoryConfig.getSummaryMaxCharacters()
        );
        if (transcripts.size() == 1) {
            String singlePrompt = buildSessionUpdatePrompt(existingSummary, transcripts.get(0));
            return normalizeSummary(memorySummaryClient.generateSummary(
                    chatMemoryConfig.getSummaryModel(),
                    sessionSystemPrompt,
                    singlePrompt
            ));
        }

        List<String> chunkSummaries = new ArrayList<>(transcripts.size());
        for (String transcript : transcripts) {
            String partialSummary = normalizeSummary(memorySummaryClient.generateSummary(
                    chatMemoryConfig.getSummaryModel(),
                    sessionSystemPrompt,
                    buildSessionUpdatePrompt(null, transcript)
            ));
            if (StringUtils.hasText(partialSummary)) {
                chunkSummaries.add(partialSummary);
            }
        }

        if (chunkSummaries.isEmpty()) {
            return normalizeSummary(existingSummary);
        }

        String mergedPrompt = buildMergedSessionSummaryPrompt(existingSummary, chunkSummaries);
        return normalizeSummary(memorySummaryClient.generateSummary(
                chatMemoryConfig.getSummaryModel(),
                sessionSystemPrompt,
                mergedPrompt
        ));
    }

    private void refreshUserProfileSnapshot(String userId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }

        String lockKey = USER_SUMMARY_LOCK_PREFIX + userId;
        String lockValue = UUID.randomUUID().toString();
        if (!acquireLock(lockKey, lockValue)) {
            log.debug("检测到其他节点正在生成用户主体画像，跳过本次刷新，userId={}", userId);
            return;
        }

        try {
            List<ChatSession> recentSessions = chatSessionRepository.findByUserIdOrderByCreatedAtDesc(
                    userId,
                    PageRequest.of(0, chatMemoryConfig.getUserProfileSessionWindow())
            );
            if (recentSessions.isEmpty()) {
                return;
            }

            List<String> recentSessionIds = recentSessions.stream()
                    .map(ChatSession::getSessionId)
                    .filter(StringUtils::hasText)
                    .toList();
            Map<String, ChatSessionProfile> sessionProfileMap = chatSessionProfileRepository.findBySessionIdIn(recentSessionIds)
                    .stream()
                    .filter(profile -> StringUtils.hasText(profile.getProfileSummary()))
                    .collect(Collectors.toMap(ChatSessionProfile::getSessionId, profile -> profile, (left, right) -> right, LinkedHashMap::new));

            List<String> sessionSummaries = new ArrayList<>();
            List<String> usedSessionIds = new ArrayList<>();
            for (ChatSession session : recentSessions) {
                ChatSessionProfile profile = sessionProfileMap.get(session.getSessionId());
                if (profile == null || !StringUtils.hasText(profile.getProfileSummary())) {
                    continue;
                }
                usedSessionIds.add(session.getSessionId());
                sessionSummaries.add("sessionId=" + session.getSessionId() + "\n" + profile.getProfileSummary());
            }

            if (sessionSummaries.isEmpty()) {
                return;
            }

            // 这里按“最近 10 个 session 的摘要集合”再次压缩，形成更稳定的长期用户主体画像。
            String mergedSummary = normalizeSummary(memorySummaryClient.generateSummary(
                    chatMemoryConfig.getSummaryModel(),
                    String.format(promptProvider.getUserProfileSystemPromptTemplate(), chatMemoryConfig.getSummaryMaxCharacters()),
                    buildUserProfilePrompt(sessionSummaries)
            ));
            if (!StringUtils.hasText(mergedSummary)) {
                return;
            }

            UserProfileSnapshot snapshot = UserProfileSnapshot.builder()
                    .userId(userId)
                    .profileSummary(mergedSummary)
                    .sourceSessionCount(usedSessionIds.size())
                    .sourceSessionIds(String.join(",", usedSessionIds))
                    .summaryModel(chatMemoryConfig.getSummaryModel())
                    .build();
            userProfileSnapshotRepository.save(snapshot);
            cacheUserProfileSystemPrompt(userId, mergedSummary);
            log.info("用户主体画像已刷新，userId={}, sessions={}", userId, usedSessionIds.size());
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    private String getCachedSessionSummarySystemPrompt(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }

        String key = buildSessionSummaryKey(sessionId);
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.hasText(cached)) {
            return cached;
        }

        return chatSessionProfileRepository.findById(sessionId)
                .map(ChatSessionProfile::getProfileSummary)
                .filter(StringUtils::hasText)
                .map(summary -> {
                    cacheSessionSummarySystemPrompt(sessionId, summary);
                    return formatSessionSummarySystemPrompt(summary);
                })
                .orElse(null);
    }

    private String getCachedUserProfileSystemPrompt(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }

        String key = buildUserProfileKey(userId);
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.hasText(cached)) {
            return cached;
        }

        return userProfileSnapshotRepository.findById(userId)
                .map(UserProfileSnapshot::getProfileSummary)
                .filter(StringUtils::hasText)
                .map(summary -> {
                    cacheUserProfileSystemPrompt(userId, summary);
                    return formatUserProfileSystemPrompt(summary);
                })
                .orElse(null);
    }

    private void ensureSessionSummaryCache(String sessionId, String summary) {
        if (StringUtils.hasText(summary)) {
            cacheSessionSummarySystemPrompt(sessionId, summary);
        }
    }

    private void cacheSessionSummarySystemPrompt(String sessionId, String summary) {
        cacheValue(buildSessionSummaryKey(sessionId), formatSessionSummarySystemPrompt(summary));
    }

    private void cacheUserProfileSystemPrompt(String userId, String summary) {
        cacheValue(buildUserProfileKey(userId), formatUserProfileSystemPrompt(summary));
    }

    private void cacheValue(String key, String value) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }

        long ttlHours = chatMemoryConfig.getSummaryRedisTtlHours();
        if (ttlHours > 0) {
            stringRedisTemplate.opsForValue().set(key, value, Duration.ofHours(ttlHours));
            return;
        }
        stringRedisTemplate.opsForValue().set(key, value);
    }

    private String buildSessionSummaryKey(String sessionId) {
        return chatMemoryConfig.getSessionSummaryRedisKeyPrefix() + sessionId;
    }

    private String buildUserProfileKey(String userId) {
        return chatMemoryConfig.getUserProfileRedisKeyPrefix() + userId;
    }

    private String formatSessionSummarySystemPrompt(String summary) {
        return String.format(promptProvider.getSessionSummaryWrapperTemplate(), normalizeSummary(summary));
    }

    private String formatUserProfileSystemPrompt(String summary) {
        return String.format(promptProvider.getUserProfileWrapperTemplate(), normalizeSummary(summary));
    }

    private List<String> splitConversationIntoChunks(List<ChatMessage> messages) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (ChatMessage message : messages) {
            String line = toTranscriptLine(message);
            if (!StringUtils.hasText(line)) {
                continue;
            }

            int lineTokens = TOKEN_ENCODING.countTokens(line) + 1;
            // 当累计 token 即将超过摘要模型的可用上下文预算时，先截成一个分片，
            // 后续会对每个分片先做局部摘要，再合并为最终摘要，避免一次性塞入超长会话。
            if (currentChunk.length() > 0 && currentTokens + lineTokens > chatMemoryConfig.getSummaryContextTokenLimit()) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }

            if (lineTokens > chatMemoryConfig.getSummaryContextTokenLimit()) {
                line = truncateToReasonableLine(line);
                lineTokens = Math.min(chatMemoryConfig.getSummaryContextTokenLimit(), TOKEN_ENCODING.countTokens(line) + 1);
            }

            currentChunk.append(line).append("\n");
            currentTokens += lineTokens;
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }
        return chunks;
    }

    private String buildSessionUpdatePrompt(String existingSummary, String transcript) {
        String existingSummarySection = StringUtils.hasText(existingSummary)
                ? String.format(promptProvider.getSessionExistingSummaryBlockTemplate(), existingSummary)
                : "";
        return String.format(
                promptProvider.getSessionUpdateUserPromptTemplate(),
                existingSummarySection,
                transcript,
                chatMemoryConfig.getSummaryMaxCharacters()
        );
    }

    private String buildMergedSessionSummaryPrompt(String existingSummary, List<String> chunkSummaries) {
        String existingSummarySection = StringUtils.hasText(existingSummary)
                ? String.format(promptProvider.getSessionExistingSummaryBlockTemplate(), existingSummary)
                : "";
        StringBuilder chunkSummaryBlock = new StringBuilder();
        for (int i = 0; i < chunkSummaries.size(); i++) {
            chunkSummaryBlock.append(String.format(
                    promptProvider.getSessionChunkItemPromptTemplate(),
                    i + 1,
                    chunkSummaries.get(i)
            ));
        }
        return String.format(
                promptProvider.getSessionMergeUserPromptTemplate(),
                existingSummarySection,
                chunkSummaryBlock,
                chatMemoryConfig.getSummaryMaxCharacters()
        );
    }

    private String buildUserProfilePrompt(List<String> sessionSummaries) {
        StringBuilder sessionSummaryBlock = new StringBuilder();
        for (int i = 0; i < sessionSummaries.size(); i++) {
            sessionSummaryBlock.append(String.format(
                    promptProvider.getUserProfileSessionItemPromptTemplate(),
                    i + 1,
                    sessionSummaries.get(i)
            ));
        }
        return String.format(
                promptProvider.getUserProfileAggregationUserPromptTemplate(),
                sessionSummaryBlock,
                chatMemoryConfig.getSummaryMaxCharacters()
        );
    }

    private String toTranscriptLine(ChatMessage message) {
        if (message == null || !StringUtils.hasText(message.getContent())) {
            return null;
        }
        String role = StringUtils.hasText(message.getChatRole()) ? message.getChatRole().trim().toLowerCase() : "user";
        return role + ": " + message.getContent().trim();
    }

    private String truncateToReasonableLine(String line) {
        int safeLength = Math.min(line.length(), 1200);
        return line.substring(0, safeLength);
    }

    private String normalizeSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return summary;
        }

        String normalized = summary.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= chatMemoryConfig.getSummaryMaxCharacters()) {
            return normalized;
        }
        return normalized.substring(0, chatMemoryConfig.getSummaryMaxCharacters());
    }

    private boolean acquireLock(String lockKey, String lockValue) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                Duration.ofSeconds(chatMemoryConfig.getSummaryLockSeconds())
        ));
    }

    private void releaseLock(String lockKey, String lockValue) {
        try {
            stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("释放中期记忆分布式锁失败，lockKey={}: {}", lockKey, e.getMessage());
        }
    }

    private int resolveSummaryTriggerMessageCount() {
        return Math.max(2, chatMemoryConfig.getSummaryTriggerRounds() * 2);
    }

    private int safeToInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}





