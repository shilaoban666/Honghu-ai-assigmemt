package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落打包切分器。pdf/docx 默认策略。
 */
@Slf4j
@Component("ragParagraphPackingTextSplitter")
public class RagParagraphPackingTextSplitter extends AbstractRagTextSplitter {

    private final RagRecursiveStructureTextSplitter fallback;

    public RagParagraphPackingTextSplitter(RagProperties ragProperties) {
        super(ragProperties);
        this.fallback = new RagRecursiveStructureTextSplitter(ragProperties);
    }

    @Override
    protected List<ChunkCandidate> doChunk(String text, SplitterSettings s, ChunkMetadata base) {
        int cs = s.chunkSize(), ub = Math.max(cs, cs * 3 / 2);
        if (text.length() <= ub) return List.of(buildChunk(0, text, base));

        List<String> paragraphs = splitParagraphs(text);
        List<List<String>> groups = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int curLen = 0;

        for (String p : paragraphs) {
            if (p.length() > ub) {
                if (!cur.isEmpty()) { groups.add(new ArrayList<>(cur)); cur.clear(); curLen = 0; }
                List<ChunkCandidate> fc = fallback.doChunk(p, new SplitterSettings(cs, 0, 1, s.maxChunks()), base);
                for (ChunkCandidate c : fc) groups.add(List.of(c.content()));
                continue;
            }
            if (curLen + p.length() <= ub) { cur.add(p); curLen += p.length(); }
            else {
                if (!cur.isEmpty()) groups.add(new ArrayList<>(cur));
                cur = new ArrayList<>(); cur.add(p); curLen = p.length();
            }
            if (groups.size() >= s.maxChunks()) break;
        }
        if (!cur.isEmpty() && groups.size() < s.maxChunks()) groups.add(cur);

        List<ChunkCandidate> chunks = new ArrayList<>();
        int idx = 0;
        for (List<String> g : groups) chunks.add(buildChunk(idx++, String.join("\n", g), base));

        if (s.overlap() > 0 && chunks.size() > 1) chunks = applyParagraphOverlap(chunks, s, base);
        return chunks;
    }

    private List<String> splitParagraphs(String text) {
        List<String> ps = new ArrayList<>();
        for (String p : text.split("\\n\\s*\\n+")) { String t = p.trim(); if (!t.isEmpty()) ps.add(t); }
        return ps;
    }

    private List<ChunkCandidate> applyParagraphOverlap(List<ChunkCandidate> chunks, SplitterSettings s, ChunkMetadata base) {
        List<ChunkCandidate> res = new ArrayList<>(); res.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            ChunkCandidate prev = res.get(res.size()-1), cur = chunks.get(i);
            String[] pp = prev.content().split("\\n");
            if (pp.length > 0 && pp[pp.length-1].length() <= s.overlap()) {
                res.add(buildChunk(cur.chunkIndex(), pp[pp.length-1] + "\n" + cur.content(), base));
                continue;
            }
            int start = Math.max(0, prev.content().length() - s.overlap());
            int b = findBoundary(prev.content(), start);
            res.add(buildChunk(cur.chunkIndex(), prev.content().substring(b) + cur.content(), base));
        }
        return res;
    }

    private int findBoundary(String text, int from) {
        for (int i = Math.min(from+80, text.length()-1); i >= Math.max(0, from-80); i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') return i+1;
        }
        return from;
    }
}
