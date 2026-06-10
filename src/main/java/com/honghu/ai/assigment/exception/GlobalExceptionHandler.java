package com.honghu.ai.assigment.exception;

import com.honghu.ai.assigment.dto.ChatResponse;
import com.honghu.ai.assigment.dto.QuotaCheckResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
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
     * 返回结构化的 429 配额错误。
     *
     * <p>这里不复用 ChatResponse，是因为 ChatResponse 更偏向聊天成功/失败语义，
     * 而配额错误需要额外带上 used/limit/resetAt 这些可视化信息。</p>
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceededException(QuotaExceededException ex) {
        /*
         * 配额超限必须返回结构化 429，而不是普通 ChatResponse。
         * 前端需要 dailyUsed、dailyLimit、monthlyUsed、monthlyLimit 和 resetAt
         * 来展示“为什么不能继续用、什么时候恢复”。
         */
        QuotaCheckResult quota = ex.getQuota();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", quota == null ? "QUOTA_EXCEEDED" : quota.getReason());
        body.put("message", "AI quota exceeded");
        body.put("dailyUsed", quota == null ? null : quota.getDailyUsed());
        body.put("dailyLimit", quota == null ? null : quota.getDailyLimit());
        body.put("monthlyUsed", quota == null ? null : quota.getMonthlyUsed());
        body.put("monthlyLimit", quota == null ? null : quota.getMonthlyLimit());
        body.put("resetAt", quota == null ? null : quota.getResetAt());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    /**
     * 返回“模型未配置价格”的结构化错误。
     *
     * <p>选择 503 是为了表达“服务暂时不可正确提供”，并提示这是后台配置问题而不是用户参数问题。</p>
     */
    @ExceptionHandler(PricingNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handlePricingNotConfiguredException(PricingNotConfiguredException ex) {
        /*
         * 模型没有价格时不继续调用，避免产生无法入账的用量。
         * 返回 503 是为了提醒管理员去模型价格页补配置，而不是让用户误以为是配额问题。
         */
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "PRICING_NOT_CONFIGURED");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * 处理控制器主动抛出的 HTTP 状态异常。
     *
     * <p>后台登录失败、权限不足、资源不存在等业务错误都会走
     * {@link ResponseStatusException}。如果不单独处理，最后会落到通用 500，
     * 前端只能看到“系统内部错误”，无法正确跳转登录页或展示 403/404。</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus finalStatus = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", finalStatus.name());
        body.put("message", ex.getReason() == null ? finalStatus.getReasonPhrase() : ex.getReason());
        return ResponseEntity.status(finalStatus).body(body);
    }

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
     * 处理登录服务异常。
     *
     * <p>登录失败不属于系统 500，因此这里明确返回 401，方便前端直接展示账号/密码错误或重新登录提示。</p>
     */
    @ExceptionHandler(LoginServiceException.class)
    public ResponseEntity<Map<String, Object>> handleLoginServiceException(LoginServiceException ex) {
        log.error("login服务异常: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "LOGIN_FAILED");
        body.put("message", "登录失败: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
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
