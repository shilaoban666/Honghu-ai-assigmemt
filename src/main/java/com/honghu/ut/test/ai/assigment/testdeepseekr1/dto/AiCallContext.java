package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 一次 AI 调用在平台内部流转时携带的上下文对象。
 *
 * <p>它不是给前端直接传输的 DTO，而是服务层内部的“调用信封”，用于把：</p>
 * <ul>
 *     <li>是谁发起的调用（userId）</li>
 *     <li>调用落在哪个团队/个人空间（workspaceId）</li>
 *     <li>属于哪条会话、哪条消息（sessionId / chatId）</li>
 *     <li>这一跳调用的链路追踪号（requestId / attemptNo）</li>
 * </ul>
 * <p>统一传给网关、计费、配额、用量流水等模块，避免每个服务都重复拼装这些字段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCallContext {

    /**
     * 单次 AI 请求的全局唯一 ID。
     *
     * <p>它会写入 ai_usage_event，用来串起网关日志、调用流水和错误排查。
     * 同一次请求重试时可以复用 requestId，再通过 attemptNo 区分第几次尝试。</p>
     */
    private String requestId;

    /**
     * 同一个 requestId 下的尝试次数，默认第 1 次。
     *
     * <p>当前主要用于 usage event 幂等键：requestId + attemptNo。
     * 如果未来做自动重试并希望每次重试都单独落流水，可以递增这个字段。</p>
     */
    @Builder.Default
    private Integer attemptNo = 1;

    /** 当前调用用户 ID，匿名或系统调用可以为空。 */
    private String userId;

    /**
     * 当前调用所在 workspace。
     *
     * <p>为空时服务层会解析用户默认 workspace。企业用户切换团队空间时，
     * 前端应通过 X-Workspace-Id 或请求体传入该字段。</p>
     */
    private String workspaceId;

    /** 聊天会话 ID，用于把调用流水关联到一轮会话。 */
    private String sessionId;

    /** 持久化聊天消息 ID，用于把调用流水精确关联到某条用户消息或 AI 回复。 */
    private Long chatId;

    /** 调用来源标识，例如 chat、stream、legacy-chat，主要用于排查日志。 */
    private String source;

    /**
     * Current user query text carried as trusted runtime context.
     *
     * <p>Tools may use this as a default query when the model omits an explicit
     * parameter. It is not inserted into the model-controlled arguments JSON.</p>
     */
    private String query;

    /**
     * 构造匿名调用上下文。
     *
     * <p>匿名上下文仍然会生成 requestId，保证失败或调试场景也能留下可追踪 ID。
     * 这种场景常见于旧接口兼容调用、系统任务、或 아직没登录的体验入口。</p>
     */
    public static AiCallContext anonymous(String source) {
        return AiCallContext.builder()
                .requestId(UUID.randomUUID().toString())
                .source(source)
                .build();
    }

    /**
     * 确保上下文有可用的 requestId 和 attemptNo。
     *
     * <p>这个方法设计成“懒生成”，是因为很多调用方只关心 userId / workspaceId，
     * 直到真正要落 usage event 时才必须保证 requestId 一定存在。</p>
     *
     * @return 最终可用的 requestId
     */
    public String ensureRequestId() {
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        if (attemptNo == null || attemptNo < 1) {
            attemptNo = 1;
        }
        return requestId;
    }
}
