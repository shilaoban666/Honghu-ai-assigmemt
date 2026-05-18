package com.honghu.ut.test.ai.assigment.testdeepseekr1.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
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
        log.info("【操作系统信息】：操作系统名称: {},操作系统版本: {}, 操作系统架构: {}, 可用处理器数: {}", osBean.getName(), osBean.getVersion(), osBean.getArch(), osBean.getAvailableProcessors());
    }

    /**
     * 打印内存使用信息
     */
    private void printMemoryInfo() {
        log.info("【内存使用情况】");

        MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
        log.info("  堆内存: {}", formatMemoryUsage(heapMemory));

        MemoryUsage nonHeapMemory = memoryBean.getNonHeapMemoryUsage();
        log.info("  非堆内存: {}", formatMemoryUsage(nonHeapMemory));

        long totalUsed = safeAdd(heapMemory.getUsed(), nonHeapMemory.getUsed());
        long totalMax = safeAdd(heapMemory.getMax(), nonHeapMemory.getMax());
        log.info("  总内存: 已使用: {} MB, 最大值: {} MB, 使用率: {}%",
                bytesToMB(totalUsed),
                bytesToMB(totalMax),
                calculatePercentage(totalUsed, totalMax));
    }

    /**
     * 打印线程信息
     */
    private void printThreadInfo() {
        log.info("【线程信息】：当前线程数: {}, 峰值线程数: {}, 守护线程数: {}, 总启动线程数: {}",
                threadBean.getThreadCount(),
                threadBean.getPeakThreadCount(),
                threadBean.getDaemonThreadCount(),
                threadBean.getTotalStartedThreadCount());
    }

    /**
     * 打印CPU使用信息
     */
    private void printCpuInfo() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                log.info("【CPU信息】：可用处理器数: {}, 系统CPU负载: {}%, JVM进程CPU负载: {}%, JVM CPU时间: {} ms",
                        osBean.getAvailableProcessors(),
                        formatCpuLoad(sunOsBean.getCpuLoad()),
                        formatCpuLoad(sunOsBean.getProcessCpuLoad()),
                        sunOsBean.getProcessCpuTime() / 1_000_000);
            } else {
                log.info("【CPU信息】：可用处理器数: {}, CPU负载信息: 无法获取（需要Sun JVM实现）",
                        osBean.getAvailableProcessors());
            }
        } catch (Exception e) {
            log.warn("【CPU信息】：可用处理器数: {}, 无法获取CPU详细信息: {}",
                    osBean.getAvailableProcessors(),
                    e.getMessage());
        }
    }

    /**
     * 将一段内存使用信息压缩成一行日志，避免启动时日志过于竖向拉长。
     */
    private String formatMemoryUsage(MemoryUsage memoryUsage) {
        return String.format("初始大小: %s MB, 已使用: %s MB, 已提交: %s MB, 最大值: %s MB, 使用率: %s%%",
                bytesToMB(memoryUsage.getInit()),
                bytesToMB(memoryUsage.getUsed()),
                bytesToMB(memoryUsage.getCommitted()),
                bytesToMB(memoryUsage.getMax()),
                calculatePercentage(memoryUsage.getUsed(), memoryUsage.getMax()));
    }

    /**
     * 统一格式化 CPU 负载；某些 JVM 在取不到值时会返回负数，这里直接显示为 N/A。
     */
    private String formatCpuLoad(double load) {
        if (load < 0) {
            return "N/A";
        }
        return df.format(load * 100);
    }

    /**
     * 对可能出现 -1 的 max 值做安全相加；只要任一端不可用，就返回 -1 表示总量未知。
     */
    private long safeAdd(long left, long right) {
        if (left < 0 || right < 0) {
            return -1;
        }
        return left + right;
    }

    /**
     * 字节转MB
     */
    private String bytesToMB(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }
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