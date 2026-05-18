package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归结构切分器。
 *
 * <p>这个策略适合“存在明显结构边界，但边界并不总是稳定一致”的文本，尤其是 Markdown、说明文、知识库文档等。</p>
 *
 * <p>它的核心思路是：</p>
 * <ol>
 *   <li>先用较粗的分隔符尝试拆分，比如多空行、单空行；</li>
 *   <li>如果某一段仍然太长，就继续用更细的分隔符递归拆，比如句号、分号、逗号、空格；</li>
 *   <li>如果细到最后还是过长，就退化到固定窗口切分器兜底。</li>
 * </ol>
 *
 * <p>这种策略的好处是：相比简单窗口切分，它更努力地沿着“自然语言结构”下刀，
 * 因而更容易得到语义完整的 chunk。</p>
 *
 * <p><strong>当前项目中的默认适用文件类型：</strong></p>
 * <ul>
 *   <li>{@code md}</li>
 * </ul>
 *
 * <p>Markdown 虽然清洗后会丢掉一部分标记，但通常仍保留标题、换行、段落、句子等层次感，
 * 所以用递归结构切分比简单窗口更容易得到符合语义边界的结果。</p>
 */
@Slf4j
@Component("ragRecursiveStructureTextSplitter")
public class RagRecursiveStructureTextSplitter extends AbstractRagTextSplitter {

    /**
     * 从“粗到细”的分隔符优先级表。
     *
     * <p>顺序非常关键：越靠前越代表更大的结构边界，越靠后越代表更细的拆分粒度。</p>
     */
    private static final List<String> SEPARATORS = List.of(
            "\n\n\n", "\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", "；", "; ", "，", ", ", " ");

    /** 当递归拆到最后仍然过长时，回退使用固定窗口切分器。 */
    private final RagWindowOverlapTextSplitter fallback;

    /**
     * 创建递归结构切分器。
     *
     * @param ragProperties RAG 配置
     */
    public RagRecursiveStructureTextSplitter(RagProperties ragProperties) {
        super(ragProperties);
        this.fallback = new RagWindowOverlapTextSplitter(ragProperties);
    }

    @Override
    /**
     * 按“分隔符递归细化”的策略执行切分。
     *
     * @param text 已清洗文本
     * @param s 切分参数
     * @param base 基础 metadata
     * @return 切分后的 chunk 列表
     */
    protected List<ChunkCandidate> doChunk(String text, SplitterSettings s, ChunkMetadata base) {
        // 文本足够短时直接单块返回，不必进入递归流程。
        if (text.length() <= s.chunkSize()) return List.of(buildChunk(0, text, base));

        // 第一步：按优先级递归拆成若干更小的 segments。
        List<String> segs = splitRec(text, SEPARATORS, 0, s.chunkSize());

        // 第二步：把 segments 贪心打包回 chunk。
        List<ChunkCandidate> chunks = packSegments(segs, s, base);

        // 第三步：对相邻块补 overlap，保留边界附近上下文。
        return applyOverlap(chunks, s, base);
    }

