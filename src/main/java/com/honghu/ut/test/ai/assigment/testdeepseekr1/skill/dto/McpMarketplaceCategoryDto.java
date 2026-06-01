package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 全网 MCP 商店左侧分类 DTO。
 *
 * <p>前端详细商店需要一边展示分类导航，一边展示每个分类下可用 MCP 数量。
 * 这个 DTO 就是给左侧导航栏使用的轻量数据结构。</p>
 *
 * @param key 分类稳定 key，例如 security、prompts、resources
 * @param label 分类展示名
 * @param icon 分类短图标文本
 * @param count 当前分类下的 MCP 数量
 */
@Builder
@Schema(description = "全网 MCP 商店分类")
public record McpMarketplaceCategoryDto(
        @Schema(description = "分类 key", example = "security")
        String key,
        @Schema(description = "分类展示名", example = "安全")
        String label,
        @Schema(description = "短图标文本", example = "盾")
        String icon,
        @Schema(description = "分类下 MCP 数量", example = "249")
        long count
) {
}
