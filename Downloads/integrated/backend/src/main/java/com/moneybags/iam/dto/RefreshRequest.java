package com.moneybags.iam.dto;
import jakarta.validation.constraints.*;
public record RefreshRequest(@NotBlank @Size(max=128) String refreshToken) {}
