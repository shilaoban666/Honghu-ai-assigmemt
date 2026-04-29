package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.PresignedUploadResult;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * S3 文件上传服务。
 *
 * <p>业务层服务，负责文件上传相关的业务逻辑（路径计算、文件类型校验），
 * 底层 AWS 操作全部委托给 {@link AwsManager}。</p>
 *
 * <p>路径规则：{@code {bucket}/{username}/{sessionId}/{uuid}/{fileType}/{fileName}}</p>
 *
 * @author shilaoban
 * @since 2026-04-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3UploadService {

    private final AwsManager awsManager;
    private final AwsProperties awsProperties;
    private final UserRepository userRepository;

    /** 允许上传的文件类型白名单（扩展名，小写） */
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            "pdf", "png", "jpg", "jpeg", "gif", "bmp", "webp",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "md", "json", "xml"
    );

    /** 文件扩展名 → Content-Type 映射 */
    private static final Map<String, String> CONTENT_TYPE_MAP = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("webp", "image/webp"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("md", "text/markdown"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml")
    );

    /**
     * 生成文件上传的预签名 URL，同时返回 objectKey 供前端上传完成后回调。
     *
     * <p>完整上传流程：</p>
     * <ol>
     *     <li>前端调用本接口，拿到 {@code uploadUrl} 和 {@code objectKey}</li>
     *     <li>前端用 HTTP PUT + Content-Type 请求头，将文件直传到 {@code uploadUrl}</li>
     *     <li>上传成功后，前端携带 {@code objectKey} 调用 POST /api/v1/rag/files，后端写 DB 记录</li>
     * </ol>
     *
     * @param userId  用户Id（如 admin），决定 S3 路径的第一级目录
     * @param sessionId 会话 ID，决定 S3 路径的第二级目录
     * @param fileType  文件扩展名（如 pdf、png），决定第四级目录，同时用于推断 Content-Type
     * @param fileName  原始文件名（可选，为空时使用 UUID 命名）
     * @return {@link PresignedUploadResult} 包含 uploadUrl、objectKey、contentType
     * @throws IllegalArgumentException 如果 fileType 不在白名单中
     */
    public PresignedUploadResult generatePresignedUploadUrl(String userId, String sessionId, String fileType, String fileName) {
        // 1. 校验文件类型白名单
        String normalizedType = fileType.toLowerCase().trim();
        if (!ALLOWED_FILE_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType
                    + "，允许的类型: " + ALLOWED_FILE_TYPES);
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String bucket = awsProperties.getS3().getUploadedBucket();

        // 2. 确保 S3 存储桶存在（不存在时自动创建）
        awsManager.ensureBucketExists(bucket);

        // 3. 构造 S3 路径: {username}/{sessionId}/{fileType}/{uuid}
        //    uuid 保证同一用户同一 session 下多次上传同名文件不会互相覆盖
        String fileUuid = UUID.randomUUID().toString();
        String folderKey = String.join("/", user.getUsername(), sessionId, normalizedType, fileUuid);

        // 4. 在 S3 中创建文件夹占位对象（便于控制台查看目录结构）
        awsManager.ensureFolderExists(bucket, folderKey);

        // 5. 拼接完整的 objectKey（文件名用原始名或 UUID 兜底）
        String actualFileName = (fileName != null && !fileName.isBlank())
                ? fileName
                : fileUuid + "." + normalizedType;
        String objectKey = folderKey + "/" + actualFileName;

        // 6. 推断 Content-Type，生成预签名 PUT URL
        String contentType = CONTENT_TYPE_MAP.getOrDefault(normalizedType, "application/octet-stream");
        Duration expiration = Duration.ofMinutes(awsProperties.getS3().getPresignedUrlExpirationMinutes());
        String uploadUrl = awsManager.generatePresignedPutUrl(bucket, objectKey, contentType, expiration);

        log.info("已生成预签名上传 URL: username={}, sessionId={}, fileType={}, objectKey={}",
                user.getUsername(), sessionId, normalizedType, objectKey);

        // 7. 将 url 和 objectKey 一起返回，objectKey 是前端第三步 POST 时必须携带的参数
        return new PresignedUploadResult(uploadUrl, objectKey, contentType);
    }
}
