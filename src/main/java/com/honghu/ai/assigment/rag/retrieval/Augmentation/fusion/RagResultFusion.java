package com.honghu.ai.assigment.rag.retrieval.Augmentation.fusion;

import com.honghu.ai.assigment.rag.retrieval.retriever.RetrievalCandidate;
import java.util.List;

/**
 * 结果融合器接口。
 *
 * <p>将多个来源（keyword + vector）、多个 query variant 的检索结果
 * 合并去重，输出统一的候选列表供后续 rerank / augmentor 处理。</p>
 *
 * <h3>融合前 vs 融合后</h3>
 * <pre>
 *   融合前：[keyword结果], [vector结果], [variant1结果], [variant2结果]  (4 路)
 *   融合后：[统一的候选列表，按融合分数降序]  (1 路)
 * </pre>
 *
 * <h3>实现要求</h3>
 * <ul>
 *   <li>必须按 {@link RetrievalCandidate#dedupKey()} 去重</li>
 *   <li>单路输入时保持原排序，不做无意义计算</li>
 *   <li>空列表处理：null 安全，入参 null 或空时返回空列表</li>
 * </ul>
 *
 * @see RrfRagResultFusion
 */
public interface RagResultFusion {

    /**
     * 融合多路候选结果。
     *
     * <p>注意：这里的“融合”并不只是把多个列表拼起来，而是要解决两个核心问题：</p>
     * <ul>
     *   <li>同一个 chunk 可能从不同检索器或不同 query variant 中重复出现，需要稳定去重；</li>
     *   <li>多路候选如何决定统一排序，需要一个明确的融合评分或优先级策略。</li>
     * </ul>
     *
     * @param candidateLists 各路检索结果（每路按原始分数降序排列）
     * @param rrfK           RRF k 参数（仅 RRF 算法使用，其他算法可忽略）
     * @return 融合后的统一候选列表（按融合分数降序）
     */
    List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> candidateLists, int rrfK);

    /**
     * No-Op 实现：只做简单去重合并（先到先保留）。
     *
     * <p>不重新排序，不重新打分。适合不需要融合的简单场景。</p>
     */
    static RagResultFusion noop() {
        return (lists, k) -> {
            java.util.Map<String, RetrievalCandidate> merged = new java.util.LinkedHashMap<>();
            for (List<RetrievalCandidate> list : lists) {
                for (RetrievalCandidate c : list) {
                    // 只保留第一次出现的候选；谁先进入列表，谁就代表该 chunk。
                    merged.putIfAbsent(c.dedupKey(), c);
                }
            }
            // 返回保持原始到达顺序的候选列表，不额外调整分数和排序。
            return new java.util.ArrayList<>(merged.values());
        };
    }
}
