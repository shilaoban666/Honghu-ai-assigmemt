package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.ingest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.S3UploadReceivedMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.UploadMetadata;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.processer.AbstractDocumentIngestionProcesser;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
/**
 * S3 事件消息解析器 —— RAG 摄取链路里"协议适配"的一层。
 *
 * <h3>能吃下的三种上游结构</h3>
 * <ol>
 *     <li>S3 → SQS 直投：消息体本身就有 {@code Records} 数组</li>
 *     <li>S3 → SNS → SQS：消息体外层多一层 SNS 包装，真实 S3 事件放在 {@code Message} 字段（字符串）里，
 *         需要 {@link #unwrapPayload} 解一次包再读 {@code Records}</li>
 *     <li>应用侧上传完成通知：消息体直接给出 {@code bucket + s3Key + fileId + version} 等元数据</li>
 * </ol>
 *
 * <h3>每条 record 的标准化输出</h3>
 * <p>统一封装为 {@link S3UploadReceivedMessage}，下游不需要再关心 JSON path：</p>
 * <ul>
 *     <li>bucket / key / eventName / etag / sequencer / size 直接抽出</li>
 *     <li>key 做 URL 解码（S3 事件里空格会被编码成 {@code %20}）</li>
 *     <li>deduplicationKey 是<strong>消息级幂等键</strong>：优先用 sequencer（S3 对同一 object 事件序号），
 *         否则退化为 bucket|key|etag|eventName</li>
 * </ul>
 *
 * <p><strong>特别区分：</strong></p>
 * <ul>
 *     <li>{@code objectEtag} 更偏向“对象版本标识”，主要在
 *         {@link AbstractDocumentIngestionProcesser}
 *         里做“同版本对象已索引则跳过”判断</li>
 *     <li>{@code deduplicationKey} 更偏向“消息/事件幂等标识”，主要在
 *         {@link RagIngestionStateService#reserveEvent}
 *         里预占处理权，并通过 {@code rag_ingestion_event.deduplication_key} 唯一索引兜底并发重复消费</li>
 * </ul>
 *
 * <h3>本类<strong>不</strong>负责的事情</h3>
 * <ul>
 *     <li>不做 bucket 白名单（在 {@code AbstractDocumentIngestionProcesser.validateIncomingMessage} 里做）</li>
 *     <li>不做去重（落库时由 {@link RagIngestionStateService#reserveEvent} 用唯一索引兜底）</li>
 *     <li>不识别 EventBridge 嵌套结构（已知后续增强项）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEventMessageParser {
    /** 应用侧上传完成通知统一映射成的伪事件名，便于下游区分来源。 */
    private static final String METADATA_EVENT_NAME = "ObjectCreated:UploadNotification";
    /** 当上传元数据里没有真实 eTag 时，给 objectEtag 构造一个可识别的 synthetic 前缀。 */
    private static final String METADATA_ETAG_PREFIX = "metadata-version:";

    private final ObjectMapper objectMapper;

    /**
     * 解析 SQS 原始消息体，输出标准化的上传事件对象列表。
     *
     * <p>为什么返回 List？因为：</p>
     * <ul>
     *     <li>S3 事件规范里一个消息体可能包含多个 {@code Records}</li>
     *     <li>而应用侧直推的上传元数据消息虽然通常只有一条文件记录，但也复用同一个返回模型</li>
     * </ul>
     *
     * @param rawMessageBody 原始 SQS 消息体
     * @return 标准化后的上传对象列表；无法解析时返回空列表
     */
    public List<S3UploadReceivedMessage> parse(String rawMessageBody) throws IOException {
        // 空消息体没有任何解析价值，直接返回空列表。
        if (!StringUtils.hasText(rawMessageBody)) {
            return List.of();
        }

        // 第一步：先把最外层字符串解析成 JSON。
        JsonNode root = objectMapper.readTree(rawMessageBody);
        // 第二步：兼容 SNS 包裹场景；如果外层有 Message 字段，则继续向里解包。
        JsonNode eventPayload = unwrapPayload(root);
        JsonNode recordsNode = eventPayload.path("Records");
        if (recordsNode.isArray()) {
            // 这是标准 S3 事件路径：从 Records 数组逐条抽取 record。
            return parseS3Records(recordsNode);
        }

        // 如果没有 Records，就尝试把它当成应用侧上传元数据通知来解析。
        S3UploadReceivedMessage metadataMessage = parseUploadMetadataMessage(eventPayload);
        return metadataMessage == null ? List.of() : List.of(metadataMessage);
    }

    private List<S3UploadReceivedMessage> parseS3Records(JsonNode recordsNode) {
        // 用可变列表收集每一条成功解析出来的标准化消息。
        List<S3UploadReceivedMessage> messages = new ArrayList<>();
        for (JsonNode recordNode : recordsNode) {
            // 从标准 S3 event 路径中抽 bucket 与 object key。
            String bucketName = recordNode.path("s3").path("bucket").path("name").asText(null);
            String rawObjectKey = recordNode.path("s3").path("object").path("key").asText(null);
            if (!StringUtils.hasText(bucketName) || !StringUtils.hasText(rawObjectKey)) {
                // bucket 或 key 缺失时，这条 record 对摄取服务没有价值，直接跳过。
                continue;
            }

            // S3 event 里的 key 常常是 URL 编码形式，例如空格会变成 %20，所以这里先解码。
            String objectKey = decodeObjectKey(rawObjectKey, true);
            String eventName = recordNode.path("eventName").asText(null);

            // eTag 在不同来源中大小写可能不一致，这里做双写兼容。
            String eTag = recordNode.path("s3").path("object").path("eTag").asText(null);
            if (!StringUtils.hasText(eTag)) {
                // 不同来源大小写可能不一致，这里做一下兼容。
                eTag = recordNode.path("s3").path("object").path("etag").asText(null);
            }
            String sequencer = recordNode.path("s3").path("object").path("sequencer").asText(null);
            Long objectSize = recordNode.path("s3").path("object").path("size").isMissingNode()
                    ? null
                    : recordNode.path("s3").path("object").path("size").asLong();

            // 把原始 S3 record 统一映射成项目内部标准的上传事件对象。
            messages.add(new S3UploadReceivedMessage(
                    bucketName,
                    objectKey,
                    eTag,
                    objectSize,
                    eventName,
                    sequencer,
                    buildDeduplicationKey(bucketName, objectKey, eTag, eventName, sequencer)
            ));
        }
        return messages;
    }

    /**
     * 解析应用侧直推的上传完成元数据消息。
     *
     * <p>该结构通常长这样：bucket + s3Key + fileId + version + uploadedAt。
     * 虽然它不是原生 S3 事件，但下游 RAG 处理链只依赖 bucket/objectKey/fileType/fileId 等标准字段，
     * 因此这里把它适配成一条 {@link S3UploadReceivedMessage}。</p>
     *
     * <p><strong>额外注意：</strong></p>
     * <ul>
     *     <li>如果消息里已经携带真实 {@code eTag}/{@code sequencer}，这里优先直接使用</li>
     *     <li>如果没有，再退化到 synthetic version token，避免后续 RAG 早退逻辑把所有 null eTag
     *         误判成“同版本已处理”</li>
     *     <li>这里仍然单独生成 {@code deduplicationKey}，因为它服务的是“消息幂等”，不是“对象版本比较”；
     *         下游由 {@link RagIngestionStateService#reserveEvent}
     *         以及 {@link DocumentIngestionService}
     *         的“无 handler 写 SKIPPED 状态”路径等逻辑消费</li>
     * </ul>
     */
    private S3UploadReceivedMessage parseUploadMetadataMessage(JsonNode payload) {
        // 应用侧元数据消息里 bucket / key 是最基本的两项，没有它们就无法继续下游处理。
        String bucketName = payload.path("bucket").asText(null);
        String rawObjectKey = firstText(payload, "s3Key", "objectKey", "key");
        if (!StringUtils.hasText(bucketName) || !StringUtils.hasText(rawObjectKey)) {
            return null;
        }

        // 这里 decode 时 plusAsSpace=false，因为应用自己发的元数据不一定遵循 S3 事件同样的编码规则。
        String objectKey = decodeObjectKey(rawObjectKey, false);
        Long objectSize = readMetadataObjectSize(payload);
        String versionToken = buildMetadataVersionToken(payload, objectKey);
        String objectEtag = firstText(payload, "eTag", "etag");
        String sequencer = firstText(payload, "sequencer");
        String effectiveEtag = StringUtils.hasText(objectEtag)
                ? objectEtag
                : METADATA_ETAG_PREFIX + versionToken;
        // deduplicationKey 不是简单复制 eTag：
        // 它是“这条上传事件在 RAG 链路里的幂等主键”。
        // 主要用途：
        // 1) RagIngestionStateService.reserveEvent(...) 按它查询/预占事件处理权；
        // 2) rag_ingestion_event 表对 deduplication_key 建唯一索引，兜底并发重复消费；
        // 3) DocumentIngestionService.recordUnsupportedFile(...) 在无 handler 场景下也用它保证幂等更新。
        // 因此这里优先选更像“事件标识”的 sequencer；若无 sequencer，再退到 eTag / synthetic version token。
        String deduplicationKey = buildMetadataDeduplicationKey(bucketName, objectKey, effectiveEtag, sequencer, versionToken);

        // 补齐 UploadMetadata，尽量把应用侧原始字段保留下来供后续链路和排查使用。
        return new S3UploadReceivedMessage(
                bucketName,
                objectKey,
                effectiveEtag,
                objectSize,
                METADATA_EVENT_NAME,
                sequencer,
                deduplicationKey,
                buildUploadMetadata(payload, bucketName, objectKey, effectiveEtag, sequencer, objectSize)
        );
    }

    /**
     * 解包 SNS 包裹的真实消息。
     *
     * <p>如果消息体本身已经是 S3 事件，则直接返回 root；
     * 如果是 SNS 转发，则真实事件在 {@code Message} 字段中。</p>
     */
    private JsonNode unwrapPayload(JsonNode root) throws IOException {
        JsonNode messageNode = root.path("Message");
        if (messageNode.isTextual() && StringUtils.hasText(messageNode.asText())) {
            // SNS -> SQS 场景里，真实消息被包在 Message 字符串里，需要再 decode 一层 JSON。
            return objectMapper.readTree(messageNode.asText());
        }
        // 如果外层本来就是业务事件本体，则直接返回。
        return root;
    }

    /**
     * 对 URL 编码的 object key 做解码。
     */
    private String decodeObjectKey(String rawObjectKey, boolean plusAsSpace) {
        String trimmed = rawObjectKey == null ? null : rawObjectKey.trim();
        if (!StringUtils.hasText(trimmed)) {
            return trimmed;
        }
        // 如果压根没有编码痕迹，就直接返回原值，避免无意义 decode。
        if (!trimmed.contains("%") && (!plusAsSpace || !trimmed.contains("+"))) {
            return trimmed;
        }
        // 标准 S3 event 中 '+' 常被当成空格，而应用侧元数据里 '+' 可能是字面值，因此用参数控制行为。
        String encoded = plusAsSpace ? trimmed : trimmed.replace("+", "%2B");
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private String firstText(JsonNode payload, String... fieldNames) {
        // 依次尝试多个候选字段名，兼容不同上游消息结构的命名差异。
        for (String fieldName : fieldNames) {
            JsonNode node = payload.path(fieldName);
            if (node.isTextual() && StringUtils.hasText(node.asText())) {
                return node.asText();
            }
        }
        return null;
    }

    private Long readMetadataObjectSize(JsonNode payload) {
        // 优先取 fileSize，再退到 size，兼容不同调用方的字段名习惯。
        return parseLongField("fileSize", payload.path("fileSize"),
                parseLongField("size", payload.path("size"), null));
    }

    private Long parseLongField(String fieldName, JsonNode node, Long fallbackValue) {
        // 数字节点直接读取。
        if (node.isNumber()) {
            return node.asLong();
        }
        // 字符串节点则尝试 trim 后转 long；失败时退回 fallback。
        if (node.isTextual() && StringUtils.hasText(node.asText())) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ex) {
                log.debug("忽略无法解析为 long 的字段: fieldName={}, value={}", fieldName, node.asText());
            }
        }
        return fallbackValue;
    }

    private String buildMetadataVersionToken(JsonNode payload, String objectKey) {
        // version token 的目标是尽量稳定描述“这一次上传元数据代表的对象版本”。
        List<String> parts = new ArrayList<>();
        appendTokenPart(parts, "fileId", firstText(payload, "fileId"));
        JsonNode versionNode = payload.path("version");
        if (!versionNode.isMissingNode() && !versionNode.isNull()) {
            appendTokenPart(parts, "version", versionNode.asText());
        }
        appendTokenPart(parts, "versionGroupId", firstText(payload, "versionGroupId"));
        appendTokenPart(parts, "uploadedAt", firstText(payload, "uploadedAt"));
        if (parts.isEmpty()) {
            // 如果一个版本字段都没有，就退化到 objectKey，至少保证 token 非空。
            appendTokenPart(parts, "objectKey", objectKey);
        }
        return String.join(";", parts);
    }

    private UploadMetadata buildUploadMetadata(JsonNode payload,
                                               String bucketName,
                                               String objectKey,
                                               String objectEtag,
                                               String sequencer,
                                               Long objectSize) {
        // 这里集中把应用侧消息中的可用字段打包成 UploadMetadata，
        // 供下游日志、诊断和可能的后续扩展使用。
        Integer version = readMetadataVersion(payload);
        return new UploadMetadata(
                firstText(payload, "fileId"),
                firstText(payload, "userId"),
                firstText(payload, "username"),
                firstText(payload, "sessionId"),
                firstText(payload, "originalFilename"),
                objectKey,
                bucketName,
                objectEtag,
                sequencer,
                firstText(payload, "fileType"),
                firstText(payload, "contentType"),
                objectSize,
                firstText(payload, "uploadedAt"),
                firstText(payload, "versionGroupId"),
                version,
                payload.toString()
        );
    }

    private Integer readMetadataVersion(JsonNode payload) {
        JsonNode versionNode = payload.path("version");
        // 数字类型直接转 int。
        if (versionNode.isInt() || versionNode.isIntegralNumber()) {
            return versionNode.asInt();
        }
        // 文本类型则尝试解析；失败后静默忽略并返回 null。
        if (versionNode.isTextual() && StringUtils.hasText(versionNode.asText())) {
            try {
                return Integer.parseInt(versionNode.asText().trim());
            } catch (NumberFormatException ex) {
                log.debug("忽略无法解析为 int 的 version 字段: value={}", versionNode.asText());
            }
        }
        return null;
    }

    /**
     * 为“应用侧上传完成通知”生成消息级幂等键。
     *
     * <p>这个键的消费位置不在 parser 本身，而在后续 RAG 消费链路：</p>
     * <ol>
     *     <li>{@link RagIngestionStateService#reserveEvent}
     *         用它查已有事件、判断是否已处理/处理中，并决定本次是否能抢到处理权</li>
     *     <li>{@code rag_ingestion_event.deduplication_key} 上的唯一索引用它在数据库层兜底并发重复消费</li>
     *     <li>{@link DocumentIngestionService}
     *         在“无可用 handler、仅写 SKIPPED 状态”路径下也按它做幂等更新</li>
     * </ol>
     *
     * <p>规则说明：</p>
     * <ul>
     *     <li>优先用 {@code sequencer}：它最接近“同一对象上的同一次事件标识”</li>
     *     <li>没有 sequencer 但有真实 eTag 时，退化到 bucket|key|etag|eventName</li>
     *     <li>连真实 eTag 都没有时，最后退化到 synthetic version token，保证旧消息仍然可幂等消费</li>
     * </ul>
     */
    private String buildMetadataDeduplicationKey(String bucketName,
                                                 String objectKey,
                                                 String objectEtag,
                                                 String sequencer,
                                                 String versionToken) {
        if (StringUtils.hasText(sequencer)) {
            return buildDeduplicationKey(bucketName, objectKey, objectEtag, METADATA_EVENT_NAME, sequencer);
        }
        if (StringUtils.hasText(objectEtag) && !objectEtag.startsWith(METADATA_ETAG_PREFIX)) {
            return buildDeduplicationKey(bucketName, objectKey, objectEtag, METADATA_EVENT_NAME, null);
        }
        return bucketName + "|" + objectKey + "|metadata|" + versionToken;
    }

    private void appendTokenPart(List<String> parts, String name, String value) {
        // 只有值非空时才加入 token，避免产生一堆 name= 的空片段。
        if (StringUtils.hasText(value)) {
            parts.add(name + "=" + value.trim());
        }
    }

    /**
     * 构造摄取幂等键。
     *
     * <p>注意这里构造的是<strong>消息级幂等键</strong>，不是对象版本号。</p>
     *
     * <p>它的主要消费方是：</p>
     * <ul>
     *     <li>{@link RagIngestionStateService#reserveEvent}</li>
     *     <li>{@link DocumentIngestionService} 的无 handler 幂等写入路径</li>
     *     <li>{@code rag_ingestion_event} 表上的唯一键约束</li>
     * </ul>
     *
     * <p>优先使用 sequencer，因为它更适合作为 S3 对象事件的“同一事件标识”；
     * 如果没有 sequencer，再退化到 bucket + key + eTag + eventName。</p>
     */
    private String buildDeduplicationKey(String bucketName, String objectKey, String eTag, String eventName, String sequencer) {
        if (StringUtils.hasText(sequencer)) {
            // sequencer 存在时优先用它，因为它最接近“同一对象上的同一次事件标识”。
            return bucketName + "|" + objectKey + "|" + sequencer;
        }
        // 没有 sequencer 时，再退化到 bucket + key + eTag + eventName 组合键。
        return bucketName + "|" + objectKey + "|" + (eTag == null ? "" : eTag) + "|" + (eventName == null ? "" : eventName);
    }
}
