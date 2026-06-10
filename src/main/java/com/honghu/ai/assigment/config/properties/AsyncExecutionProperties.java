package com.honghu.ai.assigment.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 中期记忆异步线程池配置。
 *
 * <p>把线程池参数收口到配置文件，主要有两个目的：</p>
 * <ul>
 *     <li>避免把线程池大小等容量参数硬编码在 Java 代码里</li>
 *     <li>便于根据不同环境的机器规格动态调优，而不需要重新发版</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.async.memory-summary")
public class AsyncExecutionProperties {

    /**
     * 线程名前缀。
     *
     * <p>建议保留业务语义，方便在日志、线程 dump、APM 中快速识别该线程池。</p>
     */
    private String threadNamePrefix = "memory-summary-";

    /** 核心线程数。 */
    private int corePoolSize = 2;

    /** 最大线程数。 */
    private int maxPoolSize = 4;

    /** 队列容量。 */
    private int queueCapacity = 200;

    /**
     * 应用关闭时是否等待任务执行完成。
     *
     * <p>对摘要任务这种“可延后但不想轻易丢”的后台任务，通常建议开启。</p>
     */
    private boolean waitForTasksToCompleteOnShutdown = true;

    /** 应用关闭时最长等待秒数。 */
    private int awaitTerminationSeconds = 10;
}

