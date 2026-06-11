package com.honghu.ai.assigment.monitor;

import lombok.Builder;
import lombok.Data;

/**
 * 系统指标数据传输对象
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Data
@Builder
public class SystemMetrics {
    
    // CPU相关信息
    private int availableProcessors;
    
    // 堆内存信息
    private String heapMemoryUsed;
    private String heapMemoryMax;
    private String heapMemoryUsage;
    
    // 非堆内存信息
    private String nonHeapMemoryUsed;
    private String nonHeapMemoryMax;
    private String nonHeapMemoryUsage;
    
    // 线程信息
    private int threadCount;
    private int peakThreadCount;
    private int daemonThreadCount;
    private long totalStartedThreadCount;
}