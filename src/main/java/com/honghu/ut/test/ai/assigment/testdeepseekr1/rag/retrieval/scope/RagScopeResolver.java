package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.scope;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.pipeline.RagRequest;

/**
 * 检索作用域解析器接口。
 *
 * <p>根据 {@link RagRequest} 中的 {@code attachmentFileIds / chatId / sessionId}
 * 以及配置的策略枚举，产出最终用于检索的 {@link EffectiveScope}。</p>
 *
 * <h3>优先级链</h3>
 * <pre>
 *   attachmentFileIds 非空  →  primaryType = FILE_IDS
 *   chatId 非空             →  primaryType = CHAT
 *   sessionId 非空          →  primaryType = SESSION
 *   都不满足                 →  primaryType = EMPTY
 * </pre>
 *
 * <h3>实现要求</h3>
 * <ul>
 *   <li>必须支持五种 {@link RetrievalScope} 策略的解析</li>
 *   <li>当策略允许 fallback 时，EffectiveScope.allowFallback 应为 true</li>
 *   <li>ownerFolder 必须从外部传入（由 Pipeline 从 UserRepository 查询），不在 resolver 内部查 DB</li>
 * </ul>
 *
 * @see DefaultRagScopeResolver
 * @see EffectiveScope
 * @see RetrievalScope
 */
public interface RagScopeResolver {

    /**
     * 解析检索作用域。
     *
     * <p>这个方法的核心任务不是“马上去查数据”，而是先回答一个更基础的问题：
     * <b>本次请求理论上应该先查哪一层数据，以及失败后能不能往更宽范围退。</b>
     * 它输出的 {@link EffectiveScope} 会被后续 retriever 直接消费，成为真正查询数据库或向量库时的依据。</p>
     *
     * <p>之所以把这一步独立出来，是为了把“策略判断”与“数据检索”解耦：
     * 作用域解析只关心附件/chat/session 之间的优先级和 fallback 规则，
     * 不关心底层究竟是 SQL 查询还是向量检索。</p>
     *
     * @param request      用户请求（含 userId / sessionId / chatId / attachmentFileIds）
     * @param ownerFolder  鉴权用的 ownerFolder（即 username），由 Pipeline 从 UserRepository 查询后传入
     * @param defaultScope 配置默认作用域策略值（如 "attachment_chat_first"），可为 null（fallback 到 SESSION_ONLY）
     * @return 解析后的 EffectiveScope，永远不会返回 null
     */
    EffectiveScope resolve(RagRequest request, String ownerFolder, String defaultScope);
}
