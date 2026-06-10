package com.honghu.ai.assigment.config;

import com.honghu.ai.assigment.config.properties.AsyncExecutionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务线程池配置。
 *
 * <p>把中期记忆压缩放到独立线程池，避免与 Web 请求线程相互挤压。</p>
 *
 * <p>这里不直接写死线程池参数，而是统一从配置文件读取，
 * 这样不同环境可以按机器核数、流量规模、模型耗时来调优线程池容量。</p>
 */
@Configuration
@EnableAsync
public class AsyncExecutionConfig {

    private final AsyncExecutionProperties asyncExecutionProperties;

    public AsyncExecutionConfig(AsyncExecutionProperties asyncExecutionProperties) {
        this.asyncExecutionProperties = asyncExecutionProperties;
    }

    @Bean(name = "memorySummaryTaskExecutor")
    public Executor memorySummaryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(asyncExecutionProperties.getThreadNamePrefix());
        executor.setCorePoolSize(asyncExecutionProperties.getCorePoolSize());
        executor.setMaxPoolSize(asyncExecutionProperties.getMaxPoolSize());
        executor.setQueueCapacity(asyncExecutionProperties.getQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(asyncExecutionProperties.isWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(asyncExecutionProperties.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }
}

