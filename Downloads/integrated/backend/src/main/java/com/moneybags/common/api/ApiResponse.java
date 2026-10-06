package com.moneybags.common.api;
import java.time.Instant;
public record ApiResponse<T>(T data,String correlationId,Instant timestamp) {
    public static <T> ApiResponse<T> of(T data) { return new ApiResponse<>(data,Correlation.current(),Instant.now()); }
}
