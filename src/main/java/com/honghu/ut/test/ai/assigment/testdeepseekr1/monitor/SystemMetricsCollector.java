package com.honghu.ut.test.ai.assigment.testdeepseekr1.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.text.DecimalFormat;

/**
 * 系统指标收集器
 * 收集JVM和系统级别的各种指标信息
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Slf4j
@Component
public class SystemMetricsCollector {

    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    private final DecimalFormat df = new DecimalFormat("#.##");

    public SystemMetricsCollector() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
    }

    /**
     * 收集并打印所有系统指标
     */
    public void collectAndPrintMetrics() {
        log.info("=====================================");
        log.info("     系统指标监控报告");
        log.info("=====================================");
        
        // 打印操作系统信息
        printOperatingSystemInfo();
        
        // 打印JVM内存信息
        printMemoryInfo();
        
        // 打印线程信息
        printThreadInfo();
        
        // 打印CPU信息
        printCpuInfo();
        
        log.info("=====================================");
    }

    /**
     * 打印操作系统基本信息
     */
    private void printOperatingSystemInfo() {
        log.info("【操作系统信息】：操作系统名称: {},操作系统版本: {}, 操作系统架构: {}, 可用处理器数: {}",osBean.getName(),osBean.getVersion(),osBean.getArch(),osBean.getAvailableProcessors());
    }

    /**
     * 打印内存使用信息
     */
    private void printMemoryInfo() {
        log.info("【内存使用情况】");
        
        // 堆内存信息
        var heapMemory = memoryBean.getHeapMemoryUsage();
        log.info("  堆内存: 初始大小: {} MB,已使用: {} MB,已提交: {} MB,最大值: {} MB, 使用率: {}%",bytesToMB(heapMemory.getInit()),bytesToMB(heapMemory.getUsed()),bytesToMB(heapMemory.getCommitted()),bytesToMB(heapMemory.getMax()),calculatePercentage(heapMemory.getUsed(), heapMemory.getMax()));

        // 非堆内存信息
        var nonHeapMemory = memoryBean.getNonHeapMemoryUsage();
        log.info("  非堆内存:");
        log.info("    初始大小: {} MB", bytesToMB(nonHeapMemory.getInit()));
        log.info("    已使用: {} MB", bytesToMB(nonHeapMemory.getUsed()));
        log.info("    已提交: {} MB", bytesToMB(nonHeapMemory.getCommitted()));
        log.info("    最大值: {} MB", bytesToMB(nonHeapMemory.getMax()));
        log.info("    使用率: {}%", calculatePercentage(nonHeapMemory.getUsed(), nonHeapMemory.getMax()));

        // 总内存使用
        long totalUsed = heapMemory.getUsed() + nonHeapMemory.getUsed();
        long totalMax = heapMemory.getMax() + nonHeapMemory.getMax();
        log.info("  总内存使用率: {}%", calculatePercentage(totalUsed, totalMax));
    }

    /**
     * 打印线程信息
     */
    private void printThreadInfo() {
        log.info("【线程信息】");
        log.info("  当前线程数: {}", threadBean.getThreadCount());
        log.info("  峰值线程数: {}", threadBean.getPeakThreadCount());
        log.info("  守护线程数: {}", threadBean.getDaemonThreadCount());
        log.info("  总启动线程数: {}", threadBean.getTotalStartedThreadCount());
        
        // 线程状态分布（简化版本）
        log.info("  线程状态详情:");
        log.info("    当前活跃线程数: {}", threadBean.getThreadCount());
        log.info("    守护线程数: {}", threadBean.getDaemonThreadCount());
        // 注：标准ThreadMXBean不提供详细的线程状态统计，需要更复杂的实现
    }

    /**
     * 打印CPU使用信息
     */
    private void printCpuInfo() {
        log.info("【CPU信息】");
        log.info("  可用处理器数: {}", osBean.getAvailableProcessors());
        
        try {
            // 尝试获取更详细的CPU信息（需要特定的MXBean实现）
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                log.info("  系统CPU负载: {}%", df.format(sunOsBean.getSystemCpuLoad() * 100));
                log.info("  JVM进程CPU负载: {}%", df.format(sunOsBean.getProcessCpuLoad() * 100));
                log.info("  JVM CPU时间: {} ms", sunOsBean.getProcessCpuTime() / 1_000_000);
            } else {
                log.info("  CPU负载信息: 无法获取（需要Sun JVM实现）");
            }
        } catch (Exception e) {
            log.warn("  无法获取CPU详细信息: {}", e.getMessage());
        }
    }

    /**
     * 字节转MB
     */
    private String bytesToMB(long bytes) {
        return df.format(bytes / (1024.0 * 1024.0));
    }

    /**
     * 计算百分比
     */
    private String calculatePercentage(long used, long max) {
        if (max <= 0) return "N/A";
        return df.format((double) used / max * 100);
    }

    /**
     * 获取系统指标数据对象（用于API返回）
     */
    public SystemMetrics getMetrics() {
        return SystemMetrics.builder()
                .availableProcessors(osBean.getAvailableProcessors())
                .heapMemoryUsed(bytesToMB(memoryBean.getHeapMemoryUsage().getUsed()))
                .heapMemoryMax(bytesToMB(memoryBean.getHeapMemoryUsage().getMax()))
                .heapMemoryUsage(calculatePercentage(
                        memoryBean.getHeapMemoryUsage().getUsed(),
                        memoryBean.getHeapMemoryUsage().getMax()))
                .nonHeapMemoryUsed(bytesToMB(memoryBean.getNonHeapMemoryUsage().getUsed()))
                .nonHeapMemoryMax(bytesToMB(memoryBean.getNonHeapMemoryUsage().getMax()))
                .nonHeapMemoryUsage(calculatePercentage(
                        memoryBean.getNonHeapMemoryUsage().getUsed(),
                        memoryBean.getNonHeapMemoryUsage().getMax()))
                .threadCount(threadBean.getThreadCount())
                .peakThreadCount(threadBean.getPeakThreadCount())
                .daemonThreadCount(threadBean.getDaemonThreadCount())
                .totalStartedThreadCount(threadBean.getTotalStartedThreadCount())
                .build();
    }
}