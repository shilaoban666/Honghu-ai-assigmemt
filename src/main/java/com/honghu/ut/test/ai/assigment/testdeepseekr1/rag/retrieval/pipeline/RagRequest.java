package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.pipeline;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.scope.RetrievalScope;
import lombok.Builder;
import lombok.Value;
import java.util.List;

/**
 * RAG Pipeline 的统一输入。
 *
 * <p>ChatService 构造此对象后交给 {@code PipelineRagRetrievalService}，
 * 后续所有阶段（scope 解析、检索、fusion、augment）都从这个对象读取参数。
 * 这是 RAG 检索的"请求上下文"，取代旧的三参数分散传参。</p>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>必须</th><th>说明</th></tr>
 *   <tr><td>{@code userId}</td><td>是</td><td>当前请求用户 ID，用于鉴权和 ownerFolder 反查</td></tr>
 *   <tr><td>{@code sessionId}</td><td>是</td><td>当前对话会话 ID，用于 scope 兜底</td></tr>
 *   <tr><td>{@code chatId}</td><td>否</td><td>当前消息的 chat 数据库 ID（流式路径可用，非流式路径无）</td></tr>
 *   <tr><td>{@code attachmentFileIds}</td><td>否</td><td>本轮上传附件文件的 fileId 列表，最精确的作用域</td></tr>
 *   <tr><td>{@code query}</td><td>是</td><td>用户当前提问文本</td></tr>
 *   <tr><td>{@code recentHistory}</td><td>否</td><td>最近对话历史，供 Query Rewrite 代词消解使用（P5+）</td></tr>
 *   <tr><td>{@code scopeOverride}</td><td>否</td><td>显式指定检索作用域（null 时使用配置默认值）</td></tr>
 *   <tr><td>{@code topKOverride}</td><td>否</td><td>显式指定 topK（null 时使用配置值）</td></tr>
 * </table>
 *
 * <h3>向后兼容</h3>
 * <p>静态工厂方法 {@link #sessionOnly} 构造 scope=SESSION_ONLY 的请求，
 * 供旧三参调用路径使用。</p>
 *
 * @see RagResult
 * @see RagPipeline
 */
@Value
@Builder
public class RagRequest {

    /**
     * 当前请求用户 ID。
     *
     * <p>这是整个 RAG 请求里最重要的鉴权字段之一。Pipeline 会先根据它查询用户信息，
     * 再得到 {@code ownerFolder}，后续所有检索都必须带着这个 owner 过滤条件，
     * 用来保证“用户只能检索自己上传或归属自己的资料”。</p>
     */
    String userId;

    /**
     * 当前对话会话 ID。
     *
     * <p>它既是入口鉴权的重要参数，也是最宽的兜底检索范围。
     * 即使 attachment/chat 级别数据都不存在，系统也仍然可以退回到 session 级别继续检索。</p>
     */
    String sessionId;

    /**
     * 当前消息对应的 chat 数据库 ID。
     *
     * <p>它代表“当前这条对话消息所在的更细粒度聊天上下文”。
     * 当作用域策略是 {@code CHAT_FIRST} 或附件作用域 fallback 到 chat 时，检索器会使用它来限制候选范围。</p>
     */
    Long chatId;

    /**
     * 本轮上传附件文件的 fileId 列表。
     *
     * <p>这是最精确的检索范围：只查本轮问题直接绑定的附件文档。
     * 如果它非空，且策略允许优先查附件，系统会优先使用它而不是直接扩大到 chat 或 session。</p>
     */
    List<String> attachmentFileIds;

    /**
     * 用户当前提问文本。
     *
     * <p>这是检索的核心输入：QueryAnalyzer 可能基于它生成多个变体，
     * keyword/vector 两种检索器也都会直接使用它或它的变体做召回。</p>
     */
    String query;

    /**
     * 最近的对话历史。
     *
     * <p>当前版本主要为后续更高级的 Query Rewrite 预留，例如代词消解、上下文补全、问题改写等。
     * 即使目前未深度使用，把它放在统一请求对象里也能避免未来再次大范围改接口。</p>
     */
    List<ChatMessage> recentHistory;

    /**
     * 显式指定的检索作用域。
     *
     * <p>当调用方非常明确地知道“这次就应该只查 session”或“优先附件再 fallback”时，
     * 可以直接在请求里覆盖默认配置。为 null 时，系统再回退使用配置文件中的默认策略。</p>
     */
    RetrievalScope scopeOverride;

    /**
     * 显式指定的 topK。
     *
     * <p>它允许调用方在单次请求上临时覆盖配置中的默认 topK，
     * 例如某些链路只想拿 2 条上下文，而另一些调试链路想看 8 条召回结果。</p>
     */
    Integer topKOverride;

    /**
     * 构造仅 session 作用域的请求（向后兼容）。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param query     用户查询文本
     * @return scopeOverride=SESSION_ONLY 的 RagRequest
     */
    public static RagRequest sessionOnly(String userId, String sessionId, String query) {
        // 用 builder 构造强类型请求，而不是让旧代码继续直接传散落参数，
        // 这样后续即使 pipeline 字段扩展，也仍能保持统一的请求模型。
        return RagRequest.builder()
                .userId(userId).sessionId(sessionId).query(query)
                // 显式指定 SESSION_ONLY，确保旧三参调用路径的行为与历史版本一致。
                .scopeOverride(RetrievalScope.SESSION_ONLY).build();
    }
}
