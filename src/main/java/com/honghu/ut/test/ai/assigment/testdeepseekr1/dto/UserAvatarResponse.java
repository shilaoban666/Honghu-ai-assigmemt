package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户头像上传响应")
public class UserAvatarResponse {

    /*
     * 用户主键。
     *
     * 前端拿到响应后可以用它确认当前返回的数据确实属于正在编辑资料的用户，
     * 也方便调试“上传成功但页面仍展示旧头像”的账号错位问题。
     */
    @Schema(description = "用户 ID")
    private String userId;

    /*
     * 头像实际写入的 S3 Bucket。
     *
     * 生产环境可能把普通文件、RAG 文档、头像分别放在不同 bucket；
     * 把 bucket 返回出来，便于运维排查对象存储路径。
     */
    @Schema(description = "头像所在 S3 Bucket")
    private String bucket;

    /*
     * 头像对象在 S3 中的 Key。
     *
     * 数据库存的是这个稳定 Key，而不是预签名 URL；
     * 因为预签名 URL 会过期，Key 才是长期可保存的对象地址。
     */
    @Schema(description = "头像 S3 对象 Key")
    private String avatarObjectKey;

    /*
     * 前端可直接使用的头像访问地址。
     *
     * 这个字段通常是短期有效的预签名 GET URL；
     * 页面刷新或 URL 过期后，需要再次通过后端生成新的访问地址。
     */
    @Schema(description = "头像访问 URL")
    private String avatar;

    /*
     * 文件 MIME 类型。
     *
     * 前端可以用它做展示或缓存判断；
     * 后端上传 S3 时也会把它写入 Content-Type，确保浏览器按图片而不是下载文件处理。
     */
    @Schema(description = "MIME 类型", example = "image/png")
    private String contentType;
}
