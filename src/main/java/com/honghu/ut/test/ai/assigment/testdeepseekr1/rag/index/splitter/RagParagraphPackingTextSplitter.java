package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落打包切分器。
 *
 * <p>这个策略的核心思想不是“机械地每 N 个字符切一刀”，而是：</p>
 * <ul>
 *   <li>先尽量识别段落；</li>
 *   <li>再尽量把完整段落打包进同一个 chunk；</li>
 *   <li>只有在单段本身过长时，才退化到更细粒度的切分策略。</li>
 * </ul>
 *
 * <p>它特别适合 PDF / DOCX 这种“正文型文档”，因为这类文档在清洗之后往往还保留着较自然的段落边界。</p>
 *
 * <p><strong>当前项目中的默认适用文件类型：</strong></p>
 * <ul>
 *   <li>{@code pdf}</li>
 *   <li>{@code docx}</li>
 * </ul>
 *
 * <p>这类文件的正文通常篇幅较长、段落相对完整，所以用“段落优先打包”的方式能更好保留语义连续性。</p>
 */
@Slf4j
@Component("ragParagraphPackingTextSplitter")
public class RagParagraphPackingTextSplitter extends AbstractRagTextSplitter {

    /** 当单段落太长时，退化使用递归结构切分器进一步拆分。 */
    private final RagRecursiveStructureTextSplitter fallback;

    /**
     * 创建段落打包切分器。
     *
     * @param ragProperties RAG 配置
     */
    public RagParagraphPackingTextSplitter(RagProperties ragProperties) {
        super(ragProperties);
        this.fallback = new RagRecursiveStructureTextSplitter(ragProperties);
    }

    @Override
    /**
     * 按“段落优先”的策略执行切分。
     *
     * <p>目标不是绝对卡死在 {@code chunkSize}，而是尽量让段落保持完整，
     * 因此会允许单块长度上浮到一个较宽松的上限。</p>
     *
     * @param text 已清洗文本
     * @param s 切分参数
     * @param base 基础 metadata
     * @return 切分得到的 chunk 列表
     */
    protected List<ChunkCandidate> doChunk(String text, SplitterSettings s, ChunkMetadata base) {
        // cs 是理想 chunk 大小；ub 是允许的上浮上限，给“整段保留”留一点空间。
        int cs = s.chunkSize(), ub = Math.max(cs, cs * 3 / 2);

        // 如果整篇文本本来就不长，就直接返回单块，不必再做复杂拆分。
        if (text.length() <= ub) return List.of(buildChunk(0, text, base));

        // 先按空行拆成若干段落。
        List<String> paragraphs = splitParagraphs(text);

        // groups 表示“每个 chunk 最终会由哪些段落组成”。
        List<List<String>> groups = new ArrayList<>();

        // cur / curLen 表示当前正在打包中的段落组与其累计长度。
        List<String> cur = new ArrayList<>();
        int curLen = 0;

        for (String p : paragraphs) {
            // 如果单个段落本身已经超过允许上限，就不能强行塞进当前段落包，
            // 否则一个 chunk 会大得过分，所以这里直接退化给 fallback 拆细。
            if (p.length() > ub) {
                // 在处理超长段落之前，先把当前已经积累的段落组提交出去。
                if (!cur.isEmpty()) { groups.add(new ArrayList<>(cur)); cur.clear(); curLen = 0; }

                // 用递归结构切分器拆超长段，再把它的内容作为独立 group 放回当前流程。
                List<ChunkCandidate> fc = fallback.doChunk(p, new SplitterSettings(cs, 0, 1, s.maxChunks()), base);
                for (ChunkCandidate c : fc) groups.add(List.of(c.content()));
                continue;
            }

            // 如果当前段落还能放进当前组，就继续累加，尽量保持多个相邻段落在同一块里。
            if (curLen + p.length() <= ub) { cur.add(p); curLen += p.length(); }
            else {
                // 放不下时，先把旧组提交，再让当前段落开启一个新组。
                if (!cur.isEmpty()) groups.add(new ArrayList<>(cur));
                cur = new ArrayList<>(); cur.add(p); curLen = p.length();
            }

            // 到达最大 chunk 数上限后就停止继续分组。
            if (groups.size() >= s.maxChunks()) break;
        }

        // 循环结束后，如果当前组还有残留段落，补交出去。
        if (!cur.isEmpty() && groups.size() < s.maxChunks()) groups.add(cur);

        // 把每个段落组真正拼装成 ChunkCandidate。
        List<ChunkCandidate> chunks = new ArrayList<>();
        int idx = 0;
        for (List<String> g : groups) chunks.add(buildChunk(idx++, String.join("\n", g), base));

        // 如果配置了 overlap，则在相邻块之间做段落级上下文补偿。
        if (s.overlap() > 0 && chunks.size() > 1) chunks = applyParagraphOverlap(chunks, s, base);
        return chunks;
    }

