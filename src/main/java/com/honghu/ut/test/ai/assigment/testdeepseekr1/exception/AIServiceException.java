package com.honghu.ut.test.ai.assigment.testdeepseekr1.exception;

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