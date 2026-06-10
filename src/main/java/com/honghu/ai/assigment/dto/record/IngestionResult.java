package com.honghu.ai.assigment.dto.record;

/**
 * @program: testDeepseekR1
 * @description:
 *
 * @author: shilaoban
 * @create: 2026-04-30 23:08
 **/
public record IngestionResult(boolean shouldDeleteMessage, boolean success, String detail) {
    public static IngestionResult success(String detail) {
        return new IngestionResult(true, true, detail);
    }
    public static IngestionResult skip(String detail) {
        return new IngestionResult(true, true, detail);
    }

    public static IngestionResult permanentFailure(String detail) {
        return new IngestionResult(true, false, detail);
    }

    public static IngestionResult retryLater(String detail) {
        return new IngestionResult(false, false, detail);
    }
}
