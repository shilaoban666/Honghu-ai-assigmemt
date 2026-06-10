package com.honghu.ai.assigment.rag.retrieval.Augmentation.fusion;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.rag.retrieval.retriever.RetrievalCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * RRF (Reciprocal Rank Fusion) 融合器。
 *
 * <p>对多路检索结果按 RRF 公式重新打分。RRF 的核心思想是：
 * 一个候选在越多路结果中排名靠前，越有可能是好结果。</p>
 *
 * <h3>RRF 公式</h3>
 * <pre>score(d) = Σ 1 / (k + rank_i(d))</pre>
 * <ul>
 *   <li>{@code d} — 候选文档/chunk</li>
 *   <li>{@code k} — 平滑参数（默认 60），防止 rank 很小的结果权重过大</li>
 *   <li>{@code rank_i(d)} — 候选 d 在第 i 路结果中的排名（1-indexed）</li>
 * </ul>
 *
 * <h3>融合流程</h3>
 * <ol>
 *   <li>遍历每路结果，对每个候选计算 RRF 分数并累加</li>
 *   <li>保留原始分数最高的那个 candidate 实例</li>
 *   <li>按 RRF 分数降序排列</li>
 *   <li>截断到 {@code candidateLimitAfterFusion} 个候选</li>
 * </ol>
 *
 * <h3>为什么用 RRF 而不是线性加权</h3>
 * <ul>
 *   <li>不依赖分数归一化：keyword 和 vector 的分数尺度不同，RRF 只关注排名</li>
 *   <li>数学性质好：排名靠前的候选贡献大，但不会因为单一高分垄断</li>
 *   <li>工业验证：在多个检索评测中表现稳定</li>
 * </ul>
 *
 * @see RagResultFusion
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RrfRagResultFusion implements RagResultFusion {

    private final RagProperties ragProperties;

    /**
     * 按 RRF 算法融合多路候选结果。
     *
     * <p>单路输入时直接返回（不做 RRF），避免无意义计算。</p>
     */
    @Override
    public List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> candidateLists, int rrfK) {
        // 没有任何输入列表时，自然也没有可融合的候选。
        if (candidateLists == null || candidateLists.isEmpty()) return List.of();
        if (candidateLists.size() == 1) {
            // 单路结果：只去重，不做 RRF
            List<RetrievalCandidate> single = new ArrayList<>();
            Map<String, RetrievalCandidate> seen = new LinkedHashMap<>();
            for (RetrievalCandidate c : candidateLists.get(0)) {
                // 只保留第一次出现的 dedupKey，既防止重复 chunk，也保留原列表顺序。
                if (seen.putIfAbsent(c.dedupKey(), c) == null) {
                    single.add(c);
                }
            }
            return single;
        }

        // 优先使用调用方显式传入的 rrfK；未传或非法时再回退到配置值。
        int k = rrfK > 0 ? rrfK : ragProperties.getRetrieval().getFusion().getRrfK();

        // dedupKey → 累计 RRF 分数
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        // dedupKey → 保留原始分数最高的 candidate
        Map<String, RetrievalCandidate> bestCandidates = new LinkedHashMap<>();

        for (List<RetrievalCandidate> list : candidateLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                // 取出当前列表中 rank 位置的候选，准备把它对 RRF 的贡献累计进去。
                RetrievalCandidate c = list.get(rank);
                String key = c.dedupKey();
                // rank 从 0 开始，转为 1-indexed
                double rrfScore = 1.0 / (k + rank + 1);
                // 同一个候选如果在多路结果里都出现，就把各路的 RRF 贡献分数累加起来。
                rrfScores.merge(key, rrfScore, Double::sum);
                RetrievalCandidate existing = bestCandidates.get(key);
                // bestCandidates 保存“这个 dedupKey 对应的代表性 candidate 实例”，
                // 当前策略选择原始分数更高的那个，便于保留更可信的 snippet/source 元信息。
                if (existing == null || c.getRawScore() > existing.getRawScore()) {
                    bestCandidates.put(key, c);
                }
            }
        }

        // 按 RRF 分数降序排列
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        int limit = ragProperties.getRetrieval().getFusion().getCandidateLimitAfterFusion();
        List<RetrievalCandidate> result = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < limit; i++) {
            // 取出当前 RRF 排名靠前的 dedupKey，再找到它对应的最佳 candidate 实例。
            String key = sorted.get(i).getKey();
            RetrievalCandidate c = bestCandidates.get(key);
            double rrf = sorted.get(i).getValue();
            result.add(RetrievalCandidate.builder()
                    // snippet 保留原候选的正文和元数据，不在融合阶段改写内容本身。
                    .snippet(c.getSnippet())
                    // source 拼上 -rrf，表示这条候选最终排序分来自融合结果，而不是单一路原始打分。
                    .source(c.getSource() + "-rrf")
                    // rawScore 改写成 RRF 分数，方便 Pipeline 继续直接按 rawScore 统一排序。
                    .rawScore(rrf)
                    .build());
        }

        log.debug("RRF fusion: {} lists → {} candidates (k={}, limit={})",
                candidateLists.size(), result.size(), k, limit);
        return result;
    }
}
