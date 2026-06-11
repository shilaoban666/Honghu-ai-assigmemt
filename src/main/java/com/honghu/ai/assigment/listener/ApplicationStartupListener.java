package com.honghu.ai.assigment.listener;

import com.honghu.ai.assigment.monitor.SystemMetricsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动完成监听器
 * 在Spring Boot应用完全启动后执行系统指标收集
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationStartupListener implements ApplicationListener<ApplicationReadyEvent> {

    private final SystemMetricsCollector metricsCollector;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("应用启动完成，开始收集系统指标...");
        
        try {
            // 延迟一小段时间确保所有bean都已初始化
            Thread.sleep(1000);
            
            // 收集并打印系统指标
            metricsCollector.collectAndPrintMetrics();
            
            log.info("系统指标收集完成！");
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("系统指标收集被中断", e);
        } catch (Exception e) {
            log.error("收集系统指标时发生错误", e);
        }
    }
}