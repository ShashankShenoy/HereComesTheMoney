package com.moneybags.iam.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;

/** Public IAM contracts. Passwords and MFA secrets never appear in administrative responses. */
public final class IamDtos {
    private IamDtos() {}
    public record CreateUser(@NotBlank @Pattern(regexp="[A-Za-z0-9._@-]{3,120}") String username,
        @NotBlank @Pattern(regexp="CUSTOMER|EMPLOYEE|SERVICE") String userType,
        @Size(max=80) String employeeRef, @NotBlank @Size(min=10,max=72) String password) {}
    public record Version(@NotNull @Min(1) Long rowVersion) {}
    public record ReasonInput(@NotBlank @Size(max=500) String reason) {}
    public record StatusChange(@NotNull @Min(1) Long rowVersion,
        @NotBlank @Pattern(regexp="ACTIVE|AUTH_LOCKED|SUSPENDED|DISABLED|CLOSED") String status,
        @NotBlank @Size(max=500) String reason) {}
    public record PasswordReset(@NotBlank @Size(min=10,max=72) String password,
        @NotBlank @Size(max=500) String reason) {}
    public record PasswordChange(@NotBlank @Size(max=72) String currentPassword,
        @NotBlank @Size(min=10,max=72) String newPassword) {}
    public record RoleInput(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{2,79}") String roleCode,
        @NotBlank @Size(max=120) String displayName, boolean sensitive,
        @NotNull @Size(max=100) List<@NotBlank String> permissionIds) {}
    public record RoleUpdate(@NotNull @Min(1) Long rowVersion,
        @NotBlank @Size(max=120) String displayName, boolean sensitive,
        @NotBlank @Pattern(regexp="ACTIVE|RETIRED") String status,
        @NotNull @Size(max=100) List<@NotBlank String> permissionIds) {}
    public record PermissionInput(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{2,99}") String permissionCode,
        @NotBlank @Size(max=60) String resourceCode, @NotBlank @Size(max=60) String actionCode,
        boolean stepUpRequired) {}
    public record Dimension(@NotBlank @Pattern(regexp="PRODUCT_TYPE|CURRENCY") String dimensionCode,
        @NotBlank @Size(max=80) String scopeValue) {}
    public record AccessInput(@NotBlank String targetUserId, @NotBlank String roleId,
        @NotBlank @Pattern(regexp="GRANT|REVOKE") String changeType, String targetAssignmentId,
        @NotBlank @Pattern(regexp="SELF|BRANCH|REGION|GLOBAL") String scopeType,
        @Size(max=80) String scopeRef, @NotNull OffsetDateTime validFrom, OffsetDateTime validTo,
        @NotBlank @Size(max=500) String reason, @NotNull @Size(max=30) List<@Valid Dimension> dimensions) {}
    public record Decision(@NotNull @Min(1) Long rowVersion,
        @NotBlank @Pattern(regexp="APPROVED|REJECTED") String decision) {}
    public record AuthorityInput(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{2,79}") String authorityCode,
        @NotBlank @Pattern(regexp="[A-Z]{3}|\\*") String currencyCode,
        @DecimalMin("0") @Digits(integer=16,fraction=4) BigDecimal maxAmount,
        @DecimalMin("0") @Digits(integer=4,fraction=8) BigDecimal maxRatePct,
        @NotNull OffsetDateTime validFrom, OffsetDateTime validTo) {}
    public record LinkInput(@NotBlank @Size(max=36) String cifId,
        @NotBlank @Size(max=24) String relationshipType) {}
    public record MenuInput(@NotBlank @Size(max=80) String clientId, String parentMenuId,
        @NotBlank @Pattern(regexp="/[A-Za-z0-9/_-]*") @Size(max=240) String routePath,
        @NotBlank @Size(max=120) String labelKey, @Min(0) @Max(99999) int sortOrder,
        String requiredPermissionId, @NotBlank @Pattern(regexp="ACTIVE|HIDDEN") String status) {}
    public record Code(@NotBlank @Pattern(regexp="[0-9]{6}") String code) {}
    public record FactorRevoke(@NotBlank @Size(max=72) String password, @Size(max=6) String code) {}
    public record FactorView(String factorId,String factorType,String status,OffsetDateTime enrolledAt,
        OffsetDateTime verifiedAt,OffsetDateTime revokedAt) {}
    public record Enrollment(String factorId,String secret,String otpauthUri) {}
    public record AuthorizationInput(@NotBlank @Size(max=100) String permissionCode,
        @Size(max=80) String branchRef,@Size(max=80) String regionRef,@Size(max=36) String cifId,
        @Size(max=80) String productType,@Size(max=3) String currencyCode,
        @Size(max=80) String authorityCode,@DecimalMin("0") BigDecimal amount,
        @DecimalMin("0") BigDecimal ratePct,@Size(max=36) String makerUserId) {}
}
