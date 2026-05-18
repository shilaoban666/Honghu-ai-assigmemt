package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;

import java.util.List;

/**
 * RAG 文本切分策略接口。
 *
 * <p>它位于整个 RAG 预处理链路中的“最后一道文本加工工序”：</p>
 * <ol>
 *   <li>上游 parser 先把文件解析成原始文本；</li>
 *   <li>cleaner 再把文本清洗成较稳定的正文；</li>
 *   <li>最后由 splitter 把整篇长文本拆成一组可检索、可向量化、可回填的 chunk。</li>
 * </ol>
 *
 * <p>为什么一定要切分？因为一整篇文档通常太长：</p>
 * <ul>
 *   <li>直接做关键词检索时，长文本粒度太粗；</li>
 *   <li>直接做 embedding 时，会把多个主题糊成一个向量；</li>
 *   <li>最终送给大模型时，也不可能无限塞整篇原文。</li>
 * </ul>
 *
 * <p>不同实现类的差异，主要在于“怎么找边界”：</p>
 * <ul>
 *   <li>有的按固定字符窗口；</li>
 *   <li>有的按句子；</li>
 *   <li>有的按段落；</li>
 *   <li>有的尽量递归沿着自然结构拆分。</li>
 * </ul>
 *
 * <p>切分的最终产物是 {@link ChunkCandidate}，里面不仅有正文，还有字符数、token 估算、metadata 等信息，
 * 供后续数据库落库、Milvus 向量写入、检索结果回填等环节统一使用。</p>
 */
public interface RagTextSplitter {

    /**
     * 无 metadata 版本的切分入口。
     *
     * <p>这是最常见的调用方式，适用于只关心“把一段文本切开”，
     * 而暂时不需要给每个 chunk 额外附带来源元信息的场景。</p>
     *
     * <p>实现上，它通常等价于：</p>
     * <pre>{@code
     * chunk(text, ChunkMetadata.EMPTY)
     * }</pre>
     *
     * @param text 已经完成清洗的正文文本
     * @return chunk 列表；空文本时通常返回空列表
     */
    List<ChunkCandidate> chunk(String text);

    /**
     * 带基础 metadata 的切分入口。
     *
     * <p>这里的 {@link ChunkMetadata} 可以理解成“所有 chunk 共享的一份基础上下文”，例如：</p>
     * <ul>
     *   <li>原文来源区段；</li>
     *   <li>章节信息；</li>
     *   <li>清洗前后 offset；</li>
     *   <li>未来可能扩展的页码、标题层级等信息。</li>
     * </ul>
     *
     * <p>具体 splitter 在切分时，会在这个 baseMetadata 之上继续叠加 chunk 自己的 offset 信息，
     * 让每一块既知道“来自哪篇文档的大上下文”，也知道“自己在清洗后文本中的具体位置”。</p>
     *
     * @param text 已清洗文本
     * @param baseMetadata 基础元数据；允许为空，通常会被实现类安全替换为 {@code ChunkMetadata.EMPTY}
     * @return 切分后的 chunk 列表
     */
    List<ChunkCandidate> chunk(String text, ChunkMetadata baseMetadata);
}