    /**
     * 按空行拆分段落。
     *
     * <p>这是一个非常实用主义的段落识别方式：不依赖复杂样式信息，
     * 仅基于清洗后文本中的空行结构来判断段落边界。</p>
     *
     * @param text 已清洗文本
     * @return 识别出的非空段落列表
     */
    private List<String> splitParagraphs(String text) {
        // ps 用于保存清洗后识别出的有效段落。
        List<String> ps = new ArrayList<>();

        // 使用“一个或多个空行”作为段落分隔符。
        // 这里依赖 cleaner 已经对空行做过一定规范化处理。
        for (String p : text.split("\\n\\s*\\n+")) {
            // 每段先 trim，避免把段前段后残留空白算进正文。
            String t = p.trim();
            if (!t.isEmpty()) ps.add(t);
        }
        return ps;
    }

    /**
     * 为段落打包结果补上段落级 overlap。
     *
     * <p>优先复用上一块的最后一个短段落；如果最后一个段落太长，
     * 再退化为截取末尾字符并回溯自然边界。</p>
     *
     * @param chunks 原始 chunk 列表
     * @param s 切分参数
     * @param base 基础 metadata
     * @return 加上 overlap 后的新 chunk 列表
     */
    private List<ChunkCandidate> applyParagraphOverlap(List<ChunkCandidate> chunks, SplitterSettings s, ChunkMetadata base) {
        // 结果列表先放入第一块；第一块没有前文，不需要 overlap。
        List<ChunkCandidate> res = new ArrayList<>(); res.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            ChunkCandidate prev = res.get(res.size()-1), cur = chunks.get(i);

            // 优先尝试直接拿上一块的“最后一段”作为 overlap，这比生硬截字符更自然。
            String[] pp = prev.content().split("\\n");
            if (pp.length > 0 && pp[pp.length-1].length() <= s.overlap()) {
                res.add(buildChunk(cur.chunkIndex(), pp[pp.length-1] + "\n" + cur.content(), base));
                continue;
            }

            // 如果最后一段太长，就退化为“取末尾 overlap 字符”，再尽量往自然边界回溯。
            int start = Math.max(0, prev.content().length() - s.overlap());
            int b = findBoundary(prev.content(), start);
            res.add(buildChunk(cur.chunkIndex(), prev.content().substring(b) + cur.content(), base));
        }
        return res;
    }

    /**
     * 在给定位置附近寻找一个更自然的边界。
     *
     * @param text 文本内容
     * @param from 原始起点
     * @return 更适合作为 overlap 边界的位置
     */
    private int findBoundary(String text, int from) {
        // 在 from 附近前后小范围搜索一个更适合作为切点的位置。
        for (int i = Math.min(from+80, text.length()-1); i >= Math.max(0, from-80); i--) {
            char c = text.charAt(i);
            // 找到换行或常见句末标点，就把边界放在它后面。
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') return i+1;
        }

        // 如果实在找不到更合适的位置，就退回原始起点。
        return from;
    }
}
