package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocumentChunk;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.embdding.RagVectorIndexingService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 为历史已 INDEXED 文档补写 Milvus 向量。
 *
 * <p>这个服务解决的是“新代码已支持向量检索，但历史文档只有 PostgreSQL chunk、没有 Milvus 向量”
 * 的迁移问题。它不会碰上传/下载/登记主链路，只是把已经成功落库的旧数据补齐到向量库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagVectorBackfillService {

    /** 单次扫描 chunk 游标分页的批量大小。 */
    private static final int BATCH_SIZE = 64;

    private final RagDocumentChunkRepository chunkRepository;
    private final RagVectorIndexingService vectorIndexingService;

    /**
     * 全量回填历史向量。
     *
     * <p>实现上采用“按 chunkId 游标分页 + 每次按 documentId 聚合重建”的方式：</p>
     * <ul>
     *     <li>不用 OFFSET，避免大表越翻越慢</li>
     *     <li>以 document 为单位调用 replaceVectorIndex，保证同一篇文档的向量是一组一致数据</li>
     *     <li>失败按文档隔离，一篇失败不影响其他文档继续回填</li>
     * </ul>
     */
    public BackfillReport backfillAll() {
        // lastId 是 chunk 表的游标起点；每处理完一批就向后推进，避免使用 OFFSET 分页。
        long lastId = 0;
        // 这些计数器用于最后输出回填任务的整体执行报告。
        long totalDocs = 0;
        long totalChunks = 0;
        long failures = 0;

        // 由于我们是“按 chunk 游标分页”扫描，分页边界可能把同一 document 的不同 chunk 拆到不同批次。
        // processedDocumentIds 用来确保每篇文档只做一次完整重建，避免重复 delete/add。
        Set<Long> processedDocumentIds = new LinkedHashSet<>();

        while (true) {
            // 先按 chunkId 做“向后翻页”扫描，只捞已经 INDEXED 的历史 chunk。
            List<RagDocumentChunk> batch = chunkRepository.findBatchAfterId(
                    lastId,
                    RagDocument.Status.INDEXED,
                    PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                // 没有更多 chunk 了，说明全量扫描结束。
                break;
            }

            // 本批次中先提取出还没处理过的 documentId，后续按“文档”为单位做完整回填。
            Set<Long> documentIdsInBatch = new LinkedHashSet<>();
            for (RagDocumentChunk chunk : batch) {
                Long documentId = chunk.getDocument().getDocumentId();
                if (!processedDocumentIds.contains(documentId)) {
                    documentIdsInBatch.add(documentId);
                }
            }

            for (Long documentId : documentIdsInBatch) {
                try {
                    // 再次按 document 全量加载，是为了保证回填使用的是该文档当前的完整 chunk 集合，
                    // 而不是当前分页窗口里碰巧扫到的那几条 chunk 子集。
                    List<RagDocumentChunk> persistedChunks = chunkRepository.findByDocumentIdWithDocument(documentId);
                    if (persistedChunks.isEmpty()) {
                        // 极端情况下，如果文档 chunk 已被其他线程清空，这篇文档就直接跳过。
                        continue;
                    }

                    // 按文档调用 replaceVectorIndex，保证一篇文档的全部 chunk 在向量库中保持一致替换。
                    vectorIndexingService.replaceVectorIndex(
                            documentId,
                            persistedChunks,
                            persistedChunks.get(0).getDocument().getChunkCount());

                    // 只有真正写入成功后，才把这篇文档记为“已回填完成”。
                    processedDocumentIds.add(documentId);
                    totalDocs++;
                    totalChunks += persistedChunks.size();
                } catch (Exception ex) {
                    // 失败按文档计数，不中断整批；这类后台修复任务更重要的是“持续推进总体进度”，
                    // 而不是因为一篇脏文档让全量回填直接中止。
                    failures++;
                    log.error("回填失败: documentId={}", documentId, ex);
                }
            }

            // 把游标推进到当前批次最后一条 chunk，为下一轮扫描做准备。
            lastId = batch.get(batch.size() - 1).getChunkId();
            try {
                // 简单限流：DashScope embedding 和 Milvus 写入都属于外部资源，
                // 批次间短暂 sleep 能降低突发压力，换取更平稳的回填成功率。
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("RAG 向量回填完成: docs={}, chunks={}, failures={}", totalDocs, totalChunks, failures);
        return new BackfillReport(totalDocs, totalChunks, failures);
    }

    /**
     * 回填执行结果汇总。
     *
     * <p>documents 表示成功重建了多少篇文档的向量，
     * chunks 表示一共写入了多少个 chunk 向量，
     * failures 表示有多少篇文档在回填时失败。</p>
     */
    public record BackfillReport(long documents, long chunks, long failures) {
    }
}


