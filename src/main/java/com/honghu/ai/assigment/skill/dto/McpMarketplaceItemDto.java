package com.honghu.ai.assigment.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

/**
 * 全网 MCP 商店列表项 DTO。
 *
 * <p>一条记录代表一个从全网目录同步来的 MCP Server。当前阶段先由后端
 * {@code McpMarketplaceService} 返回演示/缓存数据；后续接入 Smithery、PulseMCP
 * 或自建爬虫后，字段结构可以保持不变，前端无需重写页面。</p>
 *
 * @param id MCP 技能稳定 id，通常使用 mcp:xxx
 * @param name MCP Server 名称
 * @param grade 质量等级，例如 A、B
 * @param category 分类 key
 * @param categoryLabel 分类中文名
 * @param endpoint MCP 远程连接地址
 * @param description MCP 能力简介
 * @param auth 鉴权方式
 * @param health 健康状态
 * @param tools 工具数量
 * @param resources 资源数量
 * @param prompts 提示词数量
 * @param downloads 下载或安装热度
 * @param rating 评分
 * @param tags 标签集合
 * @param updated 同步更新时间文案
 * @param verified 是否通过基础安全/健康检查
 */
@Builder
@Schema(description = "全网 MCP 商店列表项")
public record McpMarketplaceItemDto(
        String id,
        String name,
        String grade,
        String category,
        String categoryLabel,
        String endpoint,
        String description,
        String auth,
        String health,
        int tools,
        int resources,
        int prompts,
        long downloads,
        double rating,
        List<String> tags,
        String updated,
        boolean verified
) {
}
