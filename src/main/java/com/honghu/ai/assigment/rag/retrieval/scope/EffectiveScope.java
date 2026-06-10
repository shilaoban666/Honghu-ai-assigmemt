package com.honghu.ai.assigment.rag.retrieval.scope;

import lombok.Builder;
import lombok.Value;
import java.util.List;

/**
 * 解析后的实际检索作用域。
 *
 * <p>与 {@link RetrievalScope}（策略枚举）不同，这个类承载的是
 * {@link RagScopeResolver} 解析后的<b>具体参数</b>，直接供
 * {@code RagCandidateRetriever} 使用。</p>
 *
 * <h3>与 RetrievalScope 的关系</h3>
 * <pre>
 *   RetrievalScope（策略）  →  RagScopeResolver.resolve()  →  EffectiveScope（参数）
 *   "我想用 CHAT_FIRST"          "当前 chatId=42"               primaryType=CHAT, chatId=42, allowFallback=true
 * </pre>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code primaryType}</td><td>{@link ScopeType}</td><td>当前优先使用的作用域类型，决定 CandidateRetriever 走哪条查询路径</td></tr>
 *   <tr><td>{@code fileIds}</td><td>{@code List<String>}</td><td>需要精确匹配的附件文件 ID 列表（仅 ScopeType=FILE_IDS 时非空）</td></tr>
 *   <tr><td>{@code chatId}</td><td>{@code Long}</td><td>当前消息的 chat 数据库 ID（仅 ScopeType=CHAT 时非空）</td></tr>
 *   <tr><td>{@code sessionId}</td><td>{@code String}</td><td>当前会话 ID（ScopeType=SESSION 时非空；fallback 时作为兜底）</td></tr>
 *   <tr><td>{@code ownerFolder}</td><td>{@code String}</td><td>鉴权用的 ownerFolder（即 username），所有检索路径都必须携带</td></tr>
 *   <tr><td>{@code allowFallback}</td><td>{@code boolean}</td><td>是否允许命中不足时降级到更宽作用域</td></tr>
 *   <tr><td>{@code fallbackMinHits}</td><td>{@code int}</td><td>触发 fallback 的最小命中数阈值（candidateCount < 此值时触发）</td></tr>
 *   <tr><td>{@code fallbackMinScore}</td><td>{@code double}</td><td>触发 fallback 的最低分数阈值（bestScore < 此值时触发）</td></tr>
 * </table>
 *
 * <h3>Fallback 触发条件</h3>
 * <p>两个条件为 OR 关系，任一满足即触发：</p>
 * <pre>candidateCount < fallbackMinHits  OR  bestScore < fallbackMinScore</pre>
 *
 * @see RetrievalScope
 * @see RagScopeResolver
 */
@Value
@Builder
public class EffectiveScope {

    /**
     * 作用域类型。
     *
     * <p>决定 CandidateRetriever 走哪条查询路径：
     * <ul>
     *   <li>{@code FILE_IDS} — 按 fileIds 精确查 chunk</li>
     *   <li>{@code CHAT} — 按 chatId 查 chunk</li>
     *   <li>{@code SESSION} — 按 sessionId 查 chunk（最宽）</li>
     *   <li>{@code EMPTY} — 无可检索数据，直接返回空</li>
     * </ul>
     */
     public enum ScopeType {
         /** 只查本轮附件 fileIds 对应的文档，精度最高，通常作为第一优先级。 */
         FILE_IDS,
         /** 查当前 chat 范围内关联的文档，范围比附件更宽，但仍然贴近当前问题。 */
         CHAT,
         /** 查整个会话下的文档，是当前设计中的最宽私有检索范围。 */
         SESSION,
         /** 当前请求没有可用检索依据，检索器应直接返回空结果。 */
         EMPTY
     }

    /**
     * 当前优先使用的作用域类型。
     *
     * <p>它是整个 EffectiveScope 的“路由开关”，retriever 通常会先看它，
     * 再决定本次该按 fileIds、chatId 还是 sessionId 去构造查询条件。</p>
     */
    ScopeType primaryType;

    /**
     * 附件文件 ID 列表。
     *
     * <p>只有当 primaryType=FILE_IDS 时，这个字段才真正参与过滤；
     * 其他类型下它即使保留，也只是为了未来 fallback 或调试时保留上下文信息。</p>
     */
    List<String> fileIds;

    /**
     * 当前 chat 数据库 ID。
     *
     * <p>当 primaryType=CHAT 时，检索器会利用它把候选限定在当前消息附近的聊天上下文中。</p>
     */
    Long chatId;

    /**
     * 当前会话 ID。
     *
     * <p>它既能在 SESSION 作用域下直接参与检索，也经常作为 FILE_IDS/CHAT 失败后的最终兜底依据。</p>
     */
    String sessionId;

    /**
     * 鉴权用的 ownerFolder（username）。
     *
     * <p>这是所有检索路径都必须携带的安全过滤条件，
     * 用来确保用户只能查到自己名下的资料。</p>
     */
    String ownerFolder;

    /**
     * 是否允许命中不足时降级到更宽作用域。
     *
     * <p>例如 ATTACHMENT_ONLY、CHAT_ONLY 这类策略就会把它设成 false，
     * 明确禁止系统擅自扩大范围。</p>
     */
    @Builder.Default
    boolean allowFallback = true;

    /**
     * 触发 fallback 的最小命中数。
     *
     * <p>当当前候选数量小于这个值时，系统会认为“上下文数量不够支撑回答”，
     * 从而尝试扩大作用域补召回。</p>
     */
    @Builder.Default
    int fallbackMinHits = 2;

    /**
     * 触发 fallback 的最低分数阈值。
     *
     * <p>它从“质量”维度判断是否需要 fallback：
     * 即使候选数量不少，但如果最高分都很低，说明当前范围可能并不匹配用户问题。</p>
     */
    @Builder.Default
    double fallbackMinScore = 0.1;
}
