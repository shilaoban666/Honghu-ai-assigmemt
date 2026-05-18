package com.honghu.ut.test.ai.assigment.testdeepseekr1.listener;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.IngestionResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.ingest.DocumentIngestionService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.ingest.RagEventMessageParser;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * SQS 文档上传完成监听器 —— 简单 RAG 摄取链路的<strong>入口</strong>。
 *
 * <h3>整条 RAG 摄取链路全景</h3>
 * <pre>
 *   前端 PUT 文件到 S3
 *        ↓ （S3 通知）
 *   SQS（honghu-ai-document-upload-received）
 *        ↓ @SqsListener
 *   {@link RagFilesUploadListener#onMessage}                        ← 你正在看的这一层
 *        ↓
 *   {@link DocumentIngestionService#ingestMessages}                  ← 解析 + 路由 handler
 *        ↓ （按 fileType 分发）
 *   {@code AbstractDocumentIngestionProcesser#handleFileMessage}     ← 主流水：校验/下载/抽取/分块/索引
 *        ↓ 每一步都通过 RagIngestionStateService 独立事务落库
 *   PostgreSQL: rag_ingestion_event + rag_document + rag_document_chunk
 *        ↑
 *   ChatService 检索时按 (userId, sessionId) 召回 chunk → 拼 prompt → 大模型
 * </pre>
 *
 * <h3>本类的职责边界</h3>
 * <ul>
 *     <li>仅做"消息→服务"的薄胶水，不做任何业务判断或状态写库。</li>
 *     <li>使用 {@link SqsListener} 把 ack/重试/可见性超时全部交给框架：
 *         <ul>
 *             <li>方法正常返回 → 框架认为消费成功，从 SQS 删消息</li>
 *             <li>方法抛异常 → 框架不 ack，SQS 在 visibility timeout 后重新投递</li>
 *         </ul>
 *     </li>
 *     <li>因此与 {@link IngestionResult#shouldDeleteMessage()} 的映射非常直接：
 *         {@code true} → 正常 return；{@code false} → 抛异常让框架重试。</li>
 * </ul>
 *
 * <h3>为什么 catch 里要 re-throw 而不是吞掉</h3>
 * <p>如果在这里把异常吞了，SQS 会以为消费成功，消息被删除，那么这一份失败/未完成的摄取
 * 就<strong>永远不会重试</strong>，前端也无从感知。所以这里只是包装异常类型 + 加日志，
 * 关键信号必须留给框架决定是否重投。</p>
 *
 * <h3>Bean 是否被创建</h3>
 * <p>由 {@link ConditionalOnProperty} 控制：仅当 {@code app.rag.listener.enabled=true} 时才注册，
 * 单元测试和本地调试可以一键关掉，避免误连云端 SQS。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.rag.listener", name = "enabled", havingValue = "true")
public class RagDocumentUploadedListener {

    private final RagProperties ragProperties;
    private final DocumentIngestionService documentIngestionService;

    /**
     * SQS 消息消费入口。
     *
     * <p>这里直接接收原始消息体字符串，而不是绑定到固定 Java 对象，
     * 因为 S3 事件投递到 SQS 的消息结构有两种常见形式：</p>
     * <ol>
     *     <li>S3 直接发到 SQS：消息体本身包含 {@code Records}</li>
     *     <li>SNS 再转发到 SQS：消息体外层多一层 SNS 包装，真正事件在 {@code Message} 字段里</li>
     * </ol>
     *
     * <p>所以这里先把原始消息体交给摄取服务，由
     * {@link RagEventMessageParser}
     * 内部统一解析再逐条处理。</p>
     *
     * @param messageBody SQS 原始消息体（可能是 S3 事件 JSON 也可能是 SNS 包裹）
     * @param headers     Spring Cloud AWS 转出来的消息头；不同版本/适配器暴露的 header 名可能略有差异
     */
    @SqsListener("${app.rag.listener.queue-name}")
    public void onMessage(String messageBody, @Headers Map<String, Object> headers) {
        String messageId = resolveMessageId(headers);
        long startedAt = System.currentTimeMillis();
        int payloadLength = messageBody == null ? 0 : messageBody.length();
        log.info("收到 SQS 文档消息: queueName={}, messageId={}, payloadLength={}",
                ragProperties.getListener().getQueueName(), messageId, payloadLength);
        try {
            documentIngestionService.ingestMessages(messageBody, messageId);
            log.info("SQS 文档消息处理完成: queueName={}, messageId={}, durationMs={}",
                    ragProperties.getListener().getQueueName(), messageId, System.currentTimeMillis() - startedAt);
        } catch (RuntimeException ex) {
            // 业务/IO 类异常：保留原异常类型抛出，让 @SqsListener 走重试路径。
            log.error("处理 SQS 文档消息失败，将由 SQS 后续重试: queueName={}, messageId={}, payloadLength={}, durationMs={}",
                    ragProperties.getListener().getQueueName(), messageId, payloadLength, System.currentTimeMillis() - startedAt, ex);
            throw ex;
        } catch (Exception ex) {
            // 受检异常（比如 IOException）：包成 RuntimeException 才能让框架感知到失败。
            log.error("解析或处理 SQS 文档消息失败，将由 SQS 后续重试: queueName={}, messageId={}, payloadLength={}, durationMs={}",
                    ragProperties.getListener().getQueueName(), messageId, payloadLength, System.currentTimeMillis() - startedAt, ex);
            throw new IllegalStateException("SQS 文档消息处理失败", ex);
        }
    }

    private String resolveMessageId(Map<String, Object> headers) {
        if (headers != null) {
            Object rawMessageId = headers.get("MessageId");
            if (rawMessageId == null) {
                rawMessageId = headers.get("messageId");
            }
            if (rawMessageId == null) {
                rawMessageId = headers.get(MessageHeaders.ID);
            }
            if (rawMessageId != null) {
                return rawMessageId.toString();
            }
        }
        String fallback = "generated-" + UUID.randomUUID();
        log.warn("SQS 消息头中未找到可用 messageId，使用本地兜底 ID: fallbackMessageId={}", fallback);
        return fallback;
    }
}
