package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.S3UploadReceivedMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * RAG 文件登记服务。
 *
 * <p>它处理前端三步上传流程里的第三步：</p>
 * <ol>
 *     <li>前端先拿预签名 URL（带 fileType 校验）</li>
 *     <li>前端 PUT 文件到 S3</li>
 *     <li>前端调用本服务对应接口，把文件主记录先登记成 {@code RECEIVED}</li>
 * </ol>
 *
 * <p>这样做的好处是：即使 SQS 事件稍后才投递，前端依然可以立刻轮询到状态，
 * 不会出现"文件明明上传成功了，但状态接口还查不到"的空窗期。</p>
 *
 * <p><strong>安全要点：</strong></p>
 * <ul>
 *     <li>必须显式传入调用者 userId 并验证 objectKey 第一段（ownerFolder）== 该用户的 username，
 *         否则会出现"任意登录者把别人 username 路径下的对象登记成自己的"越权问题。</li>
 *     <li>fileType 必须命中 RAG 支持的扩展名白名单，避免攻击者用 {@code admin/sess/etc/passwd/x/y.bin}
 *         这种伪造路径污染 DB。</li>
 *     <li>fileName 在落库前做长度截断 + 控制字符过滤，避免日志/前端展示被污染。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagFileRegistrationService {

    private static final String RECEIVED_DETAIL_MESSAGE = "文件已上传，等待 SQS 异步摄取";

    /** 文件名最大保留长度，超出截断；与 RagDocument.fileName 列长度对齐留出余量。 */
    private static final int MAX_FILE_NAME_LENGTH = 200;

    private final AwsProperties awsProperties;
    private final RagProperties ragProperties;
    private final RagDocumentRepository ragDocumentRepository;
    private final UserRepository userRepository;
    private final RagAccessGuard ragAccessGuard;

    /**
     * 根据 objectKey 登记一个已上传文件。
     *
     * <p>该方法是幂等的：如果同一个 objectKey 已经存在记录，就只做必要字段补齐，
     * 不会把已经进入 PROCESSING / INDEXED / FAILED / SKIPPED 的状态错误回退为 RECEIVED。</p>
     *
     * @param callerUserId 调用者 user_id（来自 controller 的 X-User-Id 头校验后传入）
     * @param objectKey    S3 object key
     * @param fileName     原始文件名，可选
     * @return 文件唯一 ID，供前端继续轮询状态接口
     */
    @Transactional
    public String registerUploadedFile(String callerUserId, String objectKey, String fileName) {
        if (!StringUtils.hasText(callerUserId)) {
            throw new RagAccessDeniedException("缺少调用者 userId，无法登记上传文件");
        }
        if (!StringUtils.hasText(objectKey)) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }

        String bucketName = awsProperties.getS3().getUploadedBucket();
        if (!StringUtils.hasText(bucketName)) {
            throw new IllegalStateException("未配置 app.aws.s3.uploaded-bucket，无法登记上传文件");
        }

        S3UploadReceivedMessage parsedMessage = S3UploadReceivedMessage.fromObjectKey(bucketName, objectKey.trim());
        validateStructuredObjectKey(parsedMessage);

        // 越权防御：objectKey 第一段必须等于调用者真实 username。
        // 这一步阻止"用户 A 拿到用户 B 的某条 objectKey 后调本接口替 B 登记"的攻击。
        User caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> new RagAccessDeniedException("调用者 userId 无效"));
        if (!Objects.equals(parsedMessage.ownerFolder(), caller.getUsername())) {
            log.warn("拒绝登记非当前用户路径下的文件: callerUserId={}, callerUsername={}, ownerFolder={}",
                    callerUserId, caller.getUsername(), parsedMessage.ownerFolder());
            throw new RagAccessDeniedException("无权登记其他用户路径下的文件");
        }
        ragAccessGuard.requireOwnedSession(callerUserId, parsedMessage.sessionId());

        // 文件类型必须在 RAG 配置的白名单内，避免伪造路径写入"未知类型"或注入异常扩展名。
        Set<String> allowedExtensions = ragProperties.getIngestion().getSupportedExtensions().stream()
                .filter(StringUtils::hasText)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String fileType = parsedMessage.resolvedFileType();
        if (!StringUtils.hasText(fileType) || !allowedExtensions.contains(fileType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType);
        }

        RagDocument document = ragDocumentRepository.findByBucketNameAndObjectKey(bucketName, parsedMessage.objectKey())
                .orElseGet(RagDocument::new);

        // 同一份文档跨用户不应该出现，但为了挡住"路径恰好命中一条历史记录但 ownerFolder 不一致"的怪情况，
        // 只允许同 ownerFolder 的请求继续 update。
        if (StringUtils.hasText(document.getOwnerFolder())
                && !Objects.equals(document.getOwnerFolder(), parsedMessage.ownerFolder())) {
            log.warn("登记请求与历史 ownerFolder 不一致，拒绝覆盖: caller={}, existing={}",
                    parsedMessage.ownerFolder(), document.getOwnerFolder());
            throw new RagAccessDeniedException("无权登记其他用户的文件记录");
        }
        if (StringUtils.hasText(document.getSessionId())
                && !Objects.equals(document.getSessionId(), parsedMessage.sessionId())) {
            log.warn("登记请求试图改写文件所属 session，拒绝覆盖: fileId={}, existingSessionId={}, requestSessionId={}",
                    document.getFileId(), document.getSessionId(), parsedMessage.sessionId());
            throw new RagAccessDeniedException("无权修改文件所属会话");
        }

        document.setBucketName(bucketName);
        document.setObjectKey(parsedMessage.objectKey());
        document.setOwnerFolder(parsedMessage.ownerFolder());
        if (!StringUtils.hasText(document.getSessionId())) {
            document.setSessionId(parsedMessage.sessionId());
        }
        document.setFileId(parsedMessage.fileId());
        document.setFileType(fileType);
        document.setFileName(sanitizeFileName(StringUtils.hasText(fileName) ? fileName : parsedMessage.fileName()));

        if (document.getStatus() == null) {
            document.setStatus(RagDocument.Status.RECEIVED);
        }
        if (document.getStatus() == RagDocument.Status.RECEIVED && !StringUtils.hasText(document.getErrorMessage())) {
            document.setErrorMessage(RECEIVED_DETAIL_MESSAGE);
        }

        RagDocument saved = ragDocumentRepository.save(document);
        return saved.getFileId();
    }

    /**
     * 校验 objectKey 是否符合当前上传接口约定的目录结构。
     *
     * <p>当前要求最少满足：{@code owner/sessionId/fileType/fileId/fileName}。</p>
     */
    private void validateStructuredObjectKey(S3UploadReceivedMessage parsedMessage) {
        if (!StringUtils.hasText(parsedMessage.ownerFolder())
                || !StringUtils.hasText(parsedMessage.sessionId())
                || !StringUtils.hasText(parsedMessage.fileId())
                || !StringUtils.hasText(parsedMessage.resolvedFileType())
                || !StringUtils.hasText(parsedMessage.fileName())) {
            throw new IllegalArgumentException("objectKey 不符合上传路径规范，期望格式为 owner/sessionId/fileType/fileId/fileName");
        }
    }

    /**
     * 清洗 fileName：去掉控制字符 / 路径分隔符，并截断到合理长度。
     *
     * <p>该字段会展示给前端 + 写日志，未清洗的恶意文件名可能导致：</p>
     * <ul>
     *     <li>日志里塞 \r\n 伪造日志条目（log injection）</li>
     *     <li>前端按 HTML 渲染时触发 XSS</li>
     *     <li>下载文件名被路径符 / NUL 截断从而绕过保存对话框</li>
     * </ul>
     */
    private String sanitizeFileName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String filtered = trimmed
                .replaceAll("[\\u0000-\\u001F\\u007F]", "")
                .replace('/', '_')
                .replace('\\', '_');
        if (filtered.length() > MAX_FILE_NAME_LENGTH) {
            filtered = filtered.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return filtered;
    }
}
