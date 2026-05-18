package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.pipeline;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.retriever.RagRetrievalService.RagSnippet;
import lombok.Builder;
import lombok.Value;
import java.util.List;

/**
 * RAG Pipeline 的统一输出。
 *
 * <p>包含格式化后的上下文文本块、结构化片段列表和执行指标。
 * ChatService 直接使用 {@code contextBlock} 注入 SystemMessage，
 * 不需要再关心内部检索细节。</p>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>说明</th></tr>
 *   <tr><td>{@code contextBlock}</td><td>可直接注入 LLM SystemMessage 的 RAG 上下文块（已格式化，含安全提示和边界标记）</td></tr>
 *   <tr><td>{@code snippets}</td><td>最终注入的片段列表（已去重、排序、裁剪）</td></tr>
 *   <tr><td>{@code metrics}</td><td>Pipeline 执行指标，用于观测和排查</td></tr>
 *   <tr><td>{@code fallbackTriggered}</td><td>是否触发了作用域 fallback</td></tr>
 * </table>
 *
 * @see RagRequest
 * @see RagPipelineMetrics
 */
@Value
@Builder
public class RagResult {

    /**
     * 可直接注入 LLM SystemMessage 的 RAG 上下文块。
     *
     * <p>这是给聊天模型直接消费的最终文本结果，已经完成了：
     * 候选筛选、排序、topK 截断、资料边界包装、安全提示拼接等工作。</p>
     */
    String contextBlock;

    /**
     * 最终注入的片段列表（已去重、排序、裁剪）。
     *
     * <p>这个字段更适合程序消费，例如前端展示“引用来源”、调试界面展示命中文档、
     * 或测试里校验“这次到底召回了哪几个 chunk”。</p>
     */
    List<RagSnippet> snippets;

    /**
     * Pipeline 执行指标。
     *
     * <p>它记录本次检索过程中各阶段的关键数量和状态，便于回答“为什么这次上下文很少”
     * 或“为什么本轮触发了 fallback”。</p>
     */
    RagPipelineMetrics metrics;

    /**
     * 是否触发了作用域 fallback。
     *
     * <p>true 表示主作用域结果不够理想，系统曾扩大检索范围补过候选；
     * false 则表示始终停留在主作用域，或者虽然达到阈值但没有可继续扩大的下一级作用域。</p>
     */
    boolean fallbackTriggered;

    /**
     * 构造空结果。
     *
     * @return contextBlock="" / snippets=[] / fallbackTriggered=false 的 RagResult
     */
    public static RagResult empty() {
        // 空结果是 Pipeline 最重要的容错返回值：
        // 它明确表达“本次没有可用 RAG 上下文”，但不会让聊天主链路因为异常而中断。
        return RagResult.builder()
                .contextBlock("").snippets(List.of())
                // metrics 仍然给一个空对象，而不是 null，方便调用方无脑读取字段或继续扩展日志。
                .metrics(RagPipelineMetrics.builder().build())
                .fallbackTriggered(false).build();
    }
}
