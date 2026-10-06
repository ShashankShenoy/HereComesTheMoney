package com.moneybags.iam.dto;
import com.moneybags.iam.security.UserPrincipal;
import java.time.OffsetDateTime;
public record LoginResponse(String accessToken,String tokenType,String refreshToken,OffsetDateTime idleExpiresAt,OffsetDateTime absoluteExpiresAt,UserPrincipal user) {}
