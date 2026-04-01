
package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天短期记忆配置。
 *
 * <p>用于控制 Redis 会话短期记忆的 key、TTL，以及 Prompt 组装时可使用的安全 token 上限。</p>
 *
 * <p>这里的 token 限制是“短期记忆窗口”的软阈值，不是模型真实计费值：
 * 主要目的是在把历史消息送入大模型前，尽量保留最近且连续的一段上下文，
 * 同时为模型生成回答预留足够空间。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.chat.memory")
public class ChatMemoryConfig {

	/**
	 * Prompt 侧可安全使用的最大 token 数。
	 * 该值用于“系统提示词 + 历史消息 + 当前消息 + 角色信息”的软限制。
	 *
	 * <p>例如模型上下文是 8k，这里可以先保守配置为 4k，
	 * 剩余空间留给模型生成回答以及额外的协议开销。</p>
	 */
	private int promptTokenLimit = 4000;

	/**
	 * 扣除 system prompt 后，历史消息至少可使用的 token 数。
	 *
	 * <p>用于兜底：即使 system prompt 较长，也尽量给历史消息保留一个最小预算，
	 * 避免上下文被 system prompt 完全挤占。</p>
	 */
	private int minimumHistoryTokens = 256;

	/**
	 * Redis 短期记忆 TTL，单位：分钟。
	 * 每次读写都会刷新，实现滑动窗口效果。
	 */
	private long redisTtlMinutes = 30;

	/**
	 * Redis key 前缀。
	 *
	 * <p>最终 key 形如：chat:memory:{sessionId}</p>
	 */
	private String redisKeyPrefix = "chat:memory:";

	/**
	 * 当 Redis 中没有短期记忆时，是否回退到数据库历史记录。
	 *
	 * <p>开启后，首次命中数据库历史时会把结果回填到 Redis，
	 * 后续请求即可直接走 Redis 滑动窗口。</p>
	 */
	private boolean fallbackToDatabaseOnMiss = true;

	/**
	 * Redis 短期记忆重建锁的持有时长，单位：秒。
	 *
	 * <p>用于防止多实例在同一时刻对同一个 session 执行“删后重建”，
	 * 导致缓存反复抖动或覆盖。</p>
	 */
	private long rebuildLockSeconds = 15;

	/**
	 * 触发中期记忆压缩的轮数阈值。
	 *
	 * <p>按“轮”配置，但内部会换算为消息条数阈值（通常 1 轮 ≈ 2 条消息）。</p>
	 */
	private int summaryTriggerRounds = 20;

	/**
	 * 中期记忆摘要所使用的小模型。
	 */
	private String summaryModel = "deepseek-r1:8b";

	/**
	 * 摘要模型的温度参数。
	 */
	private double summaryTemperature = 0.2;

	/**
	 * 摘要模型单次生成的最大 token 数。
	 */
	private int summaryMaxTokens = 512;

	/**
	 * 会话摘要 / 用户画像的最大字符数。
	 */
	private int summaryMaxCharacters = 300;

	/**
	 * 摘要任务切分长对话时可使用的 token 上限。
	 */
	private int summaryContextTokenLimit = 4000;

	/**
	 * session 级摘要缓存的 Redis key 前缀。
	 */
	private String sessionSummaryRedisKeyPrefix = "chat:summary:session:";

	/**
	 * 用户主体画像缓存的 Redis key 前缀。
	 */
	private String userProfileRedisKeyPrefix = "chat:summary:user:";

	/**
	 * 中期记忆缓存 TTL，单位：小时。
	 */
	private long summaryRedisTtlHours = 24 * 30L;

	/**
	 * session/user 摘要刷新分布式锁的持有时长，单位：秒。
	 */
	private long summaryLockSeconds = 60;

	/**
	 * 是否启用异步摘要刷新。
	 */
	private boolean summaryAsyncEnabled = true;

	/**
	 * 用户主体画像聚合窗口：最近多少个 session。
	 */
	private int userProfileSessionWindow = 10;
}


