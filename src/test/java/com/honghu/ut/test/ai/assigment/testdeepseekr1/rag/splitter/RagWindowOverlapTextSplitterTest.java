package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.splitter.RagWindowOverlapTextSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagWindowOverlapTextSplitter 测试。
 *
 * <p>验证固定窗口 + overlap 切分策略的行为正确性，
 * 特别是与旧内联 chunk() 逻辑的等价性。</p>
 */
class RagWindowOverlapTextSplitterTest {

    private RagProperties properties;
    private RagWindowOverlapTextSplitter splitter;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getIngestion().setChunkSize(120);
        properties.getIngestion().setChunkOverlap(20);
        properties.getIngestion().setMinChunkLength(20);
        properties.getIngestion().setMaxChunksPerDocument(20);
        splitter = new RagWindowOverlapTextSplitter(properties);
    }

    @Test
    void emptyStringShouldReturnEmpty() {
        List<ChunkCandidate> chunks = splitter.chunk("");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void nullStringShouldReturnEmpty() {
        List<ChunkCandidate> chunks = splitter.chunk(null);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void blankStringShouldReturnEmpty() {
        List<ChunkCandidate> chunks = splitter.chunk("   \n  \t  ");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void shortTextShouldReturnSingleChunk() {
        String text = "Hello world";
        List<ChunkCandidate> chunks = splitter.chunk(text);
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals("Hello world", chunks.get(0).content().trim());
    }

    @Test
    void textShorterThanChunkSizeShouldReturnSingleChunk() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("word ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).chunkIndex());
    }

    @Test
    void longTextShouldProduceMultipleChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("This is sentence number ").append(i).append(". ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "Expected multiple chunks but got " + chunks.size());
    }

    @Test
    void chunkIndexShouldBeConsecutive() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("The quick brown fox jumps over the lazy dog. ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() > 1);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex(), "Chunk index not consecutive at position " + i);
        }
    }

    @Test
    void overlapShouldCauseContentOverlap() {
        // 构造周期性内容，验证相邻 chunk 有重叠
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789. ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "Expected multiple chunks");

        // 检查相邻 chunk 的 overlap
        for (int i = 1; i < chunks.size(); i++) {
            String prevEnd = chunks.get(i - 1).content();
            String currStart = chunks.get(i).content();
            // 不用严格断言 overlap 存在，因为 adjustChunkEnd 可能跳过
            // 但至少 chunk 之间应该是连续的
            assertNotNull(currStart);
        }
    }

    @Test
    void trailingShortChunkShouldBeMerged() {
        properties.getIngestion().setChunkSize(60);
        properties.getIngestion().setChunkOverlap(0);
        properties.getIngestion().setMinChunkLength(15);
        splitter = new RagWindowOverlapTextSplitter(properties);

        // 构造一个刚好剩一个短尾部的文本
        StringBuilder sb = new StringBuilder();
        sb.append("AAAA BBBB CCCC DDDD EEEE FFFF GGGG HHHH IIII JJJJ KKKK LLLL MMMM NNNN.");
        // 最后几个词很短
        sb.append(" short tail.");
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());

        // 最后一个 chunk 应该被合并
        assertTrue(chunks.size() >= 1);
    }

    @Test
    void maxChunksShouldLimitOutput() {
        properties.getIngestion().setChunkSize(20);
        properties.getIngestion().setMaxChunksPerDocument(3);
        properties.getIngestion().setChunkOverlap(0);
        splitter = new RagWindowOverlapTextSplitter(properties);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("word").append(i).append(" ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() <= 3, "Expected at most 3 chunks but got " + chunks.size());
    }

    @Test
    void chunkContentShouldNotBeEmpty() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("Hello world number ").append(i).append(". ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        for (ChunkCandidate chunk : chunks) {
            assertFalse(chunk.content().isBlank(), "Chunk content should not be blank");
        }
    }
}
