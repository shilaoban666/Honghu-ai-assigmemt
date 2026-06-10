package com.honghu.ai.assigment.rag.index.splitter;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.dto.record.ChunkCandidate;
import com.honghu.ai.assigment.rag.ChunkMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 句子窗口切分器。
 *
 * <p>这个策略比固定窗口更强调“句子完整性”：优先按句号、问号、叹号等边界拆句，
 * 再把多句打包进 chunk。</p>
 *
 * <p>它适合段落结构弱、但句子边界还算可识别的文本，例如：</p>
 * <ul>
 *   <li>OCR 抽出来的图片文字；</li>
 *   <li>标点比较完整、但换行并不可靠的文本；</li>
 *   <li>未来可能接入的图片 OCR / ASR 转写类文本。</li>
 * </ul>
 *
 * <p><strong>当前项目中的默认适用文件类型：</strong></p>
 * <ul>
 *   <li>{@code png}</li>
 *   <li>{@code jpg}</li>
 *   <li>{@code jpeg}</li>
 * </ul>
 *
 * <p>虽然当前 OCR parser 还是占位实现，但从设计上，这个切分器就是为“图片 OCR 后得到的弱结构文本”预留的默认策略。</p>
 */
@Slf4j
@Component("ragSentenceWindowTextSplitter")
public class RagSentenceWindowTextSplitter extends AbstractRagTextSplitter {

    /** 当单句本身过长时，退化使用固定窗口切分器兜底。 */
    private final RagWindowOverlapTextSplitter fallback;

    /**
     * 创建句子窗口切分器。
     *
     * @param ragProperties RAG 配置
     */
    public RagSentenceWindowTextSplitter(RagProperties ragProperties) {
        super(ragProperties);
        this.fallback = new RagWindowOverlapTextSplitter(ragProperties);
    }

    @Override
    /**
     * 按“句子优先”的策略执行切分。
     *
     * @param text 已清洗文本
     * @param s 切分参数
     * @param base 基础 metadata
     * @return 句子打包后的 chunk 列表
     */
    protected List<ChunkCandidate> doChunk(String text, SplitterSettings s, ChunkMetadata base) {
        // 先取出目标 chunk 大小，后续多处都会用到。
        int cs = s.chunkSize();

        // 短文本无需再拆句，直接单块返回。
        if (text.length() <= cs) return List.of(buildChunk(0, text, base));

        // 第一步：按句子边界把全文拆成句子列表。
        List<String> sentences = splitSentences(text);

        // 第二步：把多句尽量打包进每个 chunk。
        List<ChunkCandidate> chunks = packSentences(sentences, s, base);

        // 第三步：相邻块之间按“完整句子”粒度补 overlap。
        if (s.overlap() > 0 && chunks.size() > 1) chunks = applySentenceOverlap(chunks, s, base);
        return chunks;
    }

    /**
     * 把文本按句子边界拆成句子列表。
     *
     * <p>这里不追求语言学上的完美分句，而是采用对工程场景更友好的近似规则。</p>
     *
     * @param text 文本内容
     * @return 句子列表
     */
    private List<String> splitSentences(String text) {
        // sentences 保存最后拆出来的每个句子。
        List<String> sentences = new ArrayList<>();

        // cur 是当前正在累积的一句文本。
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            // 逐字符扫描文本，把字符先加入当前句缓存中。
            char c = text.charAt(i); cur.append(c);
            if (isEnd(c)) {
                // 如果句末标点后面紧跟右引号/右括号，也把它一并纳入当前句，避免标点和引号被拆开。
                if (i + 1 < text.length() && isQuote(text.charAt(i+1))) { cur.append(text.charAt(++i)); }

                // 当前句已经结束，提交到结果列表，并清空缓存开始下一句。
                sentences.add(cur.toString()); cur.setLength(0);
            }
        }

