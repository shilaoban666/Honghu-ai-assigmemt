package com.honghu.ai.assigment.controller;

import com.honghu.ai.assigment.dto.AiModelResponse;
import com.honghu.ai.assigment.dto.LoginRequest;
import com.honghu.ai.assigment.dto.QuotaSnapshot;
import com.honghu.ai.assigment.dto.RegisterRequest;
import com.honghu.ai.assigment.dto.UserAvatarResponse;
import com.honghu.ai.assigment.dto.UserResponse;
import com.honghu.ai.assigment.dto.WeChatAuthorizeResponse;
import com.honghu.ai.assigment.dto.WeChatLoginRequest;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.security.jwt.JwtService;
import com.honghu.ai.assigment.service.AiModelAccessService;
import com.honghu.ai.assigment.service.QuotaService;
import com.honghu.ai.assigment.service.UserAvatarService;
import com.honghu.ai.assigment.service.UserService;
import com.honghu.ai.assigment.service.WeChatAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 用户管理控制器
 *
 * @author shilaoban
 * @since 2026-03-11
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "用户的增删改查操作")
public class UserController {

    private final UserService userService;
    private final AiModelAccessService aiModelAccessService;
    private final QuotaService quotaService;
    private final UserAvatarService userAvatarService;
    private final JwtService jwtService;
    private final WeChatAuthService weChatAuthService;

