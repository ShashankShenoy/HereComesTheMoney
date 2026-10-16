package com.moneybags.statements;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;

/** Wire contracts shared by controllers and upstream adapters. */
public final class ApiModels {
    private ApiModels() {}
    public record CreateRequest(@NotNull @Min(1) Long accountId, @Size(max=36) String customerCifId,
        @NotBlank @Size(max=30) String statementType, @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd,
        @Size(max=20) String locale, @NotBlank @Size(max=12) String format,
        @NotBlank @Size(max=30) String channel, @Size(max=80) String purposeCode,
        @Size(max=160) String caseReference, @Size(max=100) String correlationId,
        @Size(max=36) String priorStatementId, @Size(max=80) String correctionReasonCode) {}
    public record RequestView(String requestId, Long accountId, String status, String statementType,
        LocalDate periodStart, LocalDate periodEnd, String format, String failureCode, String statementId) {}
    public record StatementView(String statementId, String statementNumber, Long accountId,
        String state, int revision, LocalDate periodStart, LocalDate periodEnd,
        BigDecimal openingBalance, BigDecimal closingBalance, String currency,
        String locale, List<LineView> lines) {}
    public record LineView(int lineNo, Long journalId, Long postingId, OffsetDateTime bookedAt,
        LocalDate valueDate, String entryClass, BigDecimal amount, BigDecimal balanceAfter,
        String narration) {}
    public record DeliveryRequest(@NotBlank @Size(max=30) String channel, @NotBlank @Size(max=20) String recipientType,
        @NotBlank @Pattern(regexp="[a-fA-F0-9]{64}") String recipientReferenceHash,
        @NotBlank @Size(max=12) String format, @Size(max=100) String correlationId) {}
    public record DeliveryView(String deliveryId, String statementId, String status,
        String channel, String format, String renderUri, String failureCode) {}
    public record DeliveryResult(@NotBlank String status, String renderUri, String renderSha256, String failureCode) {}
    public record ConsumerEvent(@NotBlank String sourceService, @NotBlank String eventId,
        @NotBlank String eventType, @NotBlank String payloadSha256) {}
    public record OutboxEvent(String eventId, String eventType, String payloadJson, String correlationId) {}
    public record PolicyDraft(@NotBlank @Size(max=80) String code, @Min(1) int version,
        @NotBlank @Pattern(regexp="WEB|BRANCH|API|EMAIL|POST|DOWNLOAD") String channel,
        @NotBlank @Pattern(regexp="CUSTOMER|STAFF|REGULATOR") String audience, @NotBlank String rulesJson) {}
    public record NarrationDraft(@NotBlank @Pattern(regexp="NORMAL|ADJUSTMENT|RETURN|REVERSAL") String code,
        @NotBlank @Pattern(regexp="[a-z]{2,3}-[A-Z]{2}") @Size(max=20) String locale,
        @NotBlank @Pattern(regexp="CUSTOMER|STAFF|REGULATOR") String audience, @Min(1) int version, @NotBlank @Size(max=1000) String text,
        @NotNull OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {}
}
