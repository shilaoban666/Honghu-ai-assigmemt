package com.honghu.ai.assigment.manager;

import com.honghu.ai.assigment.config.properties.AwsProperties;
import com.honghu.ai.assigment.service.RagDocumentProcessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AWS 资源统一管理器。
 *
 * <p>封装所有 AWS 服务（S3、SQS）的底层操作，
 * 业务层（如 {@link RagDocumentProcessService}）通过该管理器间接调用 AWS SDK，
 * 实现 <strong>基础设施与业务逻辑解耦</strong>。</p>
 *
 * <h3>设计思路</h3>
 * <ul>
 *     <li>所有 AWS 客户端由 {@link com.honghu.ai.assigment.config.AwsConfig} 统一创建</li>
 *     <li>本类只做 "薄封装"：统一异常处理、日志、参数校验</li>
 *     <li>SQS 相关方法标注了 {@code @Nullable}，调用前需检查 sqsClient 是否注入</li>
 * </ul>
 *
 * @author shilaoban
 * @since 2026-04-18
 */
@Slf4j
@Component
public class AwsManager {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AwsProperties awsProperties;

    /**
     * SQS 客户端，仅在 {@code app.aws.sqs.enabled=true} 时注入。
     * 调用 SQS 方法前需先判空。
     */
    @Nullable
    private final SqsClient sqsClient;

