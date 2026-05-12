package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归结构切分器。md 文件默认策略。
 */
@Slf4j
@Component("ragRecursiveStructureTextSplitter")
public class RagRecursiveStructureTextSplitter extends AbstractRagTextSplitter {

    private static final List<String> SEPARATORS = List.of(
            "\n\n\n", "\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", "；", "; ", "，", ", ", " ");

    private final RagWindowOverlapTextSplitter fallback;

    public RagRecursiveStructureTextSplitter(RagProperties ragProperties) {
        super(ragProperties);
        this.fallback = new RagWindowOverlapTextSplitter(ragProperties);
    }

    @Override
    protected List<ChunkCandidate> doChunk(String text, SplitterSettings s, ChunkMetadata base) {
        if (text.length() <= s.chunkSize()) return List.of(buildChunk(0, text, base));
        List<String> segs = splitRec(text, SEPARATORS, 0, s.chunkSize());
        List<ChunkCandidate> chunks = packSegments(segs, s, base);
        return applyOverlap(chunks, s, base);
    }

    private List<String> splitRec(String text, List<String> seps, int idx, int cs) {
        if (idx >= seps.size()) return List.of(text);
        String sep = seps.get(idx);
        int sepLen = sep.length();
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf(sep, start);
            if (end < 0) { parts.add(text.substring(start)); break; }
            parts.add(text.substring(start, end + sepLen));
            start = end + sepLen;
        }
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            if (p.length() > cs && idx + 1 < seps.size()) result.addAll(splitRec(p, seps, idx + 1, cs));
            else result.add(p);
        }
        return result;
    }

    private List<ChunkCandidate> packSegments(List<String> segs, SplitterSettings s, ChunkMetadata base) {
        List<ChunkCandidate> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int idx = 0, cs = s.chunkSize();
        for (String seg : segs) {
            if (seg.length() > cs) {
                if (cur.length() > 0) { chunks.add(buildChunk(idx++, cur.toString(), base)); cur.setLength(0); }
                List<ChunkCandidate> fc = fallback.doChunk(seg, new SplitterSettings(cs, 0, 1, s.maxChunks()), base);
                for (ChunkCandidate c : fc) chunks.add(buildChunk(idx++, c.content(), base));
                continue;
            }
            if (cur.length() + seg.length() <= cs) { cur.append(seg); }
            else {
                if (cur.length() > 0) chunks.add(buildChunk(idx++, cur.toString(), base));
                cur.setLength(0); cur.append(seg);
            }
            if (chunks.size() >= s.maxChunks()) return chunks;
        }
        if (cur.length() > 0) chunks.add(buildChunk(idx, cur.toString(), base));
        return chunks;
    }

    private List<ChunkCandidate> applyOverlap(List<ChunkCandidate> chunks, SplitterSettings s, ChunkMetadata base) {
        if (chunks.size() <= 1 || s.overlap() <= 0) return chunks;
        List<ChunkCandidate> res = new ArrayList<>(); res.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            ChunkCandidate prev = res.get(res.size()-1), cur = chunks.get(i);
            String prevC = prev.content();
            int ov = s.overlap(), start = Math.max(0, prevC.length() - ov);
            int b = findBoundary(prevC, start);
            res.add(buildChunk(cur.chunkIndex(), prevC.substring(b) + cur.content(), base));
        }
        return res;
    }

    private int findBoundary(String text, int from) {
        for (int i = Math.min(from + 80, text.length()-1); i >= Math.max(0, from-80); i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == ';' || c == '；') return i+1;
        }
        return from;
    }
}
