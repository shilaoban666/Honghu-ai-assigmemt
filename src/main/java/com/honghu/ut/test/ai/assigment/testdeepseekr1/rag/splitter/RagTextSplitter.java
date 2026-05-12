package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;

import java.util.List;

/**
 * RAG 文本切分策略接口。
 */
public interface RagTextSplitter {

    /** 无 metadata 版本（等价于 {@code chunk(text, ChunkMetadata.EMPTY)}）。 */
    List<ChunkCandidate> chunk(String text);

    /** 带基础 metadata 的切分。 */
    List<ChunkCandidate> chunk(String text, ChunkMetadata baseMetadata);
}