    @Autowired
    public AwsManager(S3Client s3Client,
                      S3Presigner s3Presigner,
                      AwsProperties awsProperties,
                      @Autowired(required = false) @Nullable SqsClient sqsClient) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.awsProperties = awsProperties;
        this.sqsClient = sqsClient;
    }

    // =====================================================
    //                    S3 — 桶管理
    // =====================================================

    /**
     * 检查存储桶是否存在。
     *
     * @param bucket 桶名称
     * @return true 表示存在
     */
    public boolean doesBucketExist(String bucket) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    /**
     * 确保存储桶存在，不存在则自动创建。
     *
     * @param bucket 桶名称
     */
    public void ensureBucketExists(String bucket) {
        if (!doesBucketExist(bucket)) {
            log.info("存储桶不存在，正在创建: {}", bucket);
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("存储桶创建成功: {}", bucket);
        }
    }

    /**
     * 使用默认桶名称（来自配置文件），确保桶存在。
     */
    public void ensureDefaultBucketExists() {
        ensureBucketExists(awsProperties.getS3().getUploadedBucket());
    }

    // =====================================================
    //                S3 — 文件夹 & 对象操作
    // =====================================================

    /**
     * 在 S3 中创建"文件夹"占位对象。
     *
     * <p>S3 本身是扁平 key-value 存储，没有真正的文件夹。
     * 这里通过创建以 "/" 结尾的 0 字节对象来模拟文件夹，
     * 主要用于 S3 控制台的可视化展示。</p>
     *
     * @param bucket    桶名称
     * @param folderKey 文件夹路径（不含尾部 "/"，方法内部会追加）
     */
    public void ensureFolderExists(String bucket, String folderKey) {
        String folderObjectKey = folderKey.endsWith("/") ? folderKey : folderKey + "/";
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(folderObjectKey)
                            .contentLength(0L)
                            .build(),
                    RequestBody.empty()
            );
            log.debug("文件夹已确保存在: bucket={}, key={}", bucket, folderObjectKey);
        } catch (S3Exception e) {
            log.warn("创建文件夹占位对象失败: bucket={}, key={}, error={}", bucket, folderObjectKey, e.getMessage());
        }
    }

    /**
     * 上传字节数组到 S3。
     *
     * @param bucket      桶名称
     * @param key         对象 key
     * @param data        文件内容
     * @param contentType MIME 类型
     */
    public void putObject(String bucket, String key, byte[] data, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(data)
        );
        log.info("对象上传成功: bucket={}, key={}, size={}bytes", bucket, key, data.length);
    }

    /**
     * 删除 S3 对象。
     *
     * @param bucket 桶名称
     * @param key    对象 key
     */
    public void deleteObject(String bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        log.info("对象已删除: bucket={}, key={}", bucket, key);
    }

    /**
     * 检查 S3 对象是否存在。
     *
     * @param bucket 桶名称
     * @param key    对象 key
     * @return true 表示存在
     */
    public boolean doesObjectExist(String bucket, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    /**
     * 获取对象元数据。
     */
    public HeadObjectResponse headObject(String bucket, String key) {
        return s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * 直接读取对象二进制内容。
     *
     * <p>简单 RAG 第一版会在这里把文件拉到应用内存中做文本抽取，
     * 后续如需支持超大文件，可再扩展成流式处理。</p>
     */
    public byte[] getObjectBytes(String bucket, String key) {
        ResponseBytes<GetObjectResponse> responseBytes = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()
        );
        return responseBytes.asByteArray();
    }

    // =====================================================
    //                S3 — 预签名 URL
    // =====================================================

    /**
     * 生成预签名上传（PUT）URL。
     *
     * <p>前端拿到此 URL 后，使用 HTTP PUT 方法直接上传文件到 S3，
     * 服务端不做流量中转。</p>
     *
     * @param bucket      桶名称
     * @param key         对象 key（完整路径）
     * @param contentType MIME 类型（如 application/pdf、image/png）
     * @param duration    URL 有效时长
     * @return 预签名上传 URL
     */
    public String generatePresignedPutUrl(String bucket, String key, String contentType, Duration duration) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(duration)
                .putObjectRequest(putRequest)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        String url = presigned.url().toString();
        log.debug("生成预签名 PUT URL: bucket={}, key={}, expiration={}", bucket, key, duration);
        return url;
    }

    /**
     * 生成预签名下载（GET）URL。
     *
     * @param bucket   桶名称
     * @param key      对象 key
     * @param duration URL 有效时长
     * @return 预签名下载 URL
     */
    public String generatePresignedGetUrl(String bucket, String key, Duration duration) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(getRequest)
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        String url = presigned.url().toString();
        log.debug("生成预签名 GET URL: bucket={}, key={}, expiration={}", bucket, key, duration);
        return url;
    }

    // =====================================================
    //                    SQS — 消息队列
    // =====================================================

    /**
     * 发送消息到 SQS 队列。
     *
     * @param queueUrl   队列 URL
     * @param messageBody 消息内容（通常是 JSON）
     * @return 消息 ID
     * @throws IllegalStateException 如果 SQS 未启用
     */
    public String sendMessage(String queueUrl, String messageBody) {
        requireSqsClient();
        SendMessageResponse response = sqsClient.sendMessage(
                SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(messageBody)
                        .build()
        );
        log.info("SQS 消息已发送: queueUrl={}, messageId={}", queueUrl, response.messageId());
        return response.messageId();
    }

    /**
     * 发送带属性的消息到 SQS 队列。
     *
     * @param queueUrl    队列 URL
     * @param messageBody 消息内容
     * @param attributes  消息属性（key-value 字符串对）
     * @return 消息 ID
     */
    public String sendMessage(String queueUrl, String messageBody, Map<String, String> attributes) {
        requireSqsClient();
        var builder = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody);

        if (attributes != null && !attributes.isEmpty()) {
            var msgAttributes = new java.util.HashMap<String, MessageAttributeValue>();
            attributes.forEach((k, v) -> msgAttributes.put(k,
                    MessageAttributeValue.builder().dataType("String").stringValue(v).build()));
            builder.messageAttributes(msgAttributes);
        }

        SendMessageResponse response = sqsClient.sendMessage(builder.build());
        log.info("SQS 消息已发送(带属性): queueUrl={}, messageId={}", queueUrl, response.messageId());
        return response.messageId();
    }

    /**
     * 从 SQS 队列接收消息。
     *
     * @param queueUrl    队列 URL
     * @param maxMessages 最大拉取条数（1~10）
     * @return 消息列表
     */
    public List<Message> receiveMessages(String queueUrl, int maxMessages) {
        requireSqsClient();
        ReceiveMessageResponse response = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(Math.min(maxMessages, 10))
                        .waitTimeSeconds(awsProperties.getSqs().getWaitTimeSeconds())
                        .visibilityTimeout(awsProperties.getSqs().getVisibilityTimeoutSeconds())
                        .build()
        );
        log.debug("SQS 收到 {} 条消息: queueUrl={}", response.messages().size(), queueUrl);
        return response.messages();
    }

    /**
     * 删除已处理的 SQS 消息。
     *
     * @param queueUrl      队列 URL
     * @param receiptHandle 消息回执（从 receiveMessages 返回的消息中获取）
     */
    public void deleteMessage(String queueUrl, String receiptHandle) {
        requireSqsClient();
        sqsClient.deleteMessage(
                DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(receiptHandle)
                        .build()
        );
        log.debug("SQS 消息已删除: queueUrl={}", queueUrl);
    }

    /**
     * 获取或创建 SQS 队列，返回队列 URL。
     *
     * @param queueName 队列名称
     * @return 队列 URL
     */
    public String getOrCreateQueue(String queueName) {
        requireSqsClient();
        try {
            GetQueueUrlResponse response = sqsClient.getQueueUrl(
                    GetQueueUrlRequest.builder().queueName(queueName).build());
            return response.queueUrl();
        } catch (QueueDoesNotExistException e) {
            log.info("SQS 队列不存在，正在创建: {}", queueName);
            CreateQueueResponse response = sqsClient.createQueue(
                    CreateQueueRequest.builder().queueName(queueName).build());
            log.info("SQS 队列创建成功: {} -> {}", queueName, response.queueUrl());
            return response.queueUrl();
        }
    }

    /**
     * 只读取队列 URL，不自动创建队列。
     */
    public String getQueueUrl(String queueName) {
        requireSqsClient();
        GetQueueUrlResponse response = sqsClient.getQueueUrl(
                GetQueueUrlRequest.builder().queueName(queueName).build());
        return response.queueUrl();
    }

    // =====================================================
    //                    内部工具方法
    // =====================================================

    /**
     * 检查 SQS 客户端是否可用，不可用时抛出异常。
     */
    private void requireSqsClient() {
        if (sqsClient == null) {
            throw new IllegalStateException(
                    "SQS 客户端未初始化，请在配置文件中设置 app.aws.sqs.enabled=true");
        }
    }
}




