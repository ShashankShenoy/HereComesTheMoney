package com.moneybags.common.api;
import java.time.Instant;
import java.util.Map;
public record ApiError(int status,String code,String message,String path,String correlationId,Instant timestamp,Map<String,String> fieldErrors) {}
