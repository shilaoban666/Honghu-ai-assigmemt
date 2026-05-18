package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.pipeline;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.scope.EffectiveScope;
import lombok.Builder;
import lombok.Value;
import java.util.List;

/**
 * RAG Pipeline 执行指标快照。
 *
 * <p>每次检索完成后输出一份指标，用于：
 * <ul>
 *   <li>观测各阶段候选数量变化（keyword → vector → fusion → rerank → final）</li>
 *   <li>判断是否触发了作用域 fallback</li>
 *   <li>排查为什么某次检索效果不好</li>
 *   <li>为后续 rerank / augmentor 灰度提供数据支撑</li>
 * </ul>
 *
 * <h3>典型数据流</h3>
 * <pre>
 *   keywordCandidateCount=12  →  vectorCandidateCount=8  →  afterFusionCount=15
 *   →  afterRerankCount=15  →  finalSnippetCount=4
 * </pre>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>说明</th></tr>
 *   <tr><td>{@code effectiveScopeType}</td><td>最终使用的 scope 类型（FILE_IDS / CHAT / SESSION / EMPTY）</td></tr>
 *   <tr><td>{@code keywordCandidateCount}</td><td>关键词检索候选数（多 query variant 时为累计）</td></tr>
 *   <tr><td>{@code vectorCandidateCount}</td><td>向量检索候选数（多 query variant 时为累计）</td></tr>
 *   <tr><td>{@code afterFusionCount}</td><td>fusion 去重合并后的候选数</td></tr>
 *   <tr><td>{@code afterRerankCount}</td><td>rerank 后的候选数（P5 前 = afterFusionCount）</td></tr>
 *   <tr><td>{@code finalSnippetCount}</td><td>最终注入 prompt 的片段数（topK 裁剪后）</td></tr>
 *   <tr><td>{@code fallbackTriggered}</td><td>是否触发了作用域 fallback</td></tr>
 *   <tr><td>{@code fallbackFromScope}</td><td>fallback 的源 scope（如 FILE_IDS→CHAT 时记录 FILE_IDS）</td></tr>
 *   <tr><td>{@code stageTimings}</td><td>各阶段耗时（毫秒），当前仅记录 total</td></tr>
 * </table>
 */
@Value
@Builder
public class RagPipelineMetrics {

    /**
     * 最终使用的 scope 类型。
     *
     * <p>这里记录的是主检索阶段一开始生效的 primary scope，
     * 便于排查“本轮为什么优先查附件/当前 chat/整个 session”。</p>
     */
    EffectiveScope.ScopeType effectiveScopeType;

    /**
     * 关键词检索候选数。
     *
     * <p>如果 Query Rewrite 产生了多个 query variant，这里通常是累计值，
     * 而不是某一路单独的候选数。</p>
     */
    int keywordCandidateCount;

    /**
     * 向量检索候选数。
     *
     * <p>它帮助判断 semantic recall 是否真正参与了本轮检索，
     * 以及向量侧相较关键词侧贡献了多少候选。</p>
     */
    int vectorCandidateCount;

    /**
     * fusion 后候选数。
     *
     * <p>这个数字通常会小于 keywordCandidateCount 与 vectorCandidateCount 的简单相加，
     * 因为 fusion 过程中会按去重 key 合并重复 chunk。</p>
     */
    int afterFusionCount;

    /**
     * rerank 后候选数（P5 前等于 afterFusionCount）。
     *
     * <p>当前系统还没有真正接入 rerank，但保留这个字段可以让未来扩展更平滑，
     * 不需要再改返回模型或日志结构。</p>
     */
    int afterRerankCount;

    /**
     * 最终注入的片段数。
     *
     * <p>这是最接近“最终给模型喂了多少资料”的指标，因为它已经经过排序和 topK 截断。</p>
     */
    int finalSnippetCount;

    /**
     * 是否触发了作用域 fallback。
     *
     * <p>它与 {@link RagResult#isFallbackTriggered()} 一起帮助上层快速知道：
     * 本次上下文是否来自扩大范围后的补召回。</p>
     */
    boolean fallbackTriggered;

    /**
     * fallback 源 scope。
     *
     * <p>例如 FILE_IDS -> CHAT 时，这里记录 FILE_IDS，说明是附件级别结果不足导致的回退。</p>
     */
    EffectiveScope.ScopeType fallbackFromScope;

    /**
     * 各阶段耗时。
     *
     * <p>当前通常至少包含一项 total，总耗时记录；未来可以继续拆出 retrieval、fusion、fallback、rerank 等更细阶段。</p>
     */
    List<StageTiming> stageTimings;

    /**
     * 单阶段耗时记录。
     */
    @Value
    @Builder
    public static class StageTiming {
        /**
         * 阶段名称，如 "total"、"retrieval"、"fusion"。
         *
         * <p>它表示这条耗时记录对应哪一个阶段，方便日志或监控系统按阶段聚合。</p>
         */
        String stage;
        /**
         * 耗时（毫秒）。
         *
         * <p>数值越大表示该阶段越慢，通常可用于定位瓶颈是在检索、融合还是其他步骤。</p>
         */
        long durationMs;
    }
}
