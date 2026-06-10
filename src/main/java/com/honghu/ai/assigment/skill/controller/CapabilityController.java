package com.honghu.ai.assigment.skill.controller;

import com.honghu.ai.assigment.skill.core.CapabilityService;
import com.honghu.ai.assigment.skill.dto.CapabilityDto;
import com.honghu.ai.assigment.skill.dto.request.CapabilityInstallRequest;
import com.honghu.ai.assigment.skill.dto.CapabilityPageDto;
import com.honghu.ai.assigment.skill.dto.request.SessionSkillToggleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一能力系统的 HTTP 入口。
 *
 * <p>这里把前端需要看到的四类能力统一成一组 REST API：</p>
 * <ul>
 *     <li>{@code builtin}：Java 进程内的内置工具，由 {@code @NativeSkill}/{@code @NativeTool} 注册。</li>
 *     <li>{@code mcp}：外部 MCP Server 目录项，当前阶段支持目录、安装、会话开关。</li>
 *     <li>{@code skill}：兼容 Claude Skills 语义的 prompt 技能目录项。</li>
 *     <li>{@code cli}：命令行能力目录项，当前阶段只做目录和开关，不直接执行宿主命令。</li>
 * </ul>
 *
 * <p>Controller 只负责协议层参数绑定和响应包装，不在这里写状态规则。
 * 具体的“安装态、会话启用态、默认启用、必选能力、角色限制”全部集中在
 * {@link CapabilityService}，这样后续 MCP client、CLI sandbox、Claude Skill prompt resolver
 * 接进来时，不需要重写前端 API。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@Tag(name = "统一能力系统", description = "内置工具、MCP、Skills、CLI 的统一目录、安装和会话开关")
public class CapabilityController {
    /**
     * 当前代码库已有的轻量身份传递方式。
     *
     * <p>前端通过 {@code attachIdentityHeaders} 写入该请求头；如果缺失，服务层会降级为
     * {@code guest}，便于本地开发和浏览器烟测。正式用户体系接入后，应在网关或 Spring Security
     * 层把真实登录态转换成这里使用的 userId。</p>
     */
    private static final String USER_ID_HEADER = "X-User-Id";

    private final CapabilityService capabilityService;

