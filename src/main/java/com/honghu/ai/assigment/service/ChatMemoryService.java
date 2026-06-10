package com.honghu.ai.assigment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.config.ChatMemoryConfig;
import com.honghu.ai.assigment.entity.ChatMessage;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis List 的短期会话记忆服务。
 *
 * <p>读写都会刷新 TTL，实现滑动窗口效果；Prompt 组装前由 ChatService 按 token 动态截断。</p>
 *
 * <p>设计目标：</p>
 * <ul>
 *     <li>Redis 负责“短期记忆”：高频、最近、可快速读取的会话上下文</li>
 *     <li>数据库负责“长期记忆”：完整持久化归档</li>
 *     <li>真正送给模型的历史上下文，不按条数截断，而按 token 动态计算</li>
 * </ul>
 *
 * <p>一致性定位：</p>
 * <ul>
 *     <li>数据库是最终真源（source of truth）</li>
 *     <li>Redis 是数据库派生出来的短期工作集，可过期、可丢失、可重建</li>
 *     <li>因此这里追求的是“数据库成功 + Redis 最终一致”，而不是分布式强一致事务</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemoryService {

    private static final String DEDUP_KEY_SUFFIX = ":dedup";
    private static final String REBUILD_LOCK_KEY_SUFFIX = ":rebuild:lock";
    private static final DefaultRedisScript<Long> APPEND_IF_ABSENT_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();

    static {
        APPEND_IF_ABSENT_SCRIPT.setScriptText(
                "if redis.call('SADD', KEYS[1], ARGV[1]) == 1 then " +
                        "redis.call('RPUSH', KEYS[2], ARGV[2]); " +
                        "if tonumber(ARGV[3]) > 0 then " +
                        "redis.call('EXPIRE', KEYS[1], ARGV[3]); " +
                        "redis.call('EXPIRE', KEYS[2], ARGV[3]); " +
                        "end; " +
                        "return 1; " +
                        "else " +
                        "if tonumber(ARGV[3]) > 0 then " +
                        "redis.call('EXPIRE', KEYS[1], ARGV[3]); " +
                        "redis.call('EXPIRE', KEYS[2], ARGV[3]); " +
                        "end; " +
                        "return 0; " +
                        "end"
        );
        APPEND_IF_ABSENT_SCRIPT.setResultType(Long.class);

        RELEASE_LOCK_SCRIPT.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('DEL', KEYS[1]); " +
                        "else return 0; end"
        );
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
    }

    private static final EncodingRegistry TOKEN_REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding TOKEN_ENCODING = TOKEN_REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatMemoryConfig chatMemoryConfig;

    /**
     * 追加单条消息到 Redis 短期记忆。
     *
     * <p>采用 Redis List 的右侧追加，天然保持消息时间顺序：</p>
     * <ul>
     *     <li>旧消息在左</li>
     *     <li>新消息在右</li>
     * </ul>
     *
     * <p>这里会把消息先转成轻量结构再序列化成 JSON，避免直接把 JPA 实体原样存入 Redis。</p>
     *
     * <p>注意：这个方法只负责“把已确认落库的消息写入缓存副本”，
     * 调用方应优先使用 {@link #appendMessageAfterCommit(ChatMessage)}，
     * 以避免数据库回滚但 Redis 已经提前可见的双写不一致问题。</p>
     */
    public void appendMessage(ChatMessage message) {
        // 无效消息直接忽略，避免写入空 key 或空内容污染短期记忆
        if (message == null || message.getSessionId() == null || message.getSessionId().isBlank()) {
            return;
        }
        if (message.getContent() == null || message.getContent().isBlank()) {
            return;
        }

        // Redis key 与 sessionId 一一对应，一个会话对应一个 List
        String key = buildKey(message.getSessionId());
        String dedupKey = buildDedupKey(message.getSessionId());
        RedisChatMemoryItem item = RedisChatMemoryItem.builder()
                .chatId(message.getChatId())
                .sessionId(message.getSessionId())
                .chatRole(message.getChatRole())
                .contentType(message.getContentType())
                .status(message.getStatus())
                .content(message.getContent())
                .createdAt(message.getCreatedAt() != null ? message.getCreatedAt() : LocalDateTime.now())
                .tokenEstimate(estimateTokenCount(message))
                .build();

        try {
            // 使用 chatId 作为幂等键，避免多实例重试、重复消费、或重建后再次写入时产生重复消息
            String payload = objectMapper.writeValueAsString(item);
            String messageIdentity = buildMessageIdentity(message);
            Long appended = stringRedisTemplate.execute(
                    APPEND_IF_ABSENT_SCRIPT,
                    List.of(dedupKey, key),
                    messageIdentity,
                    payload,
                    String.valueOf(resolveTtlSeconds())
            );

            if (Long.valueOf(1L).equals(appended)) {
                log.debug("Redis 短期记忆写入成功，sessionId={}, chatId={}", message.getSessionId(), message.getChatId());
            } else {
                log.debug("Redis 短期记忆检测到重复消息，已跳过，sessionId={}, chatId={}", message.getSessionId(), message.getChatId());
            }
        } catch (Exception e) {
            log.warn("写入 Redis 短期记忆失败，sessionId={}: {}", message.getSessionId(), e.getMessage());
        }
    }

    /**
     * 在数据库事务提交后再写入 Redis，避免“数据库回滚但缓存已可见”的双写不一致问题。
     *
     * <p>这是当前项目里解决 DB/Redis 双写问题的关键手段：</p>
     * <ul>
     *     <li>有事务：挂到 afterCommit，等数据库提交成功后再写 Redis</li>
     *     <li>无事务：直接执行，适配独立的补偿/重建/后台任务场景</li>
     * </ul>
     */
    public void appendMessageAfterCommit(ChatMessage message) {
        runAfterCommitOrNow(() -> appendMessage(message));
    }

    /**
     * 读取某个会话的 Redis 短期记忆，保持时间升序返回。
     *
     * <p>返回值仍然使用 ChatMessage，是为了尽量复用后续 Prompt 组装逻辑，
     * 这样 ChatService 不需要区分“这批历史来自 Redis 还是来自数据库”。</p>
     *
     * <p>命中后会刷新整个会话工作集的 TTL。这里不只刷新 List 本身，
     * 也会同步刷新 dedup Set，避免“列表一直活着，但去重索引先过期”，
     * 从而让后续重复消息再次被写入 Redis。</p>
     */
    public List<ChatMessage> getMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        String key = buildKey(sessionId);
        try {
            // 0 到 -1 代表读取整个 List；顺序与写入顺序一致，即升序历史
            List<String> payloads = stringRedisTemplate.opsForList().range(key, 0, -1);
            if (payloads == null || payloads.isEmpty()) {
                return List.of();
            }

            // 读取命中后同样刷新整个会话工作集 TTL，体现“最近被访问过”的会话仍属于短期记忆。
            // 同时刷新 list 与 dedup，避免两类 key 的生命周期漂移。
            refreshSessionExpiration(sessionId);

            List<ChatMessage> history = new ArrayList<>(payloads.size());
            for (String payload : payloads) {
                if (payload == null || payload.isBlank()) {
                    continue;
                }
                // Redis 中存的是轻量 DTO，这里再还原为统一的 ChatMessage 供业务层复用
                RedisChatMemoryItem item = objectMapper.readValue(payload, RedisChatMemoryItem.class);
                history.add(ChatMessage.builder()
                        .chatId(item.getChatId())
                        .sessionId(item.getSessionId() != null ? item.getSessionId() : sessionId)
                        .chatRole(item.getChatRole())
                        .contentType(item.getContentType() != null ? item.getContentType() : "text")
                        .status(item.getStatus())
                        .content(item.getContent())
                        .createdAt(item.getCreatedAt())
                        .build());
            }
            history.sort(Comparator
                    .comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(msg -> msg.getChatId() == null ? Long.MAX_VALUE : msg.getChatId()));
            return history;
        } catch (Exception e) {
            log.warn("读取 Redis 短期记忆失败，sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 使用数据库历史消息重建 Redis 短期记忆。
     *
     * <p>典型场景：Redis 过期、服务重启、或首次接入短期记忆机制时，
     * 可以通过数据库中的完整历史重建最近上下文。</p>
     *
     * <p>这里有两个分布式保护点：</p>
     * <ol>
     *     <li>先抢 session 级重建锁，避免多个节点同时 rebuild</li>
     *     <li>抢到锁后再次检查 Redis 是否已被其他节点补热，避免误删新数据</li>
     * </ol>
     */
    public void rebuildSessionMemory(String sessionId, List<ChatMessage> history) {
        if (sessionId == null || sessionId.isBlank() || history == null || history.isEmpty()) {
            return;
        }

        String key = buildKey(sessionId);
        String dedupKey = buildDedupKey(sessionId);
        String lockKey = buildRebuildLockKey(sessionId);
        String lockValue = UUID.randomUUID().toString();

        if (!acquireRebuildLock(lockKey, lockValue)) {
            log.info("检测到其他节点正在重建 Redis 短期记忆，跳过本次重建，sessionId={}", sessionId);
            return;
        }

        try {
            // 抢到锁后再次确认：如果 Redis 已经被其他节点补热，则直接短路返回，
            // 避免把刚补好的数据误删后再重建。
            Long existingSize = stringRedisTemplate.opsForList().size(key);
            if (existingSize != null && existingSize > 0) {
                refreshSessionExpiration(sessionId);
                log.info("Redis 短期记忆在加锁后已存在，无需重建，sessionId={}, 消息数={}", sessionId, existingSize);
                return;
            }

            // 先删 list 和幂等集合，再整体回灌，避免 Redis 中残留旧数据或重复数据
            stringRedisTemplate.delete(key);
            stringRedisTemplate.delete(dedupKey);
            for (ChatMessage message : history) {
                appendMessage(message);
            }
            log.info("已根据数据库历史重建 Redis 短期记忆，sessionId={}, 消息数={}", sessionId, history.size());
        } catch (Exception e) {
            log.warn("重建 Redis 短期记忆失败，sessionId={}: {}", sessionId, e.getMessage());
        } finally {
            releaseRebuildLock(lockKey, lockValue);
        }
    }

    /**
     * 清空某个会话在 Redis 中的短期记忆及幂等索引。
     */
    public void clearSessionMemory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            stringRedisTemplate.delete(List.of(buildKey(sessionId), buildDedupKey(sessionId)));
        } catch (Exception e) {
            log.warn("清理 Redis 短期记忆失败，sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 在数据库事务提交后再删除 Redis 短期记忆，避免数据库删除回滚而缓存已被提前删除。
     */
    public void clearSessionMemoryAfterCommit(String sessionId) {
        runAfterCommitOrNow(() -> clearSessionMemory(sessionId));
    }

    /**
     * 统一刷新某个 session 的 Redis 工作集 TTL。
     *
     * <p>一个 session 在 Redis 中至少有两类 key：</p>
     * <ul>
     *     <li>历史消息 List</li>
     *     <li>幂等防重 Set</li>
     * </ul>
     *
     * <p>二者的生命周期应该尽量保持一致，否则会出现“历史还在，但去重索引先过期”的漂移问题。</p>
     */
    private void refreshSessionExpiration(String sessionId) {
        refreshExpiration(buildKey(sessionId));
        refreshExpiration(buildDedupKey(sessionId));
    }

    private String buildKey(String sessionId) {
        // 统一构造会话短期记忆 key，便于后续扩展与排查
        return chatMemoryConfig.getRedisKeyPrefix() + sessionId;
    }

    private String buildDedupKey(String sessionId) {
        return buildKey(sessionId) + DEDUP_KEY_SUFFIX;
    }

    private String buildRebuildLockKey(String sessionId) {
        return buildKey(sessionId) + REBUILD_LOCK_KEY_SUFFIX;
    }

    private void refreshExpiration(String key) {
        // 配置为 0 或负数时，表示关闭 TTL 刷新能力
        if (chatMemoryConfig.getRedisTtlMinutes() <= 0) {
            return;
        }
        stringRedisTemplate.expire(key, Duration.ofMinutes(chatMemoryConfig.getRedisTtlMinutes()));
    }

    private int estimateTokenCount(ChatMessage message) {
        // 这里只做“上下文窗口选择”的近似估算，不追求与底层模型 tokenizer 绝对一致
        // +4 用来粗略覆盖 role / message wrapper 等额外开销
        String role = message.getChatRole() != null ? message.getChatRole() : "user";
        String content = message.getContent() != null ? message.getContent() : "";
        return Math.max(1, TOKEN_ENCODING.countTokens(role) + TOKEN_ENCODING.countTokens(content) + 4);
    }

    private String buildMessageIdentity(ChatMessage message) {
        if (message.getChatId() != null) {
            return String.valueOf(message.getChatId());
        }
        return message.getSessionId() + ":" +
                (message.getChatRole() != null ? message.getChatRole() : "user") + ":" +
                (message.getCreatedAt() != null ? message.getCreatedAt() : LocalDateTime.now()) + ":" +
                Integer.toHexString((message.getContent() != null ? message.getContent() : "").hashCode());
    }

    private long resolveTtlSeconds() {
        if (chatMemoryConfig.getRedisTtlMinutes() <= 0) {
            return 0;
        }
        return Duration.ofMinutes(chatMemoryConfig.getRedisTtlMinutes()).getSeconds();
    }

    private boolean acquireRebuildLock(String lockKey, String lockValue) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                Duration.ofSeconds(chatMemoryConfig.getRebuildLockSeconds())
        );
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseRebuildLock(String lockKey, String lockValue) {
        try {
            stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("释放 Redis 重建锁失败，lockKey={}: {}", lockKey, e.getMessage());
        }
    }

    private void runAfterCommitOrNow(Runnable action) {
        // 当前存在 Spring 事务时，把 Redis 操作延迟到 afterCommit 执行。
        // 这样可以保证“数据库提交成功”永远先于“缓存副本对外可见”。
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }

        // 没有事务时直接执行：常见于补偿任务、读修复重建、独立后台逻辑等场景。
        action.run();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RedisChatMemoryItem {
        /** 数据库主键 chatId，用于幂等、防重和顺序修复。 */
        private Long chatId;
        /** 会话 ID，用于从 Redis 还原回业务对象。 */
        private String sessionId;
        /** 消息角色：user / assistant / system。 */
        private String chatRole;
        /** 内容类型，当前主要是 text。 */
        private String contentType;
        /** 消息状态，例如 active / completed。 */
        private String status;
        /** 实际消息内容。 */
        private String content;
        /** 消息创建时间，尽量保留原时间信息。 */
        private LocalDateTime createdAt;
        /** 消息的 token 预估值，便于后续扩展监控或排查。 */
        private Integer tokenEstimate;
    }
}


