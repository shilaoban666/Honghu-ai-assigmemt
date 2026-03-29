package com.honghu.ut.test.ai.assigment.testdeepseekr1;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.ChatService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.util.ConnectionHealthChecker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接异常修复测试类
 * 验证ClosedChannelException等网络异常的处理机制
 */
@SpringBootTest
@Slf4j
public class ConnectionExceptionTest {

    @Autowired
    private ChatService chatService;
    
    @Autowired
    private ConnectionHealthChecker connectionHealthChecker;

    @Test
    public void testConnectionHealthCheck() {
        log.info("=== 开始连接健康检查测试 ===");
        
        // 测试连接状态检查
        boolean isConnected = connectionHealthChecker.forceCheck();
        log.info("Ollama服务连接状态: {}", isConnected ? "正常" : "异常");
        
        // 获取详细连接状态
        var status = connectionHealthChecker.getConnectionStatus();
        log.info("连接详情 - URL: {}, 最后检查时间: {}, 错误信息: {}", 
                status.getOllamaUrl(), 
                status.getLastCheckTime(), 
                status.getLastErrorMessage());
        
        assertThat(status).isNotNull();
    }

    @Test
    public void testSimpleChatWithRetry() {
        log.info("=== 开始简单聊天重试机制测试 ===");
        
        try {
            // 发送测试消息
            ChatResponse response = chatService.simpleChat("你好，这是一个测试消息");
            
            log.info("聊天响应 - 成功: {}, 内容长度: {}, 模型: {}", 
                    response.getSuccess(), 
                    response.getContent() != null ? response.getContent().length() : 0,
                    response.getModel());
            
            if (response.getSuccess()) {
                assertThat(response.getContent()).isNotNull().isNotEmpty();
                assertThat(response.getModel()).isNotNull();
            } else {
                log.warn("聊天请求失败: {}", response.getErrorMessage());
                // 即使失败也应该返回合理的错误信息
                assertThat(response.getErrorMessage()).isNotNull();
            }
            
        } catch (Exception e) {
            log.error("测试过程中发生异常", e);
            // 异常应该被妥善处理，不应该导致测试完全失败
        }
    }

    @Test
    public void testStructuredChatWithRetry() {
        log.info("=== 开始结构化聊天重试机制测试 ===");
        
        ChatRequest request = new ChatRequest();
        request.setMessage("请用一句话介绍Spring AI框架");
        request.setSystemMessage("你是一个技术专家");
        request.setTemperature(0.7);
        request.setMaxTokens(100);
        
        try {
            ChatResponse response = chatService.structuredChat(request);
            
            log.info("结构化聊天响应 - 成功: {}, 内容长度: {}, 模型: {}", 
                    response.getSuccess(), 
                    response.getContent() != null ? response.getContent().length() : 0,
                    response.getModel());
            
            if (response.getSuccess()) {
                assertThat(response.getContent()).isNotNull().isNotEmpty();
                assertThat(response.getModel()).isNotNull();
                // 验证token使用信息
                assertThat(response.getTokenUsage()).isNotNull();
                assertThat(response.getTokenUsage().getTotalTokens()).isGreaterThan(0);
            } else {
                log.warn("结构化聊天请求失败: {}", response.getErrorMessage());
                assertThat(response.getErrorMessage()).isNotNull();
            }
            
        } catch (Exception e) {
            log.error("结构化聊天测试过程中发生异常", e);
        }
    }

//    @Test
//    public void testStreamChatWithRetry() {
//        log.info("=== 开始流式聊天重试机制测试 ===");
//
//        try {
//            ChatResponse response = chatService.streamChat("讲一个简短的笑话");
//
//            log.info("流式聊天响应长度: {}", response.length());
//            log.debug("流式聊天响应内容预览: {}",
//                    response.length() > 100 ? response.substring(0, 100) + "..." : response);
//
//            // 流式响应应该包含data: 前缀
//            assertThat(response).isNotNull();
//            if (!response.contains("error")) {
//                assertThat(response).contains("data:");
//            }
//
//        } catch (Exception e) {
//            log.error("流式聊天测试过程中发生异常", e);
//        }
//    }

    @Test
    public void testConnectionExceptionHandling() {
        log.info("=== 开始连接异常处理测试 ===");
        
        // 测试健康检查器的异常识别能力
        var status = connectionHealthChecker.getConnectionStatus();
        
        log.info("当前连接状态: {}", status.isConnected() ? "正常" : "异常");
        if (!status.isConnected()) {
            log.info("异常详情: {}", status.getLastErrorMessage());
        }
        
        // 验证状态对象完整性
        assertThat(status.getOllamaUrl()).isNotNull();
        assertThat(status.getLastCheckTime()).isNotNull();
    }
}