package com.honghu.ai.assigment.rag.splitter;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.dto.record.ChunkCandidate;
import com.honghu.ai.assigment.rag.index.splitter.RagRecursiveStructureTextSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagRecursiveStructureTextSplitter 测试。
 */
class RagRecursiveStructureTextSplitterTest {

    private RagProperties properties;
    private RagRecursiveStructureTextSplitter splitter;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getIngestion().setChunkSize(120);
        properties.getIngestion().setChunkOverlap(20);
        properties.getIngestion().setMinChunkLength(20);
        properties.getIngestion().setMaxChunksPerDocument(20);
        splitter = new RagRecursiveStructureTextSplitter(properties);
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
    void multiParagraphTextShouldPreferParagraphBoundaries() {
        String text = """
                Paragraph one with some content.
                                
                Paragraph two continues here.
                                
                Paragraph three is the last one.""";

        List<ChunkCandidate> chunks = splitter.chunk(text);
        assertTrue(chunks.size() >= 1);
        // chunkIndex must be consecutive from 0
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex());
        }
    }

    @Test
    void veryLongParagraphShouldFallbackToWindowSplitting() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("This is a very long string that has no paragraph breaks and continues indefinitely ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "Long paragraph should be split into multiple chunks");
    }

    @Test
    void shouldNotExceedMaxChunks() {
        properties.getIngestion().setChunkSize(10);
        properties.getIngestion().setMaxChunksPerDocument(3);
        splitter = new RagRecursiveStructureTextSplitter(properties);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("word").append(i).append(". ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        assertTrue(chunks.size() <= 3);
    }

    @Test
    void chunkIndexShouldBeConsecutive() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("Sentence number ").append(i).append(" ends here.\n\n");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex());
        }
    }

    @Test
    void allChunksShouldHaveContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("The quick brown fox jumps over the lazy dog. ");
        }
        List<ChunkCandidate> chunks = splitter.chunk(sb.toString());
        for (ChunkCandidate c : chunks) {
            assertFalse(c.content().isBlank());
            assertTrue(c.charCount() > 0);
            assertTrue(c.tokenEstimate() > 0);
        }
    }
}
