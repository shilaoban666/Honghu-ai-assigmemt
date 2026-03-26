package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.LoginRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.UserResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ResponseEntity<User> getUser(
            @Parameter(description = "用户 ID") @PathVariable String userId) {
        log.info("收到查询用户请求：{}", userId);
        User user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
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
     * 用户登录
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "用户名密码登录，返回用户信息（不含密码）")
    public ResponseEntity<UserResponse> login(@RequestBody LoginRequest loginRequest) {
        log.info("收到用户登录请求：{}", loginRequest.getUsername());
        
        // 验证用户名密码
        User user = userService.authenticate(loginRequest.getUsername(), loginRequest.getPassword());
        
        // 转换为不包含密码的响应
        UserResponse response = UserResponse.fromUser(user);
        
        log.info("用户 {} 登录成功", loginRequest.getUsername());
        return ResponseEntity.ok(response);
    }
}
