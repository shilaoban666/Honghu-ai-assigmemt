package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.retriever;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.scope.EffectiveScope;
import java.util.List;

/**
 * RAG 候选检索器接口。
 *
 * <p>每种检索策略（keyword、vector、hybrid）都实现此接口，
 * 接受 {@link EffectiveScope} 参数，返回 {@link RetrievalCandidate} 列表。</p>
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>必须根据 {@code scope.getPrimaryType()} 选择正确的查询路径</li>
 *   <li>必须使用 {@code scope.getOwnerFolder()} 做鉴权过滤</li>
 *   <li>异常时返回空列表，不允许向上抛异常中断 Pipeline</li>
 *   <li>返回的候选不需要排序（由 Pipeline 统一排序）</li>
 * </ul>
 *
 * <h3>与旧服务的关系</h3>
 * <p>旧 {@code KeywordRagRetrievalService} 和 {@code VectorRagRetrievalService}
 * 可以逐步迁移为实现此接口的 CandidateRetriever，不再直接作为主入口。
 * Pipeline 通过此接口解耦具体检索策略。</p>
 *
 * @see KeywordRagCandidateRetriever
 * @see VectorRagCandidateRetriever
 * @see EffectiveScope
 */
public interface RagCandidateRetriever {

    /**
     * 检索策略标识。
     *
     * <p>这个名字不是随便给日志看的字符串，而是 Pipeline 内部识别候选来源的重要标签：</p>
     * <ul>
     *   <li>日志会打印它，帮助排查“这条候选是 keyword 召回还是 vector 召回”；</li>
     *   <li>{@link RetrievalCandidate#getSource()} 往往会直接复用它，作为候选来源字段；</li>
     *   <li>融合、fallback、观测指标里也会借助这个名字区分不同召回路径。</li>
     * </ul>
     *
     * @return 策略名，如 "keyword"、"vector"、"hybrid"，用于日志和 metrics
     */
    String strategyName();

    /**
     * 按作用域执行检索。
     *
     * <p>实现类在这里真正完成“查候选”动作。虽然 keyword 和 vector 的底层实现完全不同，
     * 但对 Pipeline 而言，它们都被抽象成同一种能力：给定一个已经解析好的作用域、一个查询文本和一个期望的 topK，
     * 返回一组候选片段。</p>
     *
     * <h3>调用方对返回值的预期</h3>
     * <ul>
     *   <li>返回的是候选集合，而不是最终 prompt 文本；</li>
     *   <li>候选中的分数允许使用各自检索器的原始评分模型；</li>
     *   <li>即使完全无命中，也应返回空列表而不是 null；</li>
     *   <li>排序可以保留实现类自己的自然顺序，最终统一排序由 Pipeline 完成。</li>
     * </ul>
     *
     * @param scope 解析后的作用域（含鉴权信息和 scope 类型）
     * @param query 用户查询文本（可能已经过 QueryAnalyzer 改写）
     * @param topK  期望返回的候选数上限（实际返回数可小于此值）
     * @return 候选列表（无需排序），无结果时返回空列表
     */
    List<RetrievalCandidate> retrieve(EffectiveScope scope, String query, int topK);
}
