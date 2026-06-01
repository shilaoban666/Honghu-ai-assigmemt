package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto;

import lombok.Builder;

import java.util.List;

/**
 * 统一能力市场分页响应。
 *
 * <p>分页字段采用 0 基页码，和 Spring Data/Pageable 的习惯保持一致。
 * {@link #categories()} 是当前查询结果的分类统计，不一定是全库固定分类；前端切换 kind、搜索词或过滤条件后，
 * 可以直接使用响应里的分类重新渲染侧边栏。</p>
 *
 * @param items 当前页能力列表
 * @param page 当前页码，从 0 开始
 * @param size 实际使用的每页数量，后端会限制最大值
 * @param total 当前查询条件下的总条数
 * @param hasMore 是否还有下一页，便于无限滚动组件快速判断
 * @param categories 当前查询条件下的分类统计
 */
@Builder
public record CapabilityPageDto(
        List<CapabilityDto> items,
        int page,
        int size,
        long total,
        boolean hasMore,
        List<CapabilityCategoryDto> categories
) {
}