    /**
     * 分页查询统一能力市场。
     *
     * <p>这个接口给“能力商店”页面使用，可以同时展示内置工具、MCP、Claude Skill 和 CLI 能力。
     * Controller 只负责把 HTTP 查询参数传给服务层；真正的过滤、排序、分页和启用状态计算都在
     * {@link CapabilityService#marketplace(String, String, String, String, String, String, int, int)} 中完成。</p>
     *
     * @param userId 请求头中的用户 id，缺失时服务层会按 guest 处理
     * @param sessionId 可选会话 id，传入后返回值里的 enabled 会反映该会话最终启用状态
     * @param kind 能力大类过滤条件，例如 builtin、mcp、skill、cli
     * @param category 分类过滤条件，all 表示不过滤分类
     * @param q 搜索关键词
     * @param sort 排序方式，例如 popular、rating、recent、tools
     * @param page 0 基页码
     * @param size 每页数量，服务层会做上限保护
     * @return 能力分页结果
     */
    @GetMapping("/skills/capabilities/marketplace")
    @Operation(summary = "分页查询统一能力市场")
    public ResponseEntity<CapabilityPageDto> marketplace(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "all") String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "popular") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        // sessionId 是可选的：不传时只返回当前用户安装态；传入时还会返回该会话的 enabled 状态。
        return ResponseEntity.ok(capabilityService.marketplace(userId, sessionId, kind, category, q, sort, page, size));
    }

    /**
     * 查询全部启用中的内置能力。
     *
     * <p>内置能力来自 Java 代码中的 {@code @NativeSkill} 和 {@code @NativeTool} 注解，
     * 不需要用户显式安装，但仍然会根据当前会话返回 enabled 状态，方便前端展示开关。</p>
     *
     * @param userId 请求头中的用户 id
     * @param sessionId 可选会话 id
     * @return 内置能力 DTO 列表
     */
    @GetMapping("/skills/capabilities/builtin")
    @Operation(summary = "查询内置能力")
    public ResponseEntity<List<CapabilityDto>> builtin(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam(required = false) String sessionId) {
        return ResponseEntity.ok(capabilityService.builtin(userId, sessionId));
    }

    /**
     * 查询当前用户已安装或系统隐式安装的能力。
     *
     * <p>显式安装来自 {@code user_skill_install}，隐式安装包括内置能力、默认启用能力和 mandatory 能力。
     * 返回结果中的 installed 与 enabled 分开表达：installed 表示用户拥有该能力，enabled 表示当前会话会不会注入。</p>
     *
     * @param userId 请求头中的用户 id
     * @param owner 预留安装维度参数，当前不参与计算
     * @param sessionId 可选会话 id
     * @return 当前用户可在“已安装/我的能力”中看到的能力列表
     */
    @GetMapping("/skills/capabilities/installed")
    @Operation(summary = "查询当前用户已安装能力")
    public ResponseEntity<List<CapabilityDto>> installed(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String sessionId) {
        // owner 是为后续 user/workspace 安装维度预留的参数；当前实现只使用 X-User-Id。
        return ResponseEntity.ok(capabilityService.installed(userId, sessionId));
    }

    /**
     * 为当前用户安装一个能力。
     *
     * <p>安装动作会创建或更新 {@code user_skill_install} 记录。它不会直接执行 MCP 安装命令、
     * 不会启动 CLI 进程，也不会马上调用工具；真正是否注入还要看会话开关、角色和全局 enabled 状态。</p>
     *
     * @param userId 请求头中的用户 id
     * @param sessionId 可选会话 id，用于返回安装后该会话视角的 enabled 状态
     * @param skillKey 能力稳定 key，例如 time、mcp:tavily、cli:git
     * @param request 安装配置请求体，可为空
     * @return 安装后的能力 DTO
     */
    // skillKey 改为查询参数而不是路径段：很多全网 MCP 的 key 含有斜杠（例如 mcp:ai.smithery/xxx），
    // 放在路径里前端 encodeURIComponent 会变成 %2F，被 Tomcat 默认拒绝（400），导致安装静默失败。
    @PostMapping("/skills/capabilities/install")
    @Operation(summary = "安装能力")
    public ResponseEntity<CapabilityDto> install(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam String skillKey,
            @RequestBody(required = false) CapabilityInstallRequest request) {
        return ResponseEntity.ok(capabilityService.install(userId, sessionId, skillKey, request));
    }

    /**
     * 卸载当前用户的一个能力。
     *
     * <p>卸载只删除用户安装关系，不删除全局 skill 目录项。mandatory 能力不能卸载；如果传入 sessionId，
     * 服务层还会清掉该会话对这个能力的显式开关，避免出现卸载后仍残留会话状态。</p>
     *
     * @param userId 请求头中的用户 id
     * @param sessionId 可选会话 id
     * @param owner 预留安装维度参数，当前不参与计算
     * @param skillKey 能力稳定 key
     * @return 204 表示卸载完成
     */
    @DeleteMapping("/skills/capabilities/install")
    @Operation(summary = "卸载能力")
    public ResponseEntity<Void> uninstall(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String owner,
            @RequestParam String skillKey) {
        // 卸载是用户级动作；如果传了 sessionId，服务层同时清掉该会话上的显式开关记录。
        capabilityService.uninstall(userId, sessionId, skillKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * 查询某个会话可展示和可切换的能力列表。
     *
     * <p>这个接口给会话设置面板使用。返回值不是“全市场”，而是当前用户已经安装或系统隐式安装的能力集合；
     * 每一项的 enabled 字段代表下一次聊天请求中是否会被解析进工具集。</p>
     *
     * @param userId 请求头中的用户 id
     * @param sessionId 会话 id
     * @return 会话能力开关列表
     */
    @GetMapping("/sessions/{sessionId}/skills")
    @Operation(summary = "查询会话能力开关")
    public ResponseEntity<List<CapabilityDto>> sessionSkills(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable String sessionId) {
        return ResponseEntity.ok(capabilityService.sessionSkills(userId, sessionId));
    }

    /**
     * 设置某个会话中某个能力是否启用。
     *
     * <p>该操作写入 {@code session_skill_setting}，只影响当前会话，不会修改用户安装关系，也不会修改全局默认值。
     * 关闭能力后，下一次聊天请求解析工具集时会读取这个显式设置并停止注入该能力；mandatory 能力仍然不能关闭。</p>
     *
     * @param userId 请求头中的用户 id
     * @param sessionId 会话 id
     * @param skillKey 能力稳定 key
     * @param request 请求体，只包含 enabled 布尔值
     * @return 设置后的能力 DTO
     */
    @PutMapping("/sessions/{sessionId}/skills")
    @Operation(summary = "设置会话能力开关")
    public ResponseEntity<CapabilityDto> toggleSessionSkill(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable String sessionId,
            @RequestParam String skillKey,
            @RequestBody SessionSkillToggleRequest request) {
        // 会话开关不会创建真实执行器，也不会安装系统依赖；它只影响下一次聊天请求解析出的能力集合。
        return ResponseEntity.ok(capabilityService.setSessionEnabled(userId, sessionId, skillKey, request.enabled()));
    }
}
