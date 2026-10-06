package com.moneybags.privacy.audit;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.moneybags.common.api.BusinessException;
import com.moneybags.integration.BankingAccess;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Read-only financial audit projection of the ledger's immutable status history. */
@RestController
@RequestMapping("/api/v1/privacy/financial-audit")
public class FinancialAuditController {
    private final JdbcTemplate jdbc;
    private final BankingAccess access;
    private final ZoneId businessZone;

    public FinancialAuditController(JdbcTemplate jdbc, BankingAccess access,
                                    @Value("${moneybags.business-zone:Asia/Kolkata}") String businessZone) {
        this.jdbc = jdbc;
        this.access = access;
        this.businessZone = ZoneId.of(businessZone);
    }

    @GetMapping
    @Operation(summary = "Review posted financial transactions and their source and destination accounts")
    public ResponseEntity<List<FinancialEvent>> search(
            @RequestParam(required = false) Long transactionId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        access.global("IAM_AUDIT_READ");
        if ((fromDate != null && (fromDate.getYear() < 1 || fromDate.getYear() > 9999))
                || (toDate != null && (toDate.getYear() < 1 || !toDate.isBefore(LocalDate.of(9999, 12, 31))))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DATE_RANGE", "Date is outside the supported audit range");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DATE_RANGE", "From date must be on or before To date");
        }
        // Posting already appends this evidence atomically. A replay adds no new history row.
        var sql = new StringBuilder("""
                SELECT H.TXN_STATUS_HISTORY_ID,T.TXN_ID,T.TXN_TYPE,H.TO_STATUS,T.STATUS,
                       T.SOURCE_ACCOUNT_ID,SUBSTR(S.ACCOUNT_NUMBER,-4),
                       T.TARGET_ACCOUNT_ID,SUBSTR(D.ACCOUNT_NUMBER,-4),
                       T.AMOUNT,T.CURRENCY_CODE,H.ACTOR_ID,COALESCE(U.USERNAME,H.ACTOR_ID),
                       H.CHANGED_AT,T.REVERSAL_OF_TXN_ID
                  FROM M05_TXN_STATUS_HISTORY H
                  JOIN M05_TXN_TRANSACTION_LOG T ON T.TXN_ID=H.TXN_ID
                  LEFT JOIN M04_BANK_ACCOUNT S ON S.ACCOUNT_ID=T.SOURCE_ACCOUNT_ID
                  LEFT JOIN M04_BANK_ACCOUNT D ON D.ACCOUNT_ID=T.TARGET_ACCOUNT_ID
                  LEFT JOIN M01_IAM_USER U ON U.USER_ID=H.ACTOR_ID
                 WHERE H.TO_STATUS IN ('POSTED','REVERSED')
                """);
        var parameters = new ArrayList<Object>();
        if (transactionId != null) {
            sql.append(" AND T.TXN_ID=?");
            parameters.add(transactionId);
        }
        if (accountId != null) {
            sql.append(" AND (T.SOURCE_ACCOUNT_ID=? OR T.TARGET_ACCOUNT_ID=?)");
            parameters.add(accountId);
            parameters.add(accountId);
        }
        // Inclusive calendar dates in the bank's business zone; next-day midnight is excluded.
        if (fromDate != null) {
            sql.append(" AND H.CHANGED_AT>=?");
            parameters.add(fromDate.atStartOfDay(businessZone).toOffsetDateTime());
        }
        if (toDate != null) {
            sql.append(" AND H.CHANGED_AT<?");
            parameters.add(toDate.plusDays(1).atStartOfDay(businessZone).toOffsetDateTime());
        }
        sql.append(" ORDER BY H.CHANGED_AT DESC,H.TXN_STATUS_HISTORY_ID DESC FETCH FIRST 100 ROWS ONLY");
        var events = jdbc.query(sql.toString(), (rs, row) -> {
            Long source = rs.getObject(6, Long.class), target = rs.getObject(8, Long.class);
            return new FinancialEvent(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), source, rs.getString(7), target,
                    rs.getString(9), rs.getBigDecimal(10),
                    rs.getString(11), rs.getString(12), rs.getString(13),
                    rs.getObject(14, OffsetDateTime.class), rs.getObject(15, Long.class));
        }, parameters.toArray());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(events);
    }

    public record FinancialEvent(long eventId, long transactionId, String type, String event,
                                 String currentStatus, Long sourceAccountId, String sourceAccountEnding,
                                 Long targetAccountId, String targetAccountEnding,
                                 @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
                                 String currency, String actorUserId, String actor,
                                 OffsetDateTime occurredAt, Long originalTransactionId) {}
}
