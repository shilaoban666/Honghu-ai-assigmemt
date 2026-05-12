package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagSentenceWindowTextSplitter 测试。
 */
class RagSentenceWindowTextSplitterTest {

    private RagProperties properties;
    private RagSentenceWindowTextSplitter splitter;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getIngestion().setChunkSize(120);
        properties.getIngestion().setChunkOverlap(20);
        properties.getIngestion().setMinChunkLength(20);
        properties.getIngestion().setMaxChunksPerDocument(20);
        splitter = new RagSentenceWindowTextSplitter(properties);
    }

    @Test
    void emptyStringShouldReturnEmpty() {
        assertTrue(splitter.chunk("").isEmpty());
    }

    @Test
    void shortTextShouldReturnSingleChunk() {
        List<ChunkCandidate> chunks = splitter.chunk("Hello world.");
        assertEquals(1, chunks.size());
    }

    @Test
    void chineseSentenceBoundaries() {
        String text = "这是第一句话。这是第二句话！这是第三句话？这是第四句。";
        List<ChunkCandidate> chunks = splitter.chunk(text);
        assertTrue(chunks.size() >= 1);
        for (ChunkCandidate c : chunks) {
            assertFalse(c.content().isBlank());
        }
    }

    @Test
    void englishSentenceBoundaries() {
        String text = "This is the first sentence. This is the second! Is this the third? Yes it is.";
        List<ChunkCandidate> chunks = splitter.chunk(text);
        assertTrue(chunks.size() >= 1);
    }

    @Test
    void mixedChineseEnglishBoundaries() {
        String text = "中文内容。English content. Another sentence！最后一句。";
        List<ChunkCandidate> chunks = splitter.chunk(text);
        // 混合文本也应该能切分
        assertTrue(chunks.size() >= 1);
    }

    @Test
    void veryLongSingleSentenceShouldFallbackToWindow() {
        StringBuilder sb = new StringBuilder();
        // 构造超长无句末标点的文本
        for (int i = 0; i < 200; i++) {
            sb.append("word").append(i).append(" ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "Very long sentence without boundaries should be split");
    }

    @Test
    void shouldNotExceedMaxChunks() {
        properties.getIngestion().setChunkSize(10);
        properties.getIngestion().setMaxChunksPerDocument(3);
        splitter = new RagSentenceWindowTextSplitter(properties);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("word").append(i).append(". ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() <= 3);
    }

    @Test
    void chunkIndexShouldBeConsecutive() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("Sentence ").append(i).append(". ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex());
        }
    }

    @Test
    void overlapShouldUseCompleteSentences() {
        properties.getIngestion().setChunkSize(80);
        properties.getIngestion().setChunkOverlap(40);
        splitter = new RagSentenceWindowTextSplitter(properties);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("Sentence number ").append(i).append(". ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        // 有 overlap 时仍应产生合理的 chunk
        assertTrue(chunks.size() >= 1);
        for (ChunkCandidate c : chunks) {
            assertFalse(c.content().isBlank());
        }
    }

    @Test
    void allChunksShouldHaveValidContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("A complete sentence number ").append(i).append(". ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        for (ChunkCandidate c : chunks) {
            assertFalse(c.content().isBlank());
            assertTrue(c.charCount() > 0);
            assertTrue(c.tokenEstimate() > 0);
        }
    }

    @Test
    void chineseTextWithClosingQuotes() {
        String text = "他说：这是测试。她说：好的。结束。";
        List<ChunkCandidate> chunks = splitter.chunk(text);
        assertTrue(chunks.size() >= 1);
        for (ChunkCandidate c : chunks) {
            assertFalse(c.content().isBlank());
        }
    }

    @Test
    void textWithNoSentenceEndShouldStillProduceChunks() {
        // 无句末标点的文本应该也能切分（通过 fallback window splitter）
        String text = "no punctuation just a very long stream of words ".repeat(50);
        List<ChunkCandidate> chunks = splitter.chunk(text);
        assertTrue(chunks.size() > 0);
    }
}
