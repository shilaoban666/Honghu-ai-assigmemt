package com.honghu.ai.assigment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 技能爬虫统一线程池配置。
 *
 * <p>四个爬虫（官方 MCP Registry、Claude Skills、国内 MCP、中文提示词技能）原本各自
 * {@code new Thread(...)} 在 {@code ApplicationReadyEvent} 后并发启动同步。这样有三个问题：</p>
 * <ul>
 *     <li><b>启动瞬时抢占</b>：4 路 HTTP 拉取 + 批量写库 + LLM 翻译在启动一刻同时发生，
 *     会瞬间占满 Hikari 连接池和 CPU，挤压刚就绪后的首批真实用户请求；</li>
 *     <li><b>不可观测、不可控</b>：裸线程没有命名统一的池、没有有界队列、没有拒绝策略，
 *     某个 registry 卡住时线程一直挂着，也没有随上下文优雅关闭；</li>
 *     <li><b>无背压</b>：将来加更多爬虫只会线性增加并发线程数。</li>
 * </ul>
 *
 * <p>这里用<b>单线程、有界队列</b>的池统一承载所有爬虫的「启动同步」：4 个爬虫的首次同步会
 * 串行排队执行，启动期对数据库/网络的冲击被摊平成一条流水线，而不是一次性的尖峰。
 * 每个爬虫自身仍有 {@code AtomicBoolean running} 防止与定时任务并发，互不冲突。</p>
 *
 * <h3>为什么不用响应式 / 虚拟线程</h3>
 * <ul>
 *     <li><b>响应式（WebFlux/R2DBC）不适用</b>：本应用是 Spring MVC + JPA/Hibernate 的阻塞栈，
 *     爬虫又是一天跑几次、每次几百条的批处理，没有高并发 I/O 问题需要响应式解决；强行引入只会
 *     让 JPA 阻塞调用跑在响应式线程上（反模式），还要改造整个仓储层，收益为负。</li>
 *     <li><b>虚拟线程需要 JDK 21</b>：本项目是 Java 17，{@code spring.threads.virtual.enabled}
 *     不可用。即便升到 21，虚拟线程的价值在「大量并发阻塞 I/O」（如 Web 层 + 出站 LLM 调用），
 *     而不是这种串行批处理；爬虫用一个小的有界平台线程池才是合适粒度。升级到 21 后若想切换，
 *     只需把本 Bean 内部换成 {@code Executors.newVirtualThreadPerTaskExecutor()} 即可，调用方无感知。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class CrawlerExecutionConfig {

    /** 排队上限：足够容纳全部爬虫的启动同步 + 余量，正常情况下不会触发拒绝。 */
    @Value("${app.skill.crawler.executor.queue-capacity:32}")
    private int queueCapacity;

    /** 空闲线程存活秒数：爬虫一天只跑几次，空闲后回收线程，不常驻。 */
    @Value("${app.skill.crawler.executor.keep-alive-seconds:30}")
    private int keepAliveSeconds;

    /**
     * 爬虫统一线程池。
     *
     * <p>核心/最大都为 1，使所有提交进来的爬虫任务串行执行，从根上消除启动并发尖峰。
     * 守护线程 + 允许核心线程超时回收，保证空闲时不占资源、也不阻塞 JVM 退出；
     * 作为 Spring Bean，上下文关闭时会自动 {@code shutdown}。</p>
     */
    @Bean(name = "skillCrawlerExecutor")
    public Executor skillCrawlerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 单线程：4 个爬虫启动同步串行跑，避免一次性打满连接池/CPU。
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        // 空闲超时后连唯一的核心线程也回收，爬虫不跑时零常驻线程。
        executor.setAllowCoreThreadTimeOut(true);
        // 守护 + 统一命名，便于线程栈排查，且不阻止 JVM 退出。
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("skill-crawler-");
        threadFactory.setDaemon(true);
        executor.setThreadFactory(threadFactory);
        // 队列若被压满（极端情况），由提交线程自己执行，形成天然背压而不是丢任务。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 爬虫是后台维护任务，关机时不需要等它跑完，避免拖慢优雅停机。
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("初始化技能爬虫线程池完成 - 单线程串行, 队列容量: {}, 空闲回收: {}s", queueCapacity, keepAliveSeconds);
        return executor;
    }
}
