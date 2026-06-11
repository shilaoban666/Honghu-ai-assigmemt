package com.honghu.ai.assigment.controller;

import com.honghu.ai.assigment.monitor.SystemMetrics;
import com.honghu.ai.assigment.monitor.SystemMetricsCollector;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统监控控制器
 * 提供系统指标查询接口
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
@Tag(name = "系统监控", description = "系统指标监控接口")
public class MonitorController {

    private final SystemMetricsCollector metricsCollector;

    /**
     * 获取实时系统指标
     */
    @GetMapping("/metrics")
    @Operation(summary = "获取系统指标", description = "获取当前系统的各项指标信息")
    public ResponseEntity<SystemMetrics> getSystemMetrics() {
        try {
            SystemMetrics metrics = metricsCollector.getMetrics();
            log.info("返回系统指标数据");
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("获取系统指标失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取系统概览信息
     */
    @GetMapping("/overview")
    @Operation(summary = "获取系统概览", description = "获取系统基本信息和运行状态")
    public ResponseEntity<Map<String, Object>> getSystemOverview() {
        Map<String, Object> overview = new HashMap<>();
        
        try {
            // 基本信息
            overview.put("applicationName", "testDeepseekR1");
            overview.put("timestamp", LocalDateTime.now());
            overview.put("status", "RUNNING");
            
            // 系统指标
            SystemMetrics metrics = metricsCollector.getMetrics();
            overview.put("cpuProcessors", metrics.getAvailableProcessors());
            overview.put("heapUsage", metrics.getHeapMemoryUsage() + "%");
            overview.put("activeThreads", metrics.getThreadCount());
            overview.put("daemonThreads", metrics.getDaemonThreadCount());
            
            log.info("返回系统概览信息");
            return ResponseEntity.ok(overview);
            
        } catch (Exception e) {
            log.error("获取系统概览失败", e);
            overview.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(overview);
        }
    }

    /**
     * 触发重新收集指标
     */
    @GetMapping("/refresh")
    @Operation(summary = "刷新指标", description = "重新收集并显示系统指标")
    public ResponseEntity<String> refreshMetrics() {
        try {
            metricsCollector.collectAndPrintMetrics();
            return ResponseEntity.ok("指标已重新收集并打印到日志");
        } catch (Exception e) {
            log.error("刷新指标失败", e);
            return ResponseEntity.internalServerError().body("刷新失败: " + e.getMessage());
        }
    }
}