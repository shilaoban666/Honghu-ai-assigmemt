package com.honghu.ai.assigment.config;

import com.honghu.ai.assigment.config.properties.MvcAsyncProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring MVC 异步请求（SSE 流式聊天）线程池配置。
 *
 * <h3>解决什么问题</h3>
 * <p>结构化流式聊天接口返回 {@code Flux<ChatResponse>}，属于 Spring MVC 异步请求。
 * 默认的 {@code SimpleAsyncTaskExecutor} 每来一个请求就 new 一个线程、用完即弃、没有上限，
 * 启动时 Spring 也会打印 “not suitable for production use under load” 警告。
 * 本类用一个<b>有界、可观测、可优雅关闭</b>的线程池替换它。</p>
 *
 * <h3>为什么这样配（关键点）</h3>
 * <ul>
 *   <li><b>有界（core/max/queue）</b>：杜绝无限建线程导致的 OOM；超载时快速拒绝做背压，而不是拖垮整机。</li>
 *   <li><b>I/O 密集型选型</b>：SSE 线程大部分时间在等上游模型流式返回，故线程数可远大于 CPU 核数。</li>
 *   <li><b>命名线程</b>：日志/线程 dump/APM 里一眼区分流式线程与 Tomcat 请求线程。</li>
 *   <li><b>MDC 透传</b>：把请求线程的日志上下文带入异步线程，保证流式阶段日志仍可按请求关联。</li>
 *   <li><b>拒绝即记录</b>：池满拒绝时打印池状态告警，便于容量定位，而不是静默失败。</li>
 *   <li><b>优雅关闭</b>：部署/重启时等在途流式响应收尾，避免会话被硬切。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class MvcAsyncConfig implements WebMvcConfigurer {

    private final MvcAsyncProperties props;

    public MvcAsyncConfig(MvcAsyncProperties props) {
        this.props = props;
    }

    /**
     * SSE 流式聊天专用线程池。{@link ThreadPoolTaskExecutor} 本身即 {@code AsyncTaskExecutor}。
     */
    @Bean(name = "mvcAsyncTaskExecutor")
    public ThreadPoolTaskExecutor mvcAsyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(props.getThreadNamePrefix());
        executor.setCorePoolSize(props.getCorePoolSize());
        executor.setMaxPoolSize(props.getMaxPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setKeepAliveSeconds(props.getKeepAliveSeconds());
        executor.setAllowCoreThreadTimeOut(props.isAllowCoreThreadTimeout());
        executor.setWaitForTasksToCompleteOnShutdown(props.isWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(props.getAwaitTerminationSeconds());
        // 专业日志①：把请求线程 MDC 透传到异步线程，流式阶段日志仍带同一请求上下文（traceId/requestId 等）。
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 专业日志②：池满被拒时打印池状态并抛异常做背压，绝不静默吞掉。
        executor.setRejectedExecutionHandler(new LoggingAbortPolicy());
        executor.initialize();
        log.info("MVC 异步线程池已初始化：namePrefix={}, core={}, max={}, queueCapacity={}, keepAlive={}s, requestTimeout={}ms",
                props.getThreadNamePrefix(), props.getCorePoolSize(), props.getMaxPoolSize(),
                props.getQueueCapacity(), props.getKeepAliveSeconds(), props.getRequestTimeoutMillis());
        return executor;
    }

    /**
     * 用上面的有界线程池替换 MVC 默认的 SimpleAsyncTaskExecutor，并设置异步请求超时。
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 直接调用 @Bean 方法：@Configuration 类被 CGLIB 增强，这里拿到的是同一个单例线程池。
        configurer.setTaskExecutor(mvcAsyncTaskExecutor());
        configurer.setDefaultTimeout(props.getRequestTimeoutMillis());
    }

    /**
     * 把当前请求线程的 MDC 复制给异步线程，任务结束后还原，避免线程被复用时上下文“串味”。
     */
    static class MdcTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> original = MDC.getCopyOfContextMap();
                if (captured != null) {
                    MDC.setContextMap(captured);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (original != null) {
                        MDC.setContextMap(original);
                    } else {
                        MDC.clear();
                    }
                }
            };
        }
    }

    /**
     * 池满拒绝时：记录线程池实时状态（便于容量评估），再抛出异常交由上层转成 503 背压。
     */
    static class LoggingAbortPolicy implements RejectedExecutionHandler {
        private static final Logger log = LoggerFactory.getLogger(LoggingAbortPolicy.class);

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("MVC 异步线程池已满，拒绝新请求做背压：active={}, poolSize={}, max={}, queueSize={}, completed={}",
                    executor.getActiveCount(), executor.getPoolSize(), executor.getMaximumPoolSize(),
                    executor.getQueue().size(), executor.getCompletedTaskCount());
            throw new RejectedExecutionException("MVC async executor exhausted; rejecting to apply backpressure");
        }
    }
}