        // 如果文本末尾没有句末标点，最后剩下的一截也要作为一个句子保留。
        if (!cur.isEmpty()) sentences.add(cur.toString());
        return sentences;
    }

    /**
     * 判断一个字符是否可以视为句子结束标记。
     *
     * @param c 当前字符
     * @return 是否为句末边界
     */
    private boolean isEnd(char c) {
        // 这里把中英文句号、问号、叹号，以及分号都当成句子边界。
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '；' || c == ';';
    }

    /**
     * 判断一个字符是否属于句末后常见的闭合引号/括号。
     *
     * @param c 当前字符
     * @return 是否应当并入当前句尾
     */
    private boolean isQuote(char c) {
        // 这里收纳的是常见“句尾后面可能紧跟的闭合符号”。
        return c == '"' || c == '\'' || c == '」' || c == '』' || c == '》' || c == '）' || c == '】' || c == '”' || c == '’';
    }

    /**
     * 把拆出来的句子贪心打包为 chunk。
     *
     * @param sentences 句子列表
     * @param s 切分参数
     * @param base 基础 metadata
     * @return 打包后的 chunk 列表
     */
    private List<ChunkCandidate> packSentences(List<String> sentences, SplitterSettings s, ChunkMetadata base) {
        // chunks 保存最终结果；cur 保存当前正在累加的多句文本。
        List<ChunkCandidate> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int idx = 0, cs = s.chunkSize();
        for (String sent : sentences) {
            // 如果单句长度已经超过 chunkSize，说明“按句子”也装不下了，
            // 这时必须退回到窗口切分器进一步拆细。
            if (sent.length() > cs) {
                if (!cur.isEmpty()) { chunks.add(buildChunk(idx++, cur.toString(), base)); cur.setLength(0); }
                List<ChunkCandidate> fc = fallback.doChunk(sent, new SplitterSettings(cs, 0, 1, s.maxChunks()), base);
                for (ChunkCandidate c : fc) chunks.add(buildChunk(idx++, c.content(), base));
                continue;
            }

            // 还能装进当前块时继续累加，让一个 chunk 尽量包含多个完整句子。
            if (cur.length() + sent.length() <= cs) { cur.append(sent); }
            else {
                // 放不下时，先把已有内容提交为一个 chunk，再让当前句成为新块的开头。
                if (!cur.isEmpty()) chunks.add(buildChunk(idx++, cur.toString(), base));
                cur.setLength(0); cur.append(sent);
            }

            // 达到最大块数限制时立刻返回。
            if (chunks.size() >= s.maxChunks()) return chunks;
        }

        // 循环结束后若还有剩余句子，补成最后一个 chunk。
        if (!cur.isEmpty()) chunks.add(buildChunk(idx, cur.toString(), base));
        return chunks;
    }

    /**
     * 为句子级切分结果添加 overlap。
     *
     * <p>这里按“完整句子”而不是按字符长度来回收上一块尾部的上下文，
     * 目的是尽量保持 overlap 本身也具有良好的可读性。</p>
     *
     * @param chunks 原始 chunk 列表
     * @param s 切分参数
     * @param base 基础 metadata
     * @return 加上 overlap 后的新结果
     */
    private List<ChunkCandidate> applySentenceOverlap(List<ChunkCandidate> chunks, SplitterSettings s, ChunkMetadata base) {
        // 第一块没有前文，原样放入结果。
        List<ChunkCandidate> res = new ArrayList<>(); res.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            ChunkCandidate prev = res.get(res.size()-1), cur = chunks.get(i);

            // 先把上一块重新拆回句子列表，准备从尾部取 overlap 句子。
            List<String> prevSents = splitSentences(prev.content());
            StringBuilder ov = new StringBuilder();
            for (int j = prevSents.size()-1; j >= 0; j--) {
                String sent = prevSents.get(j);
                // 只要累计长度还没超过 overlap 上限，就把这句加到 overlap 前缀里。
                if (ov.length() + sent.length() <= s.overlap()) ov.insert(0, sent); else break;
            }

            // 只在确实拼出 overlap 文本时才构造新块；否则沿用原块，避免复制巨大句子。
            res.add(!ov.isEmpty() ? buildChunk(cur.chunkIndex(), ov + cur.content(), base) : cur);
        }
        return res;
    }
}
