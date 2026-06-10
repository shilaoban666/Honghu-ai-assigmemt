package com.honghu.ai.assigment.rag.retrieval.retriever;

import com.honghu.ai.assigment.rag.retrieval.retriever.RagRetrievalService.RagSnippet;
import lombok.Builder;
import lombok.Value;

/**
 * 单个检索候选结果。
 *
 * <p>由 {@link RagCandidateRetriever} 产出，尚未经过 fusion / rerank / augmentor 处理。
 * 携带原始分数和来源标识，是 Pipeline 内部各阶段之间传递的标准数据单元。</p>
 *
 * <h3>与 RagSnippet 的关系</h3>
 * <p>{@link RagSnippet} 是检索结果的"内容载体"（chunk 正文 + metadata），
 * RetrievalCandidate 在其基础上附加了检索元信息（来源、原始分数）。
 * Pipeline 内部用 RetrievalCandidate 做融合排序，最终提取 RagSnippet 给 formatter。</p>
 *
 * <h3>去重 Key</h3>
 * <p>稳定去重 key 格式：{@code documentId + ":" + chunkIndex}。
 * 同一个 chunk 从不同检索路径（keyword / vector / 不同 query variant）召回时，
 * 通过此 key 识别为同一候选。</p>
 */
@Value
@Builder
public class RetrievalCandidate {

    /**
     * 检索命中的内容片段。
     *
     * <p>这是候选真正承载“正文内容 + 文档元数据”的部分。
     * Pipeline 后续无论做融合、fallback 还是最终格式化，最终都会回到这个片段对象上。</p>
     */
    RagSnippet snippet;

    /**
     * 来源标识，如 "keyword"、"vector"、"keyword-fallback-session"。
     *
     * <p>这个字段主要用于可观测性和调试：当一段内容最终进入 Prompt 后，
     * 可以倒推它最初来自哪条召回链路、是否经历过 fallback、是否经过 RRF 重写来源标签。</p>
     */
    String source;

    /**
     * 原始分数（越大越相关），不同来源的分数尺度可能不同。
     *
     * <p>例如 keyword 可能是规则加权分，vector 可能是由距离换算出来的相似度，
     * 它们未必处于同一量纲。因此这个分数字段更像“当前检索器自己的排序依据”，
     * 在多路结果场景下通常还需要再经过 fusion 或统一排序处理。</p>
     */
    double rawScore;

    /**
     * 生成稳定的去重 key。
     *
     * <p>格式：{@code documentId:chunkIndex}，用于跨来源去重。
     * 当 chunkIndex 为 null 时用 "0" 兜底。</p>
     *
     * @return 去重 key
     */
    public String dedupKey() {
        // 先取出 chunkIndex，避免在字符串拼接里重复调用访问器，也让去重逻辑更易读。
        Integer ci = snippet.chunkIndex();

        // documentId + chunkIndex 基本可以稳定标识“同一文档中的同一个分块”。
        // 当 chunkIndex 缺失时用 0 兜底，确保 dedupKey 始终可用。
        return snippet.documentId() + ":" + (ci == null ? "0" : ci);
    }
}
