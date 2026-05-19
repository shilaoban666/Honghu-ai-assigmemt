package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import javax.tools.Tool;
import java.util.List;

/**
 * 聊天请求DTO
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Data
@Schema(description = "聊天请求参数")
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    @Schema(description = "用户消息内容", example = "你好，介绍一下你自己")
    private String message;

    @Schema(description = "会话ID", example = "session-123")
    private String sessionId;

    @Schema(description = "用户ID", example = "user-123")
    private String userId ;

    /**
     * 本次聊天使用的 workspace。
     *
     * <p>不传时后端会使用用户默认 workspace。个人 workspace 会按用户角色计算模型和额度；
     * 企业 workspace 会按 plan 计算团队共享模型和额度。前端也可以通过 X-Workspace-Id 传入，
     * Header 优先级高于请求体字段。</p>
     */
    @Schema(description = "Workspace ID。不传时使用用户默认 workspace；也可由 X-Workspace-Id 请求头覆盖", example = "workspace-123")
    private String workspaceId;

    @Schema(description = "模型名称", example = "deepseek-r1:8b")
    private String model ;

    @NotNull(message = "是否流式输出不能为空")
    @Schema(description = "是否启用流式输出", example = "false")
    private Boolean stream = false;

    @Schema(description = "系统提示词", example = "你是一个专业、清晰、可靠的 AI 助手，默认使用中文回答。")
    private String systemMessage;

    @DecimalMin("0.0") @DecimalMax("2.0")
    @Schema(description = "温度参数(0-1)", example = "0.7", minimum = "0", maximum = "1")
    private Double temperature = 0.7;

    @Schema(description = "最大token数", example = "2048")
    private Integer maxTokens = 2048;

    @Schema(description = "本轮用户消息要附加的已上传文件 fileId 列表，对应 /api/v1/rag/files 返回值")
    private List<String> attachmentFileIds;

    @Schema(description = "工具列表")
    private List<Tool> tools;
}
