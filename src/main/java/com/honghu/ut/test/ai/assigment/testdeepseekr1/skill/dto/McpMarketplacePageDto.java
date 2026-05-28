package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

/**
 * 全网 MCP 商店分页响应。
 *
 * <p>详细商店页面会懒加载列表，因此后端响应需要同时告诉前端当前页数据、
 * 总数量和是否还有下一页。这样前端可以避免一次性渲染一千多条 MCP 造成卡顿。</p>
 *
 * @param items 当前页 MCP 列表
 * @param page 当前页码，从 0 开始
 * @param size 每页数量
 * @param total 筛选后的总数量
 * @param hasMore 是否还有下一页
 * @param categories 分类统计，用于左侧导航栏
 */
@Builder
@Schema(description = "全网 MCP 商店分页响应")
public record McpMarketplacePageDto(
        List<McpMarketplaceItemDto> items,
        int page,
        int size,
        long total,
        boolean hasMore,
        List<McpMarketplaceCategoryDto> categories
) {
}
