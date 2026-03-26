package com.honghu.ut.test.ai.assigment.testdeepseekr1.exception;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

/**
 * 全局异常处理器
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ChatResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        
        log.warn("参数校验失败: {}", errorMessage);
        return ResponseEntity.badRequest()
                .body(ChatResponse.error("参数校验失败: " + errorMessage));
    }

    /**
     * 处理绑定异常
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ChatResponse> handleBindException(BindException ex) {
        String errorMessage = ex.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        
        log.warn("参数绑定失败: {}", errorMessage);
        return ResponseEntity.badRequest()
                .body(ChatResponse.error("参数绑定失败: " + errorMessage));
    }

    /**
     * 处理通用异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatResponse> handleGenericException(Exception ex) {
        log.error("系统异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ChatResponse.error("系统内部错误: " + ex.getMessage()));
    }

    /**
     * 处理AI服务异常
     */
    @ExceptionHandler(AIServiceException.class)
    public ResponseEntity<ChatResponse> handleAIServiceException(AIServiceException ex) {
        log.error("AI服务异常: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ChatResponse.error("AI服务不可用: " + ex.getMessage()));
    }
    
    /**
     * 处理连接被关闭异常（ClosedChannelException）
     */
    @ExceptionHandler(ClosedChannelException.class)
    public ResponseEntity<ChatResponse> handleClosedChannelException(ClosedChannelException ex) {
        log.warn("连接被关闭异常: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ChatResponse.error("网络连接异常，请稍后重试: 连接已被关闭"));
    }
    
    /**
     * 处理连接拒绝异常
     */
    @ExceptionHandler(ConnectException.class)
    public ResponseEntity<ChatResponse> handleConnectException(ConnectException ex) {
        log.warn("连接拒绝异常: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ChatResponse.error("无法连接到AI服务，请检查服务状态: " + ex.getMessage()));
    }
    
    /**
     * 处理网络IO异常
     */
    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<ChatResponse> handleIoException(java.io.IOException ex) {
        String message = ex.getMessage();
        if (message != null && (message.contains("ClosedChannel") || message.contains("Connection reset"))) {
            log.warn("网络IO异常（连接相关）: {}", message);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ChatResponse.error("网络连接不稳定，请稍后重试"));
        }
        
        log.error("IO异常: {}", message, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ChatResponse.error("系统IO错误: " + message));
    }
    
    /**
     * 处理静态资源未找到异常
     * 主要解决favicon.ico 404问题
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFoundException(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        // 对于favicon.ico请求，返回空响应而不是错误日志
        if (ex.getResourcePath().contains("favicon.ico")) {
            log.debug("忽略favicon.ico资源未找到请求");
            return ResponseEntity.notFound().build();
        }
        
        // 其他静态资源404仍然记录警告日志
        log.warn("静态资源未找到: {}", ex.getResourcePath());
        return ResponseEntity.notFound().build();
    }
}