    /**
     * 创建新用户
     */
    @PostMapping
    @Operation(summary = "创建用户", description= "创建一个新的用户账户")
    public ResponseEntity<User> createUser(@RequestBody User user) {
        log.info("收到创建用户请求：{}", user.getUsername());
        User createdUser = userService.createUser(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    /**
     * 根据 ID 查询用户
     */
    @GetMapping("/{userId}")
    @Operation(summary = "查询用户", description= "根据用户 ID 查询用户详情")
    public ResponseEntity<UserResponse> getUser(
            @Parameter(description = "用户 ID") @PathVariable String userId) {
        log.info("收到查询用户请求：{}", userId);
        User user = userService.getUserById(userId);
        return ResponseEntity.ok(withAvatar(UserResponse.fromUser(user), user));
    }

    @PostMapping("/{userId}/avatar")
    @Operation(summary = "上传用户头像", description = "上传裁切后的头像图片到 S3，并更新用户头像元数据")
    public ResponseEntity<UserAvatarResponse> uploadAvatar(
            @Parameter(description = "用户 ID") @PathVariable String userId,
            @RequestParam("file") MultipartFile file) {
        log.info("收到上传用户头像请求：userId={}, fileName={}, size={}", userId, file.getOriginalFilename(), file.getSize());
        return ResponseEntity.ok(userAvatarService.uploadAvatar(userId, file));
    }

    /**
     * 查询当前用户的额度快照。
     *
     * <p>这个接口给普通前台使用，不要求管理员权限。返回 daily / monthly 两个窗口的额度数据，
     * 前端可以直接用 {@link QuotaSnapshot#getTokenUsed()}、{@link QuotaSnapshot#getTokenLimit()}
     * 和 {@link QuotaSnapshot#getTokenRemaining()} 渲染 token 进度条，同时保留金额字段用于对账展示。</p>
     *
     * @param userId 用户 ID
     * @param workspaceId 可选 workspace ID；不传时使用该用户默认 workspace
     * @return 日额度和月额度快照
     */
    @GetMapping("/{userId}/quota")
    @Operation(summary = "查询用户额度快照", description = "返回用户日/月标准 Token 与金额额度，用于前台用户中心展示")
    public ResponseEntity<Map<String, QuotaSnapshot>> getUserQuota(
            @Parameter(description = "用户 ID") @PathVariable String userId,
            @Parameter(description = "可选 workspace ID，不传则使用用户默认 workspace") @RequestParam(required = false) String workspaceId) {
        log.info("收到查询用户额度快照请求：userId={}, workspaceId={}", userId, workspaceId);
        return ResponseEntity.ok(Map.of(
                "daily", quotaService.getSnapshot(userId, workspaceId, "DAILY"),
                "monthly", quotaService.getSnapshot(userId, workspaceId, "MONTHLY")
        ));
    }

    /**
     * 获取所有用户
     */
    @GetMapping
    @Operation(summary = "获取所有用户", description = "获取系统中所有用户列表")
    public ResponseEntity<List<User>> getAllUsers() {
        log.info("收到获取所有用户请求");
        List<User> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    /**
     * 更新用户信息（全量更新）
     */
    @PutMapping("/{userId}")
    @Operation(summary = "更新用户（全量）", description = "更新用户的全部信息")
    public ResponseEntity<User> updateUser(
            @Parameter(description = "用户 ID") @PathVariable String userId,
            @RequestBody User user) {
        log.info("收到更新用户请求：{}", userId);
        User updatedUser = userService.updateUser(userId, user);
        return ResponseEntity.ok(updatedUser);
    }

    /**
     * 更新用户信息（部分更新）
     */
    @PatchMapping("/{userId}")
    @Operation(summary = "更新用户（部分）", description = "只更新用户提供的字段")
    public ResponseEntity<User> patchUpdateUser(
            @Parameter(description = "用户 ID") @PathVariable String userId,
            @RequestBody User user) {
        log.info("收到部分更新用户请求：{}", userId);
        User updatedUser = userService.patchUpdateUser(userId, user);
        return ResponseEntity.ok(updatedUser);
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{userId}")
    @Operation(summary = "删除用户", description = "根据用户 ID 删除用户")
    public ResponseEntity<Void> deleteUser(
            @Parameter(description= "用户 ID") @PathVariable String userId) {
        log.info("收到删除用户请求：{}", userId);
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 根据状态查询用户
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "按状态查询用户", description = "查询指定状态的所有用户")
    public ResponseEntity<List<User>> getUsersByStatus(
            @Parameter(description = "用户状态") @PathVariable User.UserStatus status) {
        log.info("收到按状态查询用户请求：{}", status);
        List<User> users = userService.getUsersByStatus(status);
        return ResponseEntity.ok(users);
    }

    /**
     * 根据昵称搜索用户
     */
    @GetMapping("/search")
    @Operation(summary = "搜索用户", description = "根据昵称模糊搜索用户")
    public ResponseEntity<List<User>> searchUsers(
            @Parameter(description = "昵称关键词") @RequestParam String nickname) {
        log.info("收到搜索用户请求：{}", nickname);
        List<User> users = userService.searchUsersByNickname(nickname);
        return ResponseEntity.ok(users);
    }

    /**
     * 用户登录。
     *
     * <p>返回结果除了基础用户信息，还会包含：</p>
     * <ul>
     *     <li>identity：身份枚举（GUEST / USER / VIP / ADMIN）</li>
     *     <li>identityLabel：身份中文名称</li>
     *     <li>permissionSummary：该身份的模型权限说明</li>
     *     <li>availableModels：本次登录后当前身份真正可用的模型列表</li>
     * </ul>
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "用户名密码登录或游客登录，返回身份、权限摘要、可用模型列表与签名 JWT")
    public ResponseEntity<UserResponse> login(@RequestBody LoginRequest loginRequest) {
        User user;
        if (Boolean.TRUE.equals(loginRequest.getGuestLogin())) {
            log.info("收到游客登录请求");
            user = userService.buildGuestUser();
        } else {
            log.info("收到用户登录请求：{}", loginRequest.getUsername());
            user = userService.authenticate(loginRequest.getUsername(), loginRequest.getPassword());
        }

        UserResponse response = buildLoginResponse(user);
        log.info("身份 {} 登录成功，可用模型数={}", user.getUserRole(), response.getAvailableModels().size());
        return ResponseEntity.ok(response);
    }

    /**
     * 用户注册。
     *
     * <p>独立 DTO + Bean Validation 校验；注册成功后直接返回签名 JWT，前端可“注册即登录”，
     * 不必再单独调一次 login。</p>
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "用户名/密码注册，校验唯一性并 BCrypt 落库，成功后返回身份与 JWT（注册即登录）")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        log.info("收到用户注册请求：{}", registerRequest.getUsername());
        User user = userService.registerUser(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(buildLoginResponse(user));
    }

    /**
     * 获取微信扫码登录的授权信息。
     *
     * <p>前端进入微信登录时先调它,拿到 appId/redirectUri/state/url 去渲染二维码或跳转授权页;
     * state 由后端生成并写 Redis(一次性、5 分钟过期),回调时校验防 CSRF。演示模式返回 mock 标记。</p>
     */
    @GetMapping("/wechat/authorize")
    @Operation(summary = "微信扫码授权信息", description = "返回前端发起扫码所需的 appId/redirectUri/state/url；演示模式返回 mock 标记")
    public ResponseEntity<WeChatAuthorizeResponse> wechatAuthorize() {
        return ResponseEntity.ok(weChatAuthService.buildAuthorize());
    }

    /**
     * 微信扫码登录。
     *
     * <p>前端把扫码授权回跳得到的一次性 code + state POST 上来，后端先校验 state(防 CSRF)，
     * 再换取 openid 并 find-or-create 用户，返回与普通登录一致的结构（含 JWT）。
     * 无真实凭证时走演示模式，详见 WeChatAuthService。</p>
     */
    @PostMapping("/login/wechat")
    @Operation(summary = "微信扫码登录", description = "校验 state 后用微信授权 code 换取 openid 完成登录，返回身份与 JWT")
    public ResponseEntity<UserResponse> wechatLogin(@Valid @RequestBody WeChatLoginRequest request) {
        log.info("收到微信登录请求");
        User user = weChatAuthService.login(request.getCode(), request.getState());
        UserResponse response = buildLoginResponse(user);
        log.info("微信用户登录成功：userId={}", user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * 校验用户名是否已存在（注册前置校验）。
     */
    @GetMapping("/check/username/{username}")
    @Operation(summary = "校验用户名是否存在", description = "注册前置校验，返回 {exists: true|false}")
    public ResponseEntity<Map<String, Boolean>> checkUsername(@PathVariable String username) {
        return ResponseEntity.ok(Map.of("exists", userService.existsByUsername(username)));
    }

    /**
     * 校验手机号是否已存在（注册前置校验）。
     */
    @GetMapping("/check/phone/{phone}")
    @Operation(summary = "校验手机号是否存在", description = "注册前置校验，返回 {exists: true|false}")
    public ResponseEntity<Map<String, Boolean>> checkPhone(@PathVariable String phone) {
        return ResponseEntity.ok(Map.of("exists", userService.existsByPhone(phone)));
    }

    /**
     * 校验邮箱是否已存在（注册前置校验）。
     */
    @GetMapping("/check/email/{email}")
    @Operation(summary = "校验邮箱是否存在", description = "注册前置校验，返回 {exists: true|false}")
    public ResponseEntity<Map<String, Boolean>> checkEmail(@PathVariable String email) {
        return ResponseEntity.ok(Map.of("exists", userService.existsByEmail(email)));
    }

    /**
     * 组装登录响应：身份说明 + 可用模型 + 头像 + 签名 JWT。
     *
     * <p>login / register / 微信登录三处共用，保证返回结构与 token 签发逻辑一致。</p>
     */
    private UserResponse buildLoginResponse(User user) {
        UserResponse response = UserResponse.fromLoginUser(
                user,
                user.getUserRole(),
                aiModelAccessService.resolveIdentityLabel(user.getUserRole()),
                aiModelAccessService.resolvePermissionSummary(user.getUserRole()),
                aiModelAccessService.listAccessibleModels(user).stream()
                        .map(AiModelResponse::fromEntity)
                        .toList()
        );
        response = withAvatar(response, user);
        JwtService.IssuedToken issuedToken = jwtService.issue(user);
        response.setToken(issuedToken.token());
        response.setTokenType(issuedToken.tokenType());
        response.setTokenExpiresAt(issuedToken.expiresAt());
        return response;
    }

    private UserResponse withAvatar(UserResponse response, User user) {
        response.setAvatar(userAvatarService.resolveAvatarUrl(user));
        return response;
    }
}
