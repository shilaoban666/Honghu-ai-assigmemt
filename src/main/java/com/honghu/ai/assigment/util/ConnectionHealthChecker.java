package com.honghu.ai.assigment.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;

/**
 * 连接健康检查工具类
 * 定期检查Ollama服务的连接状态，预防ClosedChannelException等问题
 */
@Slf4j
@Component
public class ConnectionHealthChecker {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    
    @Value("${app.ai.connection.health-check-interval:30000}") // 默认30秒检查一次
    private long healthCheckInterval;
    
    private volatile Boolean lastCheckSuccess;
    private volatile LocalDateTime lastCheckTime;
    private volatile String lastErrorMessage = "";

    /**
     * 启动完成后立即做一次轻量探测，提前把“本地 Ollama 是否可用”的真实状态加载到内存里。
     *
     * <p>这样首个请求在模型路由时就能拿到更准确的结果，避免第一次请求才发现本地端口没开。</p>
     */
    @PostConstruct
    public void initializeStatus() {
        forceCheck();
    }

    /**
     * 定时健康检查任务
     * 每30秒检查一次Ollama服务连接状态
     */
//    @Scheduled(fixedRateString = "${app.ai.connection.health-check-interval:30000}")
    public void checkConnectionHealth() {
        try {
            boolean isConnected = checkOllamaConnection();
            
            if (lastCheckSuccess == null || isConnected != lastCheckSuccess) {
                if (isConnected) {
                    log.info("✅ Ollama服务连接恢复正常");
                } else {
                    log.warn("❌ Ollama服务连接异常: {}", lastErrorMessage);
                }
                lastCheckSuccess = isConnected;
            }
            
            lastCheckTime = LocalDateTime.now();
            
        } catch (Exception e) {
            log.error("健康检查执行异常", e);
            lastCheckSuccess = false;
            lastErrorMessage = e.getMessage();
            lastCheckTime = LocalDateTime.now();
        }
    }

    /**
     * 检查Ollama服务连接状态
     * 
     * @return true表示连接正常，false表示连接异常
     */
    public boolean checkOllamaConnection() {
        try {
            // 解析URL获取主机和端口
            String host = "localhost";
            int port = 11434;
            
            if (ollamaBaseUrl.startsWith("http://")) {
                String address = ollamaBaseUrl.substring(7);
                if (address.contains(":")) {
                    String[] parts = address.split(":");
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                }
            }

            // 尝试建立TCP连接
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000); // 5秒超时
                log.debug("Ollama服务连接检查成功: {}:{}", host, port);
                return true;
            }
            
        } catch (IOException e) {
            lastErrorMessage = e.getMessage();
            log.debug("Ollama服务连接检查失败: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            lastErrorMessage = e.getMessage();
            log.warn("Ollama服务连接检查出现未知错误: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取当前连接状态
     * 
     * @return 连接状态信息
     */
    public ConnectionStatus getConnectionStatus() {
        return ConnectionStatus.builder()
                .connected(Boolean.TRUE.equals(lastCheckSuccess))
                .lastCheckTime(lastCheckTime)
                .lastErrorMessage(lastErrorMessage)
                .ollamaUrl(ollamaBaseUrl)
                .build();
    }

    /**
     * 获取当前本地 Ollama 是否可用。
     *
     * <p>若缓存状态还未初始化，或距离上次检查时间已经超过配置间隔，
     * 会自动触发一次实时探测，避免一直使用过期结果。</p>
     */
    public boolean isOllamaAvailable() {
        LocalDateTime now = LocalDateTime.now();
        if (lastCheckSuccess == null || lastCheckTime == null) {
            return forceCheck();
        }

        long elapsedMillis = java.time.Duration.between(lastCheckTime, now).toMillis();
        if (elapsedMillis >= healthCheckInterval) {
            return forceCheck();
        }
        return Boolean.TRUE.equals(lastCheckSuccess);
    }

    /**
     * 强制执行一次连接检查
     * 
     * @return 检查结果
     */
    public boolean forceCheck() {
        boolean result = checkOllamaConnection();
        lastCheckSuccess = result;
        lastCheckTime = LocalDateTime.now();
        return result;
    }

    /**
     * 连接状态数据传输对象
     */
    public static class ConnectionStatus {
        private boolean connected;
        private LocalDateTime lastCheckTime;
        private String lastErrorMessage;
        private String ollamaUrl;

        public static ConnectionStatusBuilder builder() {
            return new ConnectionStatusBuilder();
        }

        // Getters and Setters
        public boolean isConnected() { return connected; }
        public void setConnected(boolean connected) { this.connected = connected; }
        
        public LocalDateTime getLastCheckTime() { return lastCheckTime; }
        public void setLastCheckTime(LocalDateTime lastCheckTime) { this.lastCheckTime = lastCheckTime; }
        
        public String getLastErrorMessage() { return lastErrorMessage; }
        public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
        
        public String getOllamaUrl() { return ollamaUrl; }
        public void setOllamaUrl(String ollamaUrl) { this.ollamaUrl = ollamaUrl; }

        public static class ConnectionStatusBuilder {
            private boolean connected;
            private LocalDateTime lastCheckTime;
            private String lastErrorMessage;
            private String ollamaUrl;

            public ConnectionStatusBuilder connected(boolean connected) {
                this.connected = connected;
                return this;
            }

            public ConnectionStatusBuilder lastCheckTime(LocalDateTime lastCheckTime) {
                this.lastCheckTime = lastCheckTime;
                return this;
            }

            public ConnectionStatusBuilder lastErrorMessage(String lastErrorMessage) {
                this.lastErrorMessage = lastErrorMessage;
                return this;
            }

            public ConnectionStatusBuilder ollamaUrl(String ollamaUrl) {
                this.ollamaUrl = ollamaUrl;
                return this;
            }

            public ConnectionStatus build() {
                ConnectionStatus status = new ConnectionStatus();
                status.setConnected(connected);
                status.setLastCheckTime(lastCheckTime);
                status.setLastErrorMessage(lastErrorMessage);
                status.setOllamaUrl(ollamaUrl);
                return status;
            }
        }
    }
}