    /**
     * 递归拆分文本。
     *
     * <p>每一层递归只负责尝试一种分隔符；如果拆完后某一段仍然过长，
     * 才继续下钻到更细一级的分隔符。</p>
     *
     * @param text 当前待拆文本
     * @param seps 分隔符优先级列表
     * @param idx 当前递归层使用的分隔符索引
     * @param cs 目标 chunkSize
     * @return 递归拆出的 segment 列表
     */
    private List<String> splitRec(String text, List<String> seps, int idx, int cs) {
        // 分隔符已经用尽时，不再继续递归，直接把当前文本作为最终片段返回。
        if (idx >= seps.size()) return List.of(text);

        // 取出当前递归层正在使用的分隔符。
        String sep = seps.get(idx);
        int sepLen = sep.length();
        List<String> parts = new ArrayList<>();
        int start = 0;

        // 手工扫描文本，把每次命中的分隔符连同前面的正文一起切成 part。
        while (start <= text.length()) {
            int end = text.indexOf(sep, start);
            if (end < 0) { parts.add(text.substring(start)); break; }
            // 这里把分隔符也保留在前一段末尾，避免句号/换行等结构信息丢失。
            parts.add(text.substring(start, end + sepLen));
            start = end + sepLen;
        }

        // 对仍然大于 chunkSize 的 part，继续用更细一级分隔符递归拆下去。
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            if (p.length() > cs && idx + 1 < seps.size()) result.addAll(splitRec(p, seps, idx + 1, cs));
            else result.add(p);
        }
        return result;
    }

    /**
     * 把递归拆出来的 segment 贪心打包回 chunk。
     *
     * @param segs segment 列表
     * @param s 切分参数
     * @param base 基础 metadata
     * @return 打包后的 chunk 列表
     */
    private List<ChunkCandidate> packSegments(List<String> segs, SplitterSettings s, ChunkMetadata base) {
        // chunks 保存最终输出；cur 保存当前正在累计的一块文本。
        List<ChunkCandidate> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int idx = 0, cs = s.chunkSize();
        for (String seg : segs) {
            // 如果某个 segment 仍然超长，说明递归结构边界已经不够用了，
            // 此时退化给固定窗口切分器，保证算法一定能结束。
            if (seg.length() > cs) {
                if (!cur.isEmpty()) { chunks.add(buildChunk(idx++, cur.toString(), base)); cur.setLength(0); }
                List<ChunkCandidate> fc = fallback.doChunk(seg, new SplitterSettings(cs, 0, 1, s.maxChunks()), base);
                for (ChunkCandidate c : fc) chunks.add(buildChunk(idx++, c.content(), base));
                continue;
            }

            // 能放进当前块就继续累加，尽量提升单块内部的语义连续性。
            if (cur.length() + seg.length() <= cs) { cur.append(seg); }
            else {
                // 放不下时，先提交当前块，再用当前 segment 开始新块。
                if (!cur.isEmpty()) chunks.add(buildChunk(idx++, cur.toString(), base));
                cur.setLength(0); cur.append(seg);
            }

            // 达到最大 chunk 数后立即返回，防止极端长文继续膨胀。
            if (chunks.size() >= s.maxChunks()) return chunks;
        }

        // 循环结束后，如果 cur 里还有剩余文本，要补成最后一个 chunk。
        if (!cur.isEmpty()) chunks.add(buildChunk(idx, cur.toString(), base));
        return chunks;
    }

    /**
     * 为递归结构切分结果补 overlap。
     *
     * @param chunks 原始 chunk 列表
     * @param s 切分参数
     * @param base 基础 metadata
     * @return 添加 overlap 后的结果
     */
    private List<ChunkCandidate> applyOverlap(List<ChunkCandidate> chunks, SplitterSettings s, ChunkMetadata base) {
        // 只有一块或 overlap 为 0 时，没有必要额外补重叠上下文。
        if (chunks.size() <= 1 || s.overlap() <= 0) return chunks;

        // 第一块没有前文，因此原样保留。
        List<ChunkCandidate> res = new ArrayList<>(); res.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            ChunkCandidate prev = res.get(res.size()-1), cur = chunks.get(i);
            String prevC = prev.content();

            // 从上一块末尾回退 overlap 个字符，作为寻找重叠区的起始位置。
            int ov = s.overlap(), start = Math.max(0, prevC.length() - ov);

            // 再尽量把起点贴到句子/换行等自然边界上。
            int b = findBoundary(prevC, start);

            // 把上一块尾部的 overlap 文本前置到当前块内容前面。
            res.add(buildChunk(cur.chunkIndex(), prevC.substring(b) + cur.content(), base));
        }
        return res;
    }

    /**
     * 在 overlap 起点附近寻找一个更自然的句子/换行边界。
     *
     * @param text 文本内容
     * @param from 原始 overlap 起始点
     * @return 调整后的边界位置
     */
    private int findBoundary(String text, int from) {
        // 在 from 附近向前搜索更自然的边界，让 overlap 不至于从单词中间硬截断。
        for (int i = Math.min(from + 80, text.length()-1); i >= Math.max(0, from-80); i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == ';' || c == '；') return i+1;
        }

        // 找不到自然边界时，只能退回原始 from 位置。
        return from;
    }
}
