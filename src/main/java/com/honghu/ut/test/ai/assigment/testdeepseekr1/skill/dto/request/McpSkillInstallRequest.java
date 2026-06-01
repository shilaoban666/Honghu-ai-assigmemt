package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * MCP 技能安装请求。
 *
 * <p>用户在全网 MCP 商店点击“添加”时，前端会把 MCP id 和用户配置传给后端。
 * 当前阶段 config 可以为空；后续对需要 API Key / OAuth 的 MCP，会把用户填写的配置放在这里。</p>
 *
 * @param userId 当前用户 ID
 * @param mcpId MCP 技能 id，例如 mcp:tavily
 * @param config 用户级配置，例如 API Key、endpoint、env 变量
 */
@Schema(description = "MCP 技能安装请求")
public record McpSkillInstallRequest(
        String userId,
        String mcpId,
        Map<String, Object> config
) {
}
