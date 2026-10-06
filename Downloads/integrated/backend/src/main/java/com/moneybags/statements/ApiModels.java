package com.moneybags.statements;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;

/** Wire contracts shared by controllers and upstream adapters. */
public final class ApiModels {
    private ApiModels() {}
    public record CreateRequest(@NotNull Long accountId, String customerCifId,
        @NotBlank String statementType, @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd,
        String locale, @NotBlank String format, @NotBlank String channel, String purposeCode,
        String caseReference, String correlationId, String priorStatementId, String correctionReasonCode) {}
    public record RequestView(String requestId, Long accountId, String status, String statementType,
        LocalDate periodStart, LocalDate periodEnd, String format, String failureCode, String statementId) {}
    public record StatementView(String statementId, String statementNumber, Long accountId,
        String state, int revision, LocalDate periodStart, LocalDate periodEnd,
        BigDecimal openingBalance, BigDecimal closingBalance, String currency,
        String locale, List<LineView> lines) {}
    public record LineView(int lineNo, Long journalId, Long postingId, OffsetDateTime bookedAt,
        LocalDate valueDate, String entryClass, BigDecimal amount, BigDecimal balanceAfter,
        String narration) {}
    public record DeliveryRequest(@NotBlank String channel, @NotBlank String recipientType,
        @NotBlank String recipientReferenceHash, @NotBlank String format, String correlationId) {}
    public record DeliveryView(String deliveryId, String statementId, String status,
        String channel, String format, String renderUri, String failureCode) {}
    public record DeliveryResult(@NotBlank String status, String renderUri, String renderSha256, String failureCode) {}
    public record ConsumerEvent(@NotBlank String sourceService, @NotBlank String eventId,
        @NotBlank String eventType, @NotBlank String payloadSha256) {}
    public record OutboxEvent(String eventId, String eventType, String payloadJson, String correlationId) {}
    public record PolicyDraft(@NotBlank String code, @Min(1) int version,
        @NotBlank String channel, @NotBlank String audience, @NotBlank String rulesJson) {}
    public record NarrationDraft(@NotBlank String code, @NotBlank String locale,
        @NotBlank String audience, @Min(1) int version, @NotBlank String text,
        @NotNull OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {}
}
