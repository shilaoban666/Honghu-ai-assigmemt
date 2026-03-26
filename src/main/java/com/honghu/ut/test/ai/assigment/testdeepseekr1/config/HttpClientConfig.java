package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/**
 * HTTP客户端配置类
 * 解决ClosedChannelException等网络连接问题
 */
@Slf4j
@Configuration
public class HttpClientConfig {

    /**
     * 配置RestTemplate Bean，包含连接池和超时设置
     * 
     * 主要解决的问题：
     * 1. ClosedChannelException - 连接被意外关闭
     * 2. 连接超时问题
     * 3. 连接池管理不当
     * 4. 长时间等待响应的场景
     */
    @Bean
    public RestTemplate restTemplate() {
        // 配置OkHttp客户端（已有的依赖）
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(
                        100,                      // 最大连接数
                        30, TimeUnit.SECONDS))    // 连接最大空闲时间
                .connectTimeout(60, TimeUnit.SECONDS)    // 连接超时
                .readTimeout(5, TimeUnit.MINUTES)        // 读取超时
                .writeTimeout(60, TimeUnit.SECONDS)      // 写入超时
                .retryOnConnectionFailure(true)          // 连接失败时重试
                .build();

        OkHttp3ClientHttpRequestFactory factory = new OkHttp3ClientHttpRequestFactory(okHttpClient);
        
        log.info("初始化RestTemplate配置完成 - 连接池大小: 100, 连接超时: 60秒, 响应超时: 5分钟");

        return new RestTemplate(factory);
    }

    /**
     * 配置专用的Ollama RestTemplate
     * 针对AI模型推理的特点进行优化
     */
    @Bean("ollamaRestTemplate")
    public RestTemplate ollamaRestTemplate() {
        // 为AI推理场景优化的OkHttp客户端
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(
                        50,                       // AI推理通常并发较低
                        60, TimeUnit.SECONDS))    // 更长的空闲时间
                .connectTimeout(120, TimeUnit.SECONDS)   // 更长的连接超时
                .readTimeout(10, TimeUnit.MINUTES)       // 更长的读取超时
                .writeTimeout(120, TimeUnit.SECONDS)     // 更长的写入超时
                .retryOnConnectionFailure(true)
                .build();

        OkHttp3ClientHttpRequestFactory factory = new OkHttp3ClientHttpRequestFactory(okHttpClient);
        
        log.info("初始化Ollama专用RestTemplate配置完成 - 专为AI推理优化");

        return new RestTemplate(factory);
    }
}