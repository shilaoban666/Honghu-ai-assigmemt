package com.honghu.ai.assigment.skill.dto;

import lombok.Builder;

/**
 * 市场分类统计 DTO。
 *
 * <p>每次市场分页响应都会携带分类列表，前端可以直接用它渲染左侧分类导航和数量徽标。
 * 分类不是固定写死在前端，因为 MCP、Claude Skill、CLI 的目录来源不同，后续全网同步后分类会随数据变化。</p>
 *
 * @param key 稳定分类 key，例如 {@code all}、{@code developer}、{@code writing}
 * @param label 展示名称；当前没有 i18n 表时可直接使用 key 或中文名
 * @param icon 分类小图标或缩写
 * @param count 当前筛选范围内属于该分类的能力数量
 */
@Builder
public record CapabilityCategoryDto(
        String key,
        String label,
        String icon,
        long count
) {
}
