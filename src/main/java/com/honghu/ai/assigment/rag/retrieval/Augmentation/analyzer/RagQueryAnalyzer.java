package com.honghu.ai.assigment.rag.retrieval.Augmentation.analyzer;

import java.util.List;

/**
 * Query Rewrite 分析器接口。
 *
 * <p>对用户原始查询做轻量分析，产出 1~N 个 query variant 供多路检索。
 * 变体越多、召回越宽，但也可能引入噪音。第一期仅做关键词提取变体，
 * 代词消解和复杂 NLP 留给后续阶段。</p>
 *
 * <h3>典型变体策略</h3>
 * <ol>
 *   <li><b>原查询</b> — 始终保留，排在第一位</li>
 *   <li><b>关键词变体</b> — 仅提取关键词用空格连接，更短更聚焦</li>
 *   <li><b>短变体</b> — 只取前 2-3 个关键词，最激进</li>
 * </ol>
 *
 * @see LightweightRuleQueryAnalyzer
 */
public interface RagQueryAnalyzer {

    /**
     * 分析查询，返回 query variant 列表。
     *
     * <p>这里的“分析”不是让实现类直接回答问题，而是把一个用户问题转换成更适合检索器理解的若干查询表达。
     * 例如用户问题很长、带有修饰词、口语化很强时，直接拿整句去检索可能召回不稳定，
     * 这时实现类就可以额外产出更短、更聚焦的 query variant 提高召回率。</p>
     *
     * <p>调用方通常会保留返回列表的顺序，因为第一个变体往往代表“最忠于原始问题”的版本，
     * 后面的变体则是逐步增强召回的补充路径。</p>
     *
     * @param originalQuery 用户原始查询文本
     * @return 变体列表，至少包含原查询本身；空查询返回空列表
     */
    List<String> analyze(String originalQuery);

    /**
     * No-Op 实现：直接返回仅含原查询的单元素列表。
     *
     * <p>它适用于不希望启用 query rewrite 的场景：
     * 不做任何改写、不增加任何检索噪音，只把用户原句原样往下传。</p>
     */
    static RagQueryAnalyzer noop() {
        // 直接把原查询包装成单元素列表，保持与 analyze 方法统一的返回类型约定。
        return List::of;
    }
}
