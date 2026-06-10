package com.honghu.ai.assigment.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring MVC 异步请求（SSE 流式聊天）专用线程池配置。
 *
 * <p>Spring MVC 在处理返回 {@code Flux}/{@code SseEmitter} 这类异步响应时，默认使用
 * {@code SimpleAsyncTaskExecutor}——它<b>每个任务新建一个线程、不复用、不设上限</b>，
 * 高并发下会无限制创建线程，最终拖垮机器。生产环境必须替换成有界线程池。</p>
 *
 * <p>把参数收口到 {@code app.async.mvc.*}，便于按机器规格与流量在不发版的情况下调优。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.async.mvc")
public class MvcAsyncProperties {

    /** 线程名前缀。日志里看到 {@code [mvc-async-3]} 就能立刻定位是 SSE 流式线程。 */
    private String threadNamePrefix = "mvc-async-";

    /**
     * 核心线程数。
     *
     * <p>SSE 流式聊天的线程是 <b>I/O 密集型</b>（绝大多数时间在等上游模型逐字返回），
     * 不是 CPU 密集型，所以核心线程数可以明显大于 CPU 核数。这里按“常态并发会话数”设定。</p>
     */
    private int corePoolSize = 8;

    /** 最大线程数 = 允许的峰值并发流式会话数。超过后进队列，再超过才拒绝。 */
    private int maxPoolSize = 32;

    /**
     * 队列容量。
     *
     * <p>故意设得不大：SSE 线程会被一次会话长时间占用，如果队列很大，突发请求会“排队但毫无响应”，
     * 用户体验是干等。容量适中时，突发会更快地溢出到新线程（直到 maxPoolSize），
     * 超过上限则<b>快速拒绝</b>（由上层转 503）做背压，而不是让客户端无限等待。</p>
     */
    private int queueCapacity = 100;

    /** 空闲线程存活秒数；配合 allowCoreThreadTimeout 在低峰期回收线程，省内存。 */
    private int keepAliveSeconds = 60;

    /** 允许核心线程在空闲超时后也被回收（小机器友好，避免常驻占用）。 */
    private boolean allowCoreThreadTimeout = true;

    /** 应用关闭时等待在途请求完成，避免正在流式输出的会话被硬切断。 */
    private boolean waitForTasksToCompleteOnShutdown = true;

    /** 关闭时最长等待秒数；超过则强制结束，防止部署卡死。 */
    private int awaitTerminationSeconds = 30;

    /**
     * 异步请求默认超时（毫秒）。
     *
     * <p>与 AI 调用读超时（app.ai.connection.read-timeout=600000）和 nginx
     * {@code proxy_read_timeout 600s} 对齐，三者一致才不会出现“一端先超时切断流”的问题。</p>
     */
    private long requestTimeoutMillis = 600000L;
}
