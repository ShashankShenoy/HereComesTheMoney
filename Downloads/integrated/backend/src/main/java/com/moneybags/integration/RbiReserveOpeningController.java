package com.moneybags.integration;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.treasury.service.TreasuryService;
import com.moneybags.txn.core.LedgerService;
import com.moneybags.txn.api.Contracts.JournalLine;
import com.moneybags.txn.api.Contracts.JournalRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Synthetic opening capital for an RBI reserve, kept separate from cash deliveries. */
@RestController
@RequestMapping("/api/v1/treasury/reserve-openings")
public class RbiReserveOpeningController {
    private final BusinessRepository db;
    private final BankingAccess access;
    private final LedgerService ledger;
    private final TreasuryService treasury;
    private final CustomerHashService audit;

    public RbiReserveOpeningController(BusinessRepository db, BankingAccess access, LedgerService ledger,
                                       TreasuryService treasury, CustomerHashService audit) {
        this.db = db;
        this.access = access;
        this.ledger = ledger;
        this.treasury = treasury;
        this.audit = audit;
    }

    public record RequestOpening(@NotBlank @Size(max = 100) String requestKey,
                                 @NotNull @Positive Long reserveAccountId,
                                 @NotNull @Positive Long equityGlId,
                                 @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
                                 @NotBlank @Size(max = 120) String evidenceRef) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        access.global("TREASURY_READ");
        return db.rows("SELECT OPENING_ID,RESERVE_ACCOUNT_ID,EQUITY_GL_ID,AMOUNT,EVIDENCE_REF,STATUS,MAKER_ID,CHECKER_ID,JOURNAL_ID,TREASURY_ENTRY_ID,REQUESTED_AT,DECIDED_AT FROM MBX_RBI_RESERVE_OPENING ORDER BY REQUESTED_AT DESC FETCH FIRST 100 ROWS ONLY");
    }

    @PostMapping
    @Transactional
    public Map<String, Object> request(@Valid @RequestBody RequestOpening command) {
        access.global("TREASURY_LIQUIDITY_MANAGE");
        access.global("GL_ADMIN");
        var existing = db.rows("SELECT OPENING_ID,RESERVE_ACCOUNT_ID,EQUITY_GL_ID,AMOUNT,EVIDENCE_REF,STATUS FROM MBX_RBI_RESERVE_OPENING WHERE REQUEST_KEY=?", command.requestKey());
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (((Number) row.get("RESERVE_ACCOUNT_ID")).longValue() != command.reserveAccountId()
                || ((Number) row.get("EQUITY_GL_ID")).longValue() != command.equityGlId()
                || ((BigDecimal) row.get("AMOUNT")).compareTo(command.amount()) != 0
                || !Objects.equals(row.get("EVIDENCE_REF"), command.evidenceRef())) {
                throw fail("Request key was used for different reserve opening details");
            }
            return Map.of("openingId", row.get("OPENING_ID"), "status", row.get("STATUS"));
        }
        validateAccounts(command.reserveAccountId(), command.equityGlId());
        String id = UUID.randomUUID().toString();
        db.jdbc().update("INSERT INTO MBX_RBI_RESERVE_OPENING(OPENING_ID,REQUEST_KEY,RESERVE_ACCOUNT_ID,EQUITY_GL_ID,AMOUNT,EVIDENCE_REF,MAKER_ID) VALUES (?,?,?,?,?,?,?)",
            id, command.requestKey(), command.reserveAccountId(), command.equityGlId(), command.amount(),
            command.evidenceRef(), CurrentActor.get().userId());
        audit.audit("RESERVE_OPENING_REQUESTED", "RESERVE_OPENING", id, "SYNTHETIC_DEMO_CAPITAL");
        return Map.of("openingId", id, "status", "PENDING");
    }

    @PostMapping("/{id}/confirm")
    @Transactional
    public Map<String, Object> confirm(@PathVariable String id) {
        access.global("TREASURY_WORK_APPROVE");
        var opening = db.one("SELECT * FROM MBX_RBI_RESERVE_OPENING WHERE OPENING_ID=? FOR UPDATE", id);
        String checker = CurrentActor.get().userId();
        if (checker.equals(opening.get("MAKER_ID"))) throw fail("A separate checker must approve synthetic reserve opening");
        if ("CONFIRMED".equals(opening.get("STATUS"))) {
            return Map.of("openingId", id, "status", "CONFIRMED",
                "journalId", opening.get("JOURNAL_ID"), "treasuryEntryId", opening.get("TREASURY_ENTRY_ID"));
        }
        if (!"PENDING".equals(opening.get("STATUS"))) throw fail("Reserve opening is not pending");
        long reserveId = ((Number) opening.get("RESERVE_ACCOUNT_ID")).longValue();
        long equityGl = ((Number) opening.get("EQUITY_GL_ID")).longValue();
        BigDecimal amount = (BigDecimal) opening.get("AMOUNT");
        String evidenceRef = (String) opening.get("EVIDENCE_REF");
        long reserveGl = validateAccounts(reserveId, equityGl);
        try {
            var position = db.one("SELECT CONFIRMED_BALANCE,ACTIVE_HOLD_AMOUNT FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=? AND CURRENCY_CODE='INR' FOR UPDATE", reserveId);
            BigDecimal booked = db.jdbc().queryForObject("SELECT COALESCE(SUM(CASE WHEN ENTRY_SIDE='DR' THEN AMOUNT ELSE -AMOUNT END),0) FROM M05_GL_POSTING WHERE GL_ACCOUNT_ID=?", BigDecimal.class, reserveGl);
            if (((BigDecimal) position.get("CONFIRMED_BALANCE")).signum() != 0
                || ((BigDecimal) position.get("ACTIVE_HOLD_AMOUNT")).signum() != 0
                || booked.signum() != 0
                || db.count("SELECT COUNT(*) FROM M07_CENTRAL_TREASURY_LEDGER WHERE RESERVE_ACCOUNT_ID=?", reserveId) != 0) {
                throw fail("Reserve opening requires an unused zero-balance RBI account and GL");
            }
            String postingKey = "reserve-opening:" + id;
            LocalDate date = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            var key = new GeneratedKeyHolder();
            db.jdbc().update(connection -> {
                var statement = connection.prepareStatement(
                    "INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,AMOUNT,VALUE_DATE) VALUES (?,'TREASURY',?,?,?,'ADJUSTMENT','VALIDATED',?,?)",
                    new String[]{"TXN_ID"});
                Object[] values = {checker, postingKey, CustomerHashService.digest(postingKey), id, amount, date};
                for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                return statement;
            }, key);
            long txn = Objects.requireNonNull(key.getKey()).longValue();
            var journal = ledger.postJournal(new JournalRequest(postingKey, "ADJUSTMENT", txn,
                null, null, null, null, null, date,
                "Synthetic demo capital placed in RBI reserve: " + evidenceRef,
                List.of(new JournalLine(reserveGl, null, null, null, "DR", amount, "Synthetic RBI reserve opening"),
                    new JournalLine(equityGl, null, null, null, "CR", amount, "Synthetic opening capital"))), checker);
            markPosted(txn, checker);
            var posted = treasury.confirmReserveOpening(id, reserveId, amount, evidenceRef,
                journal.journalId(), OffsetDateTime.now(), checker);
            db.jdbc().update("UPDATE MBX_RBI_RESERVE_OPENING SET STATUS='CONFIRMED',CHECKER_ID=?,TXN_ID=?,JOURNAL_ID=?,EVIDENCE_ID=?,TREASURY_ENTRY_ID=?,DECIDED_AT=SYSTIMESTAMP WHERE OPENING_ID=?",
                checker, txn, journal.journalId(), posted.evidenceId(), posted.treasuryEntryId(), id);
            audit.audit("RESERVE_OPENING_CONFIRMED", "RESERVE_OPENING", id, evidenceRef);
            return Map.of("openingId", id, "status", "CONFIRMED", "journalId", journal.journalId(),
                "treasuryEntryId", posted.treasuryEntryId(), "reserveBalance", amount);
        } catch (com.moneybags.treasury.domain.DomainException error) {
            throw new BusinessException(HttpStatus.valueOf(error.status()), error.code(), error.getMessage());
        }
    }

    private long validateAccounts(long reserveId, long equityGl) {
        var reserve = db.one("SELECT RESERVE_ACCOUNT_ID,GL_ACCOUNT_ID,ACCOUNT_TYPE,ACCOUNT_STATUS FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_ID=?", reserveId);
        if (!"RBI_CURRENT".equals(reserve.get("ACCOUNT_TYPE")) || !"ACTIVE".equals(reserve.get("ACCOUNT_STATUS")))
            throw fail("Select an active simulated RBI current reserve account");
        long reserveGl = ((Number) reserve.get("GL_ACCOUNT_ID")).longValue();
        if (reserveGl == equityGl || db.count("SELECT COUNT(*) FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID=? AND ACCOUNT_CLASS='ASSET' AND NORMAL_SIDE='DR' AND CURRENCY_CODE='INR' AND ACTIVE_FLAG='Y'", reserveGl) != 1
            || db.count("SELECT COUNT(*) FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID=? AND ACCOUNT_CLASS='EQUITY' AND NORMAL_SIDE='CR' AND CURRENCY_CODE='INR' AND ACTIVE_FLAG='Y'", equityGl) != 1
            || db.count("SELECT COUNT(*) FROM M07_RESERVE_ACCOUNT WHERE GL_ACCOUNT_ID=?", reserveGl) != 1)
            throw fail("Reserve and capital must use distinct active INR asset and equity GL accounts");
        return reserveGl;
    }

    private void markPosted(long txn, String checker) {
        db.jdbc().update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS='POSTED' WHERE TXN_ID=?", txn);
        db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,NULL,'RECEIVED',?,'RESERVE_OPENING')", txn, checker);
        db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,'RECEIVED','VALIDATED',?,'RESERVE_OPENING')", txn, checker);
        db.jdbc().update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,'VALIDATED','POSTED',?,'RESERVE_OPENING')", txn, checker);
    }

    private BusinessException fail(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "RESERVE_OPENING_CONTROL", message);
    }
}
