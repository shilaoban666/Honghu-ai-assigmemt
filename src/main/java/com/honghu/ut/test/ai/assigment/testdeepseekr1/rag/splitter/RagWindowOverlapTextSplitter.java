package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定窗口 + overlap 文本切分器。txt/json/xml/csv 默认策略。
 */
@Slf4j
@Component("ragWindowOverlapTextSplitter")
public class RagWindowOverlapTextSplitter extends AbstractRagTextSplitter {

    public RagWindowOverlapTextSplitter(RagProperties ragProperties) { super(ragProperties); }

    @Override
    protected List<ChunkCandidate> doChunk(String text, SplitterSettings settings, ChunkMetadata base) {
        int cs = settings.chunkSize(), ov = settings.overlap();
        int minLen = settings.minChunkLength(), maxC = settings.maxChunks();
        List<ChunkCandidate> chunks = new ArrayList<>();
        int index = 0, start = 0;
        while (start < text.length()) {
            if (chunks.size() >= maxC) break;
            int rawEnd = Math.min(start + cs, text.length());
            int adjustedEnd = adjustChunkEnd(text, start, rawEnd, minLen);
            String content = text.substring(start, adjustedEnd);
            if (content.trim().isEmpty() && adjustedEnd < text.length()) {
                start = Math.max(adjustedEnd - ov, start + 1);
                continue;
            }
            chunks.add(buildChunk(index++, content, base, start, adjustedEnd));
            if (adjustedEnd >= text.length()) break;
            start = Math.max(adjustedEnd - ov, start + 1);
        }
        if (log.isDebugEnabled() && !chunks.isEmpty()) {
            log.debug("RAG window split: chunks={}, firstChars={}, lastChars={}",
                    chunks.size(), chunks.get(0).charCount(), chunks.get(chunks.size()-1).charCount());
        }
        return chunks;
    }
}
