package com.honghu.ai.assigment.rag.index.splitter;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.dto.record.ChunkCandidate;
import com.honghu.ai.assigment.rag.ChunkMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定窗口 + overlap 文本切分器。
 *
 * <p>这是最直接、最稳定、也最容易解释的一种切分策略：</p>
 * <ol>
 *   <li>按固定字符窗口向前推进；</li>
 *   <li>窗口结束点尽量往回贴近自然边界；</li>
 *   <li>相邻块之间保留少量 overlap，避免语义在边界处被硬切断。</li>
 * </ol>
 *
 * <p>它非常适合结构不明显的文本，例如：</p>
 * <ul>
 *   <li>普通 txt；</li>
 *   <li>JSON / XML / CSV 这类虽然有结构，但对 RAG 来说通常还是按文本处理的内容；</li>
 *   <li>没有稳定段落边界的杂项文本。</li>
 * </ul>
 *
 * <p><strong>当前项目中的默认适用文件类型：</strong></p>
 * <ul>
 *   <li>{@code txt}</li>
 *   <li>{@code json}</li>
 *   <li>{@code xml}</li>
 *   <li>{@code csv}</li>
 * </ul>
 *
 * <p>也就是说，当文件整体更像“连续文本”而不是“强段落结构文档”时，优先考虑这个切分器。</p>
 */
@Slf4j
@Component("ragWindowOverlapTextSplitter")
public class RagWindowOverlapTextSplitter extends AbstractRagTextSplitter {

    /**
     * 创建固定窗口切分器。
     *
     * @param ragProperties RAG 配置
     */
    public RagWindowOverlapTextSplitter(RagProperties ragProperties) {
        // 这里只做配置对象透传，让父类统一读取 chunkSize / overlap / minChunkLength 等设置。
        super(ragProperties);
    }

    /**
     * 按固定窗口执行切分。
     *
     * <p>算法流程：</p>
     * <ol>
     *   <li>从 {@code start} 开始取一个长度约为 {@code chunkSize} 的窗口；</li>
     *   <li>把窗口结束点尽量调整到自然边界；</li>
     *   <li>生成当前 chunk；</li>
     *   <li>下一轮从 {@code adjustedEnd - overlap} 附近继续。</li>
     * </ol>
     *
     * @param text 已清洗文本
     * @param settings 切分参数
     * @param base 基础 metadata
     * @return 切分得到的 chunk 列表
     */
    @Override
    protected List<ChunkCandidate> doChunk(String text, SplitterSettings settings, ChunkMetadata base) {
        // 先把本次切分所需的参数取到局部变量里，方便后续阅读与调试。
        int cs = settings.chunkSize(), ov = settings.overlap();
        int minLen = settings.minChunkLength(), maxC = settings.maxChunks();

        // chunks 用于收集最终输出结果。
        List<ChunkCandidate> chunks = new ArrayList<>();

        // index 是 chunk 序号；start 是当前窗口的起始偏移量。
        int index = 0, start = 0;

        // 只要窗口起点还没越过文本末尾，就继续向前切分。
        while (start < text.length()) {
            // 达到最大 chunk 数时立即停止，避免异常超长文本产生过多结果。
            if (chunks.size() >= maxC) break;

            // rawEnd 是“如果完全按固定窗口切”，当前块本应结束的位置。
            int rawEnd = Math.min(start + cs, text.length());

            // adjustedEnd 会在 rawEnd 附近回退到更自然的边界，例如句号、换行处。
            int adjustedEnd = adjustChunkEnd(text, start, rawEnd, minLen);

            // 用 [start, adjustedEnd) 这个区间截出当前 chunk 的正文。
            String content = text.substring(start, adjustedEnd);

            // 如果刚好切到一段全空白内容，并且后面还有文本，就跳过这块，避免产出无意义 chunk。
            if (content.trim().isEmpty() && adjustedEnd < text.length()) {
                // 下一轮起点按 overlap 规则推进，但至少要比当前 start 大 1，防止卡死原地。
                start = Math.max(adjustedEnd - ov, start + 1);
                continue;
            }

            // 构造当前 chunk，并把 cleaned 文本中的起止 offset 一起记进 metadata。
            chunks.add(buildChunk(index++, content, base, start, adjustedEnd));

            // 如果已经到达文本末尾，说明最后一块也切完了，可以结束循环。
            if (adjustedEnd >= text.length()) break;

            // 下一块起点向后推进，但会保留 ov 个字符的重叠区间。
            start = Math.max(adjustedEnd - ov, start + 1);
        }

        // debug 日志只打印简要摘要，帮助观察切分结果规模，不把正文刷进日志。
        if (log.isDebugEnabled() && !chunks.isEmpty()) {
            log.debug("RAG window split: chunks={}, firstChars={}, lastChars={}",
                    chunks.size(), chunks.get(0).charCount(), chunks.get(chunks.size()-1).charCount());
        }
        return chunks;
    }
}
