package com.honghu.ai.assigment.rag.index.cleaner;

import com.honghu.ai.assigment.rag.index.parser.RagDocumentParser;

/**
 * 文本清洗策略接口（Strategy）。
 *
 * <p>这一层位于 parser 之后、splitter 之前，是整条 RAG 文本预处理链路的“中间整理工序”。</p>
 *
 * <p>它接收
 * {@link RagDocumentParser}
 * 的原始输出，然后负责把文本整理成更适合后续切分、向量化、检索的形态。</p>
 *
 * <p>典型职责包括：</p>
 * <ul>
 *   <li>去掉控制字符或不可见脏数据；</li>
 *   <li>统一换行符和空白格式；</li>
 *   <li>移除 Markdown / PDF 等格式噪音；</li>
 *   <li>按配置截断超长文本，避免后续处理成本过高。</li>
 * </ul>
 *
 * <p>之所以抽成接口，是因为不同文件类型的“噪音”差异很大：
 * PDF 常见连字符断词，Markdown 常见标记语法，普通文本则只需要基础清洗。</p>
 */
public interface RagTextCleaner {

    /**
     * 对原始文本进行规范化清洗。
     *
     * <p>这里的“清洗”不是简单的字符串替换，而是为了让后续 chunk 更稳定、
     * embedding 质量更一致、检索召回更准确。</p>
     *
     * @param rawText parser 输出的原始文本，可能仍带有格式噪音
     * @param maxCharacters 最大保留字符数；超出时应进行截断，避免后续处理失控
     * @return 清洗后的文本；输入为空或只含空白时返回空字符串，不返回 {@code null}
     */
    String clean(String rawText, int maxCharacters);
}
