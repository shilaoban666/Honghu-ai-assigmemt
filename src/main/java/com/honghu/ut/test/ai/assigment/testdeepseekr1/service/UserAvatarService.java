package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.UserAvatarResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/*
 * 用户头像服务。
 *
 * 这个类负责头像上传、头像 URL 生成、旧头像清理三件事：
 * 1. 上传时先校验文件大小和 MIME 类型，防止用户上传大文件或非图片文件；
 * 2. 新头像写入 S3 后，把对象 Key 保存到用户表；
 * 3. 数据库更新成功后，尽力删除旧头像，避免对象存储长期堆积无用文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAvatarService {

    // 头像最大允许 5MB；这个大小足够普通头像使用，也能避免把头像接口当成大文件上传入口。
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;
    // 白名单 MIME 类型；只允许浏览器常见图片格式，拒绝 SVG/脚本/压缩包等潜在风险内容。
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );
    // MIME 类型到文件扩展名的映射；生成 objectKey 时使用，方便对象存储里按文件名快速识别格式。
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    // S3 操作封装，负责建 bucket、上传对象、删除对象、生成预签名 URL。
    private final AwsManager awsManager;
    // AWS/S3 配置，头像 bucket 和预签名 URL 过期时间都从这里读取。
    private final AwsProperties awsProperties;
    // 用户仓储，用来读取并保存用户头像对象 Key。
    private final UserRepository userRepository;

    @Transactional
    public UserAvatarResponse uploadAvatar(String userId, MultipartFile file) {
        // 先确认用户存在；不存在直接报错，避免上传了对象但数据库找不到归属用户。
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        // 校验文件是否为空、是否超限、MIME 类型是否在白名单内。
        validateFile(file);

        // 读取头像 bucket；没有配置时直接失败，防止对象被写到错误位置。
        String bucket = requireBucket();
        // 统一把 Content-Type 转小写并去掉空格，避免浏览器传入 Image/PNG 这类大小写变体。
        String contentType = normalizeContentType(file.getContentType());
        // 根据 MIME 类型找到扩展名，用于生成更可读的 S3 objectKey。
        String extension = EXTENSIONS.get(contentType);
        // 使用用户 ID 分目录，并用 UUID 生成文件名，避免覆盖同名旧头像。
        String objectKey = "avatars/%s/%s.%s".formatted(user.getUserId(), UUID.randomUUID(), extension);
        // 记录旧头像 Key；新头像保存成功后再尝试删除旧对象。
        String oldObjectKey = user.getAvatarObjectKey();

        try {
            // 如果头像 bucket 不存在则创建，保证首次部署或新环境能自恢复。
            awsManager.ensureBucketExists(bucket);
            // 把文件字节写入 S3，同时写入 Content-Type，浏览器访问时才能正确按图片显示。
            awsManager.putObject(bucket, objectKey, file.getBytes(), contentType);
        } catch (IOException e) {
            // MultipartFile#getBytes 可能失败，转换成业务异常让上层返回清晰错误。
            throw new IllegalStateException("读取头像文件失败", e);
        }
        log.info("用户头像已上传到 S3: userId={}, bucket={}, objectKey={}", user.getUserId(), bucket, objectKey);

        // 数据库只保存长期稳定的 objectKey 和 contentType，不保存会过期的预签名 URL。
        user.setAvatarObjectKey(objectKey);
        user.setAvatarContentType(contentType);
        userRepository.save(user);

        // 新头像已经写库后再清理旧头像；删除失败不影响本次上传成功。
        deleteOldAvatarQuietly(bucket, oldObjectKey, objectKey);

        // 返回前端需要展示的全部信息，其中 avatar 是可直接用于 img.src 的预签名访问地址。
        return UserAvatarResponse.builder()
                .userId(user.getUserId())
                .bucket(bucket)
                .avatarObjectKey(objectKey)
                .avatar(resolveAvatarUrl(user))
                .contentType(contentType)
                .build();
    }

    public String resolveAvatarUrl(User user) {
        // 用户为空或没有头像 Key 时，说明当前用户还没上传头像，直接返回 null 让前端使用默认头像。
        if (user == null || !StringUtils.hasText(user.getAvatarObjectKey())) {
            return null;
        }
        try {
            // 根据数据库中的 objectKey 生成短期 GET URL；URL 到期后重新调这个方法即可。
            return awsManager.generatePresignedGetUrl(
                    requireBucket(),
                    user.getAvatarObjectKey(),
                    Duration.ofMinutes(awsProperties.getS3().getPresignedUrlExpirationMinutes())
            );
        } catch (Exception e) {
            // URL 生成失败不能影响用户资料主流程，所以记录警告并返回 null。
            log.warn("生成头像访问链接失败: userId={}, key={}, error={}",
                    user.getUserId(), user.getAvatarObjectKey(), e.getMessage());
            return null;
        }
    }

    private void validateFile(MultipartFile file) {
        // 文件对象为空或内容为空，都不是有效头像。
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("头像文件不能为空");
        }
        // 限制头像大小，避免用户把头像接口当成任意大文件上传接口。
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("头像文件不能超过 5MB");
        }
        // 统一 MIME 类型格式后再判断白名单。
        String contentType = normalizeContentType(file.getContentType());
        // 非 JPG/PNG/WEBP 一律拒绝，减少 XSS、脚本文件伪装等风险。
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("仅支持 JPG、PNG、WEBP 图片");
        }
    }

    private String normalizeContentType(String contentType) {
        // null 归一为空字符串；非空则转小写并裁剪空白，方便稳定做白名单判断。
        return contentType == null ? "" : contentType.toLowerCase().trim();
    }

    private String requireBucket() {
        // 头像 bucket 是必需配置，不能静默回退到普通文件 bucket。
        String bucket = awsProperties.getS3().getAvatarBucket();
        // 没配置时抛出明确错误，提醒部署人员补 app.aws.s3.avatar-bucket。
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("未配置 app.aws.s3.avatar-bucket，无法上传头像");
        }
        // 返回可用 bucket 名称。
        return bucket;
    }

    private void deleteOldAvatarQuietly(String bucket, String oldObjectKey, String newObjectKey) {
        // 没有旧头像，或者旧头像刚好等于新头像，都不需要删除。
        if (!StringUtils.hasText(oldObjectKey) || oldObjectKey.equals(newObjectKey)) {
            return;
        }
        try {
            // 尝试删除旧对象，减少对象存储中无引用文件的数量。
            awsManager.deleteObject(bucket, oldObjectKey);
        } catch (Exception e) {
            // 删除旧头像失败不回滚新头像上传，只记录日志让后续运维清理。
            log.warn("删除旧头像失败: bucket={}, key={}, error={}", bucket, oldObjectKey, e.getMessage());
        }
    }
}
