package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 句子窗口切分器。png/jpg/jpeg 默认策略（OCR 占位）。
 */
@Slf4j
@Component("ragSentenceWindowTextSplitter")
public class RagSentenceWindowTextSplitter extends AbstractRagTextSplitter {

    private final RagWindowOverlapTextSplitter fallback;

    public RagSentenceWindowTextSplitter(RagProperties ragProperties) {
        super(ragProperties);
        this.fallback = new RagWindowOverlapTextSplitter(ragProperties);
    }

    @Override
    protected List<ChunkCandidate> doChunk(String text, SplitterSettings s, ChunkMetadata base) {
        int cs = s.chunkSize();
        if (text.length() <= cs) return List.of(buildChunk(0, text, base));
        List<String> sentences = splitSentences(text);
        List<ChunkCandidate> chunks = packSentences(sentences, s, base);
        if (s.overlap() > 0 && chunks.size() > 1) chunks = applySentenceOverlap(chunks, s, base);
        return chunks;
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i); cur.append(c);
            if (isEnd(c)) {
                if (i + 1 < text.length() && isQuote(text.charAt(i+1))) { cur.append(text.charAt(++i)); }
                sentences.add(cur.toString()); cur.setLength(0);
            }
        }
        if (cur.length() > 0) sentences.add(cur.toString());
        return sentences;
    }

    private boolean isEnd(char c) { return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '；' || c == ';'; }
    private boolean isQuote(char c) { return c == '"' || c == '\'' || c == '」' || c == '』' || c == '》' || c == '）' || c == '】' || c == '\u201D' || c == '\u2019'; }

    private List<ChunkCandidate> packSentences(List<String> sentences, SplitterSettings s, ChunkMetadata base) {
        List<ChunkCandidate> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int idx = 0, cs = s.chunkSize();
        for (String sent : sentences) {
            if (sent.length() > cs) {
                if (cur.length() > 0) { chunks.add(buildChunk(idx++, cur.toString(), base)); cur.setLength(0); }
                List<ChunkCandidate> fc = fallback.doChunk(sent, new SplitterSettings(cs, 0, 1, s.maxChunks()), base);
                for (ChunkCandidate c : fc) chunks.add(buildChunk(idx++, c.content(), base));
                continue;
            }
            if (cur.length() + sent.length() <= cs) { cur.append(sent); }
            else { if (cur.length() > 0) chunks.add(buildChunk(idx++, cur.toString(), base)); cur.setLength(0); cur.append(sent); }
            if (chunks.size() >= s.maxChunks()) return chunks;
        }
        if (cur.length() > 0) chunks.add(buildChunk(idx, cur.toString(), base));
        return chunks;
    }

    private List<ChunkCandidate> applySentenceOverlap(List<ChunkCandidate> chunks, SplitterSettings s, ChunkMetadata base) {
        List<ChunkCandidate> res = new ArrayList<>(); res.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            ChunkCandidate prev = res.get(res.size()-1), cur = chunks.get(i);
            List<String> prevSents = splitSentences(prev.content());
            StringBuilder ov = new StringBuilder();
            for (int j = prevSents.size()-1; j >= 0; j--) {
                String sent = prevSents.get(j);
                if (ov.length() + sent.length() <= s.overlap()) ov.insert(0, sent); else break;
            }
            res.add(ov.length() > 0 ? buildChunk(cur.chunkIndex(), ov + cur.content(), base) : cur);
        }
        return res;
    }
}
