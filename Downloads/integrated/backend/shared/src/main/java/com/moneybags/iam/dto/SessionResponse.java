package com.moneybags.iam.dto;
import java.time.OffsetDateTime;
public record SessionResponse(String sessionId,String clientId,String deviceRef,String status,OffsetDateTime createdAt,OffsetDateTime lastActivityAt,OffsetDateTime idleExpiresAt,OffsetDateTime absoluteExpiresAt,boolean current) {}
