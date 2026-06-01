package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpMarketplaceItemDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.McpMarketplacePageDto;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.request.McpSkillInstallRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.mcp.McpMarketplaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全网 MCP 技能商店控制器。
 *
 * <p>前端“技能广场”和设置页“技能管理”都会进入详细 MCP 商店页面。
 * 这个 Controller 提供该页面后续接真实数据时需要的分页查询和安装接口。
 * 当前前端为了离线可用先使用本地模拟数据，但接口已经按生产分页形态设计好。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/skills/mcp-marketplace")
@Tag(name = "全网 MCP 技能商店", description = "查询、筛选、安装全网热门 MCP Server")
public class McpMarketplaceController {

    private final McpMarketplaceService marketplaceService;

    /**
     * 分页查询全网 MCP 市场。
     *
     * @param category 分类 key；all 表示全部
     * @param q 搜索关键词
     * @param healthyOnly 是否只看健康 MCP
     * @param noAuthOnly 是否只看 No Auth MCP
     * @param sort 排序方式：popular、rating、tools、recent
     * @param page 页码，从 0 开始
     * @param size 每页数量
     * @return MCP 分页结果
     */
    @GetMapping
    @Operation(summary = "分页查询全网 MCP 商店", description = "支持分类、搜索、健康状态、No Auth 和排序筛选")
    public ResponseEntity<McpMarketplacePageDto> search(
            @RequestParam(defaultValue = "all") String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean healthyOnly,
            @RequestParam(defaultValue = "false") boolean noAuthOnly,
            @RequestParam(defaultValue = "popular") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        // Controller 只负责接收 HTTP 参数并交给 Service，分页边界和 null 容错在 Service 内统一处理。
        return ResponseEntity.ok(marketplaceService.search(category, q, healthyOnly, noAuthOnly, sort, page, size));
    }

    /**
     * 安装一个 MCP 技能到当前用户。
     *
     * @param request 安装请求
     * @return 安装成功后的 MCP 条目
     */
    @PostMapping("/install")
    @Operation(summary = "安装 MCP 技能", description = "把全网 MCP 条目写入 skill 并建立 user_skill_install 关系")
    public ResponseEntity<McpMarketplaceItemDto> install(@RequestBody McpSkillInstallRequest request) {
        return ResponseEntity.ok(marketplaceService.install(request));
    }
}
