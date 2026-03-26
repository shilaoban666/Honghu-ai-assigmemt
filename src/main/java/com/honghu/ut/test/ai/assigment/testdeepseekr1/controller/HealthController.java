package com.honghu.ut.test.ai.assigment.testdeepseekr1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@RestController
@RequestMapping("/health")
@Tag(name = "健康检查", description = "应用健康状态检查接口")
public class HealthController {

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.ai.default-model:unknown}")
    private String defaultModel;

    /**
     * 应用健康检查
     */
    @GetMapping
    @Operation(summary = "健康检查", description = "检查应用运行状态")
    @ApiResponse(responseCode = "200", description = "应用运行正常")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> healthInfo = new HashMap<>();
        healthInfo.put("status", "UP");
        healthInfo.put("appName", appName);
        healthInfo.put("timestamp", LocalDateTime.now());
        healthInfo.put("aiModel", defaultModel);
        healthInfo.put("message", "AI聊天服务运行正常");
        
        return ResponseEntity.ok(healthInfo);
    }

    /**
     * 简单的ping接口
     */
    @GetMapping("/ping")
    @Operation(summary = "Ping测试", description = "简单的连通性测试")
    @ApiResponse(responseCode = "200", description = "服务可访问")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }
}