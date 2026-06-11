package com.honghu.ai.assigment.rag.retrieval.Augmentation.analyzer;

import java.util.List;
import java.util.Map;

/**
 * 同义词词典接口。
 *
 * <p>将查询中的词扩展为同义词集合，提升召回率。
 * 第一期默认空词典（无同义词扩展），后续可接入外部词典、静态配置或 LLM 生成。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   SynonymDictionary dict = SynonymDictionary.fromMap(Map.of(
 *       "计算机", List.of("计算机", "电脑", "PC"),
 *       "人工智能", List.of("人工智能", "AI", "机器学习")
 *   ));
 *   dict.lookup("计算机"); // → ["计算机", "电脑", "PC"]
 *   dict.lookup("未知词");  // → ["未知词"]  (原样返回)
 * }</pre>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>lookup 返回值必须包含原词（避免丢失原查询信息）</li>
 *   <li>空词  一输入多次调用返回相同结果</li>
 * </ul>
 */
public interface SynonymDictionary {

    /**
     * 查找同义词列表。
     *
     * @param word 原始词
     * @return 同义词列表（含原词本身），空词典时返回仅含原词的单元素列表
     */
    List<String> lookup(String word);

    /**
     * 空词典实现：不做任何扩展，直接返回原词。
     *
     * <p>这是一个非常重要的兜底实现：它让调用方始终可以“按有词典的方式使用词典”，
     * 而不需要在外层到处写 {@code if (dict != null)} 之类的判空逻辑。</p>
     */
    static SynonymDictionary empty() {
        // 不扩展任何额外词项，直接把原词包装为单元素列表返回。
        return List::of;
    }

    /**
     * 从静态 Map 构建词典。
     *
     * @param map key=词（小写），value=同义词列表
     * @return 基于 Map 查找的词典实现
     */
    static SynonymDictionary fromMap(Map<String, List<String>> map) {
        return word -> {
            // 统一转小写查表，减少“配置里写小写、调用时传大写/混合大小写”导致的查不到问题。
            List<String> synonyms = map.get(word.toLowerCase());
            // 如果没有配置同义词，就至少把原词原样返回，避免扩展阶段把原始检索词丢掉。
            return synonyms == null || synonyms.isEmpty() ? List.of(word) : synonyms;
        };
    }
}
