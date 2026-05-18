package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagParagraphPackingTextSplitter 测试。
 */
class RagParagraphPackingTextSplitterTest {

    private RagProperties properties;
    private RagParagraphPackingTextSplitter splitter;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getIngestion().setChunkSize(120);
        properties.getIngestion().setChunkOverlap(20);
        properties.getIngestion().setMinChunkLength(20);
        properties.getIngestion().setMaxChunksPerDocument(20);
        splitter = new RagParagraphPackingTextSplitter(properties);
    }

    @Test
    void emptyStringShouldReturnEmpty() {
        assertTrue(splitter.chunk("").isEmpty());
    }

    @Test
    void shortTextShouldReturnSingleChunk() {
        List<ChunkCandidate> chunks = splitter.chunk("Hello world");
        assertEquals(1, chunks.size());
    }

    @Test
    void multipleParagraphsShouldBePackedAsWhole() {
        String text = """
                First paragraph with some content.
                                
                Second paragraph continues here.
                                
                Third paragraph is also present.""";

        List<ChunkCandidate> chunks = splitter.chunk(text);
        assertTrue(chunks.size() >= 1);
        for (ChunkCandidate c : chunks) {
            assertFalse(c.content().isBlank());
        }
    }

    @Test
    void singleVeryLongParagraphShouldFallback() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("This is a very long paragraph with no line breaks at all ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "Very long paragraph should be split into multiple chunks");
    }

    @Test
    void chunkLengthMayExceedChunkSize_byUpTo50Percent() {
        properties.getIngestion().setChunkSize(100);
        properties.getIngestion().setChunkOverlap(0);
        splitter = new RagParagraphPackingTextSplitter(properties);

        // 多段落文本，段落刚好接近 chunkSize
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("Paragraph ").append(i).append(" with some content.\n\n");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() >= 1);
        // upperBound = chunkSize * 1.5 = 150, 所以 chunk 长度 <= 150
        for (ChunkCandidate c : chunks) {
            assertTrue(c.charCount() <= 160, "Expected charCount <= 160 but got " + c.charCount());
        }
    }

    @Test
    void trailingShortParagraphShouldBeMerged() {
        properties.getIngestion().setChunkSize(200);
        properties.getIngestion().setMinChunkLength(30);
        splitter = new RagParagraphPackingTextSplitter(properties);

        // 构造多个段落，最后一个是短段落
        String text = """
                Long paragraph one with substantial content that takes some space.
                                
                Another long paragraph with substantial content that takes more space.
                                
                Short.""";
        List<ChunkCandidate> chunks = splitter.chunk(text);
        // 短尾段应该和前面的合并（由基类的 mergeTrailingShortChunk 处理）
        for (ChunkCandidate c : chunks) {
            assertTrue(c.content().length() >= 30 || chunks.size() == 1,
                    "Short chunk should be merged, got length " + c.content().length());
        }
    }

    @Test
    void shouldNotExceedMaxChunks() {
        properties.getIngestion().setChunkSize(50);
        properties.getIngestion().setMaxChunksPerDocument(3);
        splitter = new RagParagraphPackingTextSplitter(properties);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("Paragraph number ").append(i).append(".\n\n");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() <= 3);
    }

    @Test
    void chunkIndexShouldBeConsecutive() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("Paragraph ").append(i).append(" with some meaningful text content.\n\n");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex());
        }
    }

    @Test
    void allChunksShouldHaveValidContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("This is paragraph number ").append(i).append(".\n\n");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        for (ChunkCandidate c : chunks) {
            assertFalse(c.content().isBlank());
            assertTrue(c.charCount() > 0);
            assertTrue(c.tokenEstimate() > 0);
        }
    }
}
