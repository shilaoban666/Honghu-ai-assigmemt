package com.honghu.ai.assigment.exception;

/**
 * AI服务异常
 *
 * @author shilaoban
 * @since 2026-02-22
 */
public class AIServiceException extends RuntimeException {
    
    public AIServiceException(String message) {
        super(message);
    }
    
    public AIServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}