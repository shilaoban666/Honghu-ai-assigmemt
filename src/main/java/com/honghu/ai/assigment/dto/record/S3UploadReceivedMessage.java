package com.honghu.ai.assigment.dto.record;
import org.springframework.util.StringUtils;
import java.util.List;
/**
 * 从 SQS / S3 事件中抽取出的标准化上传对象消息。
 *
 * <p>这样上层监听器和摄取服务就不需要直接关心 S3 原始事件 JSON 的复杂结构。</p>
 *
 * <p>这个 record 的定位可以理解为“消息领域对象”：</p>
 * <ul>
 *     <li>上游：来自 SQS 的原始 JSON</li>
 *     <li>中间：由 {@code S3EventMessageParser} 解析为本对象</li>
 *     <li>下游：由文档摄取服务直接消费</li>
 * </ul>
 *
 * <p>这样做的核心好处是：<strong>解析逻辑和业务逻辑解耦</strong>。后续如果 S3 事件格式变化，
 * 只需要改解析器，不需要让摄取服务到处写 JSON path。</p>
 */
public record S3UploadReceivedMessage(String bucketName, String objectKey, String objectEtag,
                                      Long objectSize, String eventName, String sequencer, String deduplicationKey,
                                      UploadMetadata uploadMetadata) {

    /**
     * 兼容历史调用点：保留原 7 参构造方式，新增的 uploadMetadata 默认留空。
     */
    public S3UploadReceivedMessage(String bucketName,
                                   String objectKey,
                                   String objectEtag,
                                   Long objectSize,
                                   String eventName,
                                   String sequencer,
                                   String deduplicationKey) {
        this(bucketName, objectKey, objectEtag, objectSize, eventName, sequencer, deduplicationKey, null);
    }

    /**
     * 仅基于 bucket + objectKey 构造一个轻量解析对象。
     *
     * <p>这个工厂主要给“前端上传完成后手动登记 DB 状态”的场景使用：
     * 这时还没有完整的 S3 事件消息，但我们依然希望复用同一套 object key 解析逻辑，
     * 从路径中提取 ownerFolder / sessionId / fileType / fileId / fileName。</p>
     */
    public static S3UploadReceivedMessage fromObjectKey(String bucketName, String objectKey) {
        return new S3UploadReceivedMessage(bucketName, objectKey, null, null, null, null, bucketName + "|" + objectKey, null);
    }


    /**
     * 把 S3 object key 按“/”拆成路径段。
     *
     * <p>例如：</p>
     * <pre>
     * admin/session-1/pdf/file-123/report.pdf
     * </pre>
     *
     * <p>会拆成：</p>
     * <pre>
     * [admin, session-1, pdf, file-123, report.pdf]
     * </pre>
     */
    public List<String> pathSegments() {
        return objectKey == null ? List.of() : List.of(objectKey.split("/"));
    }

    /**
     * S3 路径第一段：上传时按用户/角色隔离的目录名。
     */
    public String ownerFolder() {
        return segmentAt(0);
    }

    /**
     * S3 路径第二段：当前聊天/上传所属的会话 ID。
     */
    public String sessionId() {
        return segmentAt(1);
    }

    /**
     * 这里按当前上传接口的真实路径规则解析：username/sessionId/fileType/fileUuid/fileName
     */
    public String fileTypeFolder() {
        return segmentAt(2);
    }

    /**
     * S3 路径第四段：上传时生成的文件唯一标识。
     */
    public String fileId() {
        return segmentAt(3);
    }

    /**
     * 文件名总是取路径最后一段。
     */
    public String fileName() {
        List<String> segments = pathSegments();
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    /**
     * 解析“最终文件类型”。
     *
     * <p>优先使用路径中的 fileType 目录；因为这是上传接口生成 object key 时显式写进去的，可信度更高。</p>
     * <p>如果目录层没有解析到，再退化为从文件名扩展名推断。</p>
     */
    public String resolvedFileType() {
        String folderType = fileTypeFolder();
        if (StringUtils.hasText(folderType)) {
            return folderType.toLowerCase();
        }
        String fileName = fileName();
        if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
            return null;
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 安全读取指定路径段。
     *
     * <p>如果 object key 层级不够，就返回 {@code null}，避免数组越界。</p>
     */
    private String segmentAt(int index) {
        List<String> segments = pathSegments();
        return segments.size() > index ? segments.get(index) : null;
    }
}
