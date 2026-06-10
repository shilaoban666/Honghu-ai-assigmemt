package com.honghu.ai.assigment.rag.retrieval.scope;

/**
 * 检索作用域枚举。
 *
 * <p>定义 RAG 检索时优先查询哪一层级的数据，从最精确的本轮附件文件逐级 fallback 到会话级兜底。
 * 不同策略之间的核心差异在于 <b>起始作用域</b> 和 <b>fallback 行为</b>。</p>
 *
 * <h3>策略语义</h3>
 * <table>
 *   <tr><th>枚举值</th><th>起始作用域</th><th>fallback 链</th></tr>
 *   <tr><td>{@link #ATTACHMENT_CHAT_FIRST}</td><td>本轮附件 fileIds</td><td>→ chatId → sessionId</td></tr>
 *   <tr><td>{@link #ATTACHMENT_ONLY}</td><td>本轮附件 fileIds</td><td>无 fallback</td></tr>
 *   <tr><td>{@link #CHAT_FIRST}</td><td>当前 chatId</td><td>→ sessionId</td></tr>
 *   <tr><td>{@link #CHAT_ONLY}</td><td>当前 chatId</td><td>无 fallback</td></tr>
 *   <tr><td>{@link #SESSION_ONLY}</td><td>当前 sessionId</td><td>无 fallback（旧默认行为）</td></tr>
 * </table>
 *
 * <h3>推荐使用策略</h3>
 * <ul>
 *   <li><b>生产默认</b>：{@code ATTACHMENT_CHAT_FIRST} — 最精确优先，命中不足自动扩大范围</li>
 *   <li><b>灰度过渡</b>：先上线 {@code SESSION_ONLY} 验证 Pipeline 骨架，稳定后再切 {@code ATTACHMENT_CHAT_FIRST}</li>
 *   <li><b>安全敏感场景</b>：{@code ATTACHMENT_ONLY} 或 {@code CHAT_ONLY}，严格不跨作用域</li>
 * </ul>
 *
 * @see EffectiveScope
 * @see DefaultRagScopeResolver
 */
public enum RetrievalScope {

    /**
     * 本轮附件优先，命中不足时 fallback 到当前 chat，再不足到 session（推荐上线默认）。
     *
     * <p>这是“精度优先但允许逐步放宽范围”的策略：先尽量回答用户当前附件里的内容，
     * 如果附件信息不够，再退到当前聊天上下文，最后再退到整个会话。</p>
     */
    ATTACHMENT_CHAT_FIRST,

    /**
     * 严格只查本轮附件，不做任何 fallback。
     *
     * <p>适用于安全要求高、明确不希望系统引用历史资料的场景。
     * 即使当前附件完全没有命中，也不会再扩大到 chat 或 session。</p>
     */
    ATTACHMENT_ONLY,

    /**
     * 当前 chat 优先，命中不足时 fallback 到 session。
     *
     * <p>适用于希望优先利用“本次对话附近资料”的场景，
     * 同时仍允许在当前 chat 结果不足时退到整个会话兜底。</p>
     */
    CHAT_FIRST,

    /**
     * 严格只查当前 chat。
     *
     * <p>它比 SESSION_ONLY 更窄，也比 CHAT_FIRST 更保守，适合只允许使用当前消息附近资料、
     * 不希望引入会话历史噪音的场景。</p>
     */
    CHAT_ONLY,

    /**
     * 只查当前 session（旧默认行为，P0/P1 保持兼容）。
     *
     * <p>这是历史系统最常见的行为：只要属于同一个会话的文档，都有机会被召回。
     * 范围最宽，兼容性最好，但也最容易引入与当前问题不完全贴近的旧资料。</p>
     */
    SESSION_ONLY
}
