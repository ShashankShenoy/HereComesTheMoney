package com.moneybags.txn.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.txn.api.ApiException;
import com.moneybags.txn.api.Contracts.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns Module 5's ledger, holds, controls, and transfer write model. */
@Service
public class LedgerService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AccountCatalog accounts;
    private final PaymentCatalog payments;

    /** Injects JDBC and the replaceable cross-module account adapter. */
    public LedgerService(JdbcTemplate db, ObjectMapper json, AccountCatalog accounts, PaymentCatalog payments) {
        this.db = db; this.json = json; this.accounts = accounts; this.payments = payments;
    }

    /** Reads the authoritative spendable position; it is never read from Module 4's projection. */
    public PositionView position(long accountId) {
        List<PositionView> rows = db.query("SELECT BANK_ACCOUNT_ID,POSTED_BALANCE,ACTIVE_HOLD_AMOUNT,ACTIVE_LIEN_AMOUNT,ACTIVE_BLOCK_AMOUNT,OVERDRAFT_LIMIT,SPENDABLE_BALANCE,POSITION_VERSION,APPLIED_CONTROL_VERSION FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=? AND CURRENCY_CODE='INR'",
                (rs, n) -> new PositionView(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getLong(8), rs.getLong(9)), accountId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Position is not initialized");
        return rows.get(0);
    }

    /** Applies a consecutive, deduplicated Module 4 control snapshot under the spend lock. */
    @Transactional
    public PositionView applyControl(long accountId, ControlRequest request, String actor) {
        accounts.requireExists(accountId);
        if (!List.of("OPEN", "CLOSED").contains(request.debitStatus()) || !List.of("OPEN", "CLOSED").contains(request.creditStatus()))
            throw invalid("CONTROL_STATUS", "Control direction must be OPEN or CLOSED");
        byte[] event = raw(request.eventId());
        if (!db.query("SELECT EVENT_ID FROM M05_CONSUMER_INBOX WHERE CONSUMER_NAME='M04_CONTROL' AND EVENT_ID=?",
                (rs, n) -> rs.getBytes(1), event).isEmpty()) {
            List<byte[]> hashes = db.query("SELECT REQUEST_HASH FROM M05_IDEMPOTENCY_RECORD WHERE ORIGINATOR_ID='M04_CONTROL' AND CHANNEL_CODE='EVENT' AND REQUEST_KEY=?",
                    (rs, n) -> rs.getBytes(1), request.eventId().toString());
            if (hashes.size() != 1 || !Arrays.equals(hashes.get(0), sha256(request)))
                throw new ApiException(HttpStatus.CONFLICT, "CONTROL_EVENT_CONFLICT", "Control event was reused with another payload");
            List<Map<String, Object>> previous = db.queryForList("SELECT BANK_ACCOUNT_ID,CONTROL_VERSION,NEW_DEBIT_STATUS,NEW_CREDIT_STATUS,LIEN_AMOUNT_AFTER,BLOCK_AMOUNT_AFTER FROM M05_POSTING_FENCE_HISTORY WHERE SOURCE_EVENT_ID=?", event);
            if (previous.size() != 1 || ((Number) previous.get(0).get("BANK_ACCOUNT_ID")).longValue() != accountId ||
                    ((Number) previous.get(0).get("CONTROL_VERSION")).longValue() != request.controlVersion() ||
                    !Objects.equals(previous.get(0).get("NEW_DEBIT_STATUS"), request.debitStatus()) ||
                    !Objects.equals(previous.get(0).get("NEW_CREDIT_STATUS"), request.creditStatus()) ||
                    ((BigDecimal) previous.get(0).get("LIEN_AMOUNT_AFTER")).compareTo(request.lienAmount()) != 0 ||
                    ((BigDecimal) previous.get(0).get("BLOCK_AMOUNT_AFTER")).compareTo(request.blockAmount()) != 0)
                throw new ApiException(HttpStatus.CONFLICT, "CONTROL_EVENT_CONFLICT", "Control event was reused with another payload");
            return position(accountId);
        }
        List<Map<String, Object>> fences = db.queryForList("SELECT DEBIT_STATUS,CREDIT_STATUS,CONTROL_VERSION FROM M05_POSTING_FENCE WHERE BANK_ACCOUNT_ID=? FOR UPDATE", accountId);
        long oldVersion = fences.isEmpty() ? 0 : ((Number) fences.get(0).get("CONTROL_VERSION")).longValue();
        if (request.controlVersion() != oldVersion + 1)
            throw new ApiException(HttpStatus.CONFLICT, "CONTROL_VERSION_GAP", "Expected control version " + (oldVersion + 1));
        if (fences.isEmpty()) {
            db.update("INSERT INTO M05_POSTING_FENCE(BANK_ACCOUNT_ID,CHANGED_BY) VALUES (?,?)", accountId, actor);
            db.update("INSERT INTO M05_ACCOUNT_POSITION(BANK_ACCOUNT_ID) VALUES (?)", accountId);
        }
        db.queryForList("SELECT BANK_ACCOUNT_ID FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=? FOR UPDATE", accountId);
        String oldDebit = fences.isEmpty() ? null : (String) fences.get(0).get("DEBIT_STATUS");
        String oldCredit = fences.isEmpty() ? null : (String) fences.get(0).get("CREDIT_STATUS");
        db.update("UPDATE M05_POSTING_FENCE SET DEBIT_STATUS=?,CREDIT_STATUS=?,CONTROL_VERSION=?,SOURCE_EVENT_ID=?,REASON_CODE=?,CHANGED_BY=?,CHANGED_AT=SYSTIMESTAMP WHERE BANK_ACCOUNT_ID=?",
                request.debitStatus(), request.creditStatus(), request.controlVersion(), event, request.reasonCode(), actor, accountId);
        db.update("UPDATE M05_ACCOUNT_POSITION SET ACTIVE_LIEN_AMOUNT=?,ACTIVE_BLOCK_AMOUNT=?,OVERDRAFT_LIMIT=?,APPLIED_CONTROL_VERSION=?,POSITION_VERSION=POSITION_VERSION+1,AS_OF=SYSTIMESTAMP WHERE BANK_ACCOUNT_ID=? AND CURRENCY_CODE='INR'",
                request.lienAmount(), request.blockAmount(), request.overdraftLimit(), request.controlVersion(), accountId);
        db.update("INSERT INTO M05_POSTING_FENCE_HISTORY(BANK_ACCOUNT_ID,CONTROL_VERSION,SOURCE_EVENT_ID,OLD_DEBIT_STATUS,NEW_DEBIT_STATUS,OLD_CREDIT_STATUS,NEW_CREDIT_STATUS,LIEN_AMOUNT_AFTER,BLOCK_AMOUNT_AFTER,REASON_CODE) VALUES (?,?,?,?,?,?,?,?,?,?)",
                accountId, request.controlVersion(), event, oldDebit, request.debitStatus(), oldCredit, request.creditStatus(), request.lienAmount(), request.blockAmount(), request.reasonCode());
        db.update("INSERT INTO M05_CONSUMER_INBOX(CONSUMER_NAME,EVENT_ID,EVENT_TYPE,PROCESSED_AT,RESULT_CODE) VALUES ('M04_CONTROL',?,'AccountControlChanged',SYSTIMESTAMP,'APPLIED')", event);
        db.update("INSERT INTO M05_IDEMPOTENCY_RECORD(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,RESOURCE_TYPE,RESOURCE_ID,HTTP_STATUS) VALUES ('M04_CONTROL','EVENT',?,?,'ACCOUNT',?,200)",
                request.eventId().toString(), sha256(request), accountId);
        outbox("m05.account.control.applied.v1", "ACCOUNT", accountId, Map.of("accountId", accountId, "controlVersion", request.controlVersion()), null);
        audit(actor, "CONTROL_APPLIED", "ACCOUNT", accountId, null);
        return position(accountId);
    }

    /** Places one payment hold and reserves spendable funds before Module 6 dispatch. */
    @Transactional
    public HoldView placeHold(HoldRequest request, String actor) {
        requireMoney(request.amount());
        if (!request.expiresAt().isAfter(OffsetDateTime.now()))
            throw invalid("HOLD_EXPIRY", "Hold expiry must be in the future");
        List<HoldView> old = db.query("SELECT HOLD_ID,STATUS,BANK_ACCOUNT_ID,PAYMENT_ID,AMOUNT FROM M05_FUNDS_HOLD WHERE HOLD_KEY=?",
                (rs, n) -> new HoldView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getBigDecimal(5)), request.holdKey());
        if (!old.isEmpty()) {
            HoldView value = old.get(0);
            if (value.bankAccountId() != request.bankAccountId() || value.paymentId() != request.paymentId() || value.amount().compareTo(request.amount()) != 0)
                throw new ApiException(HttpStatus.CONFLICT, "HOLD_KEY_CONFLICT", "Hold key was used for another request");
            return value;
        }
        payments.requireAuthorizedHold(request.paymentId(), request.bankAccountId(), request.amount());
        PositionView position = lockedPosition(request.bankAccountId());
        requireFence(request.bankAccountId(), "DR");
        if (position.spendable().compareTo(request.amount()) < 0)
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Spendable funds are insufficient");
        long id = insertId("INSERT INTO M05_FUNDS_HOLD(BANK_ACCOUNT_ID,AMOUNT,PAYMENT_ID,HOLD_KEY,EXPIRES_AT) VALUES (?,?,?,?,?)", "HOLD_ID",
                request.bankAccountId(), request.amount(), request.paymentId(), request.holdKey(), request.expiresAt());
        db.update("UPDATE M05_ACCOUNT_POSITION SET ACTIVE_HOLD_AMOUNT=ACTIVE_HOLD_AMOUNT+?,POSITION_VERSION=POSITION_VERSION+1,AS_OF=SYSTIMESTAMP WHERE BANK_ACCOUNT_ID=? AND CURRENCY_CODE='INR'",
                request.amount(), request.bankAccountId());
        outbox("m05.hold.placed.v1", "HOLD", id, Map.of("holdId", id, "paymentId", request.paymentId(), "accountId", request.bankAccountId(), "amount", request.amount()), null);
        audit(actor, "HOLD_PLACED", "HOLD", id, null);
        return new HoldView(id, "ACTIVE", request.bankAccountId(), request.paymentId(), request.amount());
    }

    /** Releases a confirmed failed or cancelled payment hold; expiry alone never releases it. */
    @Transactional
    public HoldView releaseHold(long holdId, String reasonCode, String actor) {
        List<Map<String, Object>> initial = db.queryForList("SELECT BANK_ACCOUNT_ID FROM M05_FUNDS_HOLD WHERE HOLD_ID=?", holdId);
        if (initial.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Hold does not exist");
        long accountId = ((Number) initial.get(0).get("BANK_ACCOUNT_ID")).longValue();
        lockedPosition(accountId);
        Map<String, Object> hold = requireHold(holdId);
        if (!"ACTIVE".equals(hold.get("STATUS")))
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "Hold has already closed");
        payments.requireTerminalFailure(((Number) hold.get("PAYMENT_ID")).longValue());
        BigDecimal amount = (BigDecimal) hold.get("AMOUNT");
        db.update("UPDATE M05_FUNDS_HOLD SET STATUS='RELEASED',RELEASE_REASON=?,CLOSED_AT=SYSTIMESTAMP,UPDATED_AT=SYSTIMESTAMP,VERSION_NO=VERSION_NO+1 WHERE HOLD_ID=?", reasonCode, holdId);
        db.update("UPDATE M05_ACCOUNT_POSITION SET ACTIVE_HOLD_AMOUNT=ACTIVE_HOLD_AMOUNT-?,POSITION_VERSION=POSITION_VERSION+1,AS_OF=SYSTIMESTAMP WHERE BANK_ACCOUNT_ID=? AND CURRENCY_CODE='INR'", amount, accountId);
        outbox("m05.hold.released.v1", "HOLD", holdId, Map.of("holdId", holdId, "accountId", accountId, "amount", amount, "reasonCode", reasonCode), null);
        audit(actor, "HOLD_RELEASED", "HOLD", holdId, null);
        return new HoldView(holdId, "RELEASED", accountId, ((Number) hold.get("PAYMENT_ID")).longValue(), amount);
    }

    /** Books a balanced journal, updates positions, and consumes an optional payment hold atomically. */
    @Transactional
    public JournalView postJournal(JournalRequest request, String actor) {
        validateJournal(request);
        List<JournalView> old = db.query("SELECT J.JOURNAL_ID,J.POSTING_KEY,V.DEBIT_TOTAL,V.CREDIT_TOTAL,J.CURRENCY_CODE FROM M05_GL_JOURNAL J JOIN M05_V_GL_JOURNAL_CONTROL V ON V.JOURNAL_ID=J.JOURNAL_ID WHERE J.POSTING_KEY=?",
                (rs, n) -> new JournalView(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5)), request.postingKey());
        if (!old.isEmpty()) {
            List<byte[]> hashes = db.query("SELECT REQUEST_HASH FROM M05_IDEMPOTENCY_RECORD WHERE ORIGINATOR_ID='M05_JOURNAL' AND CHANNEL_CODE='INTERNAL' AND REQUEST_KEY=?",
                    (rs, n) -> rs.getBytes(1), idempotencyKey(request.postingKey()));
            if (hashes.isEmpty() || !Arrays.equals(hashes.get(0), sha256(request)))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Posting key was reused with another payload");
            return old.get(0);
        }
        checkPeriod(request.valueDate());
        Map<Long, BigDecimal> deltas = new HashMap<>();
        for (JournalLine line : request.lines()) {
            requireMoney(line.amount());
            if (!List.of("DR", "CR").contains(line.side())) throw invalid("ENTRY_SIDE", "Entry side must be DR or CR");
            if (line.bankAccountId() != null && line.loanFacilityId() != null) throw invalid("POSTING_DIMENSION", "A line cannot target both an account and a loan");
            if (line.loanFacilityId() == null && line.loanComponentCode() != null) throw invalid("LOAN_COMPONENT", "Loan component requires a facility");
            List<Map<String, Object>> gl = db.queryForList("SELECT ACCOUNT_CLASS,CURRENCY_CODE,ACTIVE_FLAG FROM M05_GL_ACCOUNT WHERE GL_ACCOUNT_ID=?", line.glAccountId());
            if (gl.isEmpty() || !"Y".equals(gl.get(0).get("ACTIVE_FLAG")) || !"INR".equals(gl.get(0).get("CURRENCY_CODE")))
                throw invalid("GL_ACCOUNT", "GL account is missing or inactive for INR");
            if(line.loanFacilityId()!=null&&!"ASSET".equals(gl.get(0).get("ACCOUNT_CLASS")))throw invalid("LOAN_GL_CLASS","Loan receivable components must use an asset GL");
            if (line.bankAccountId() != null) {
                if (!"LIABILITY".equals(gl.get(0).get("ACCOUNT_CLASS"))) throw invalid("CUSTOMER_GL_CLASS", "Bank account lines must use a liability GL");
                deltas.merge(line.bankAccountId(), "CR".equals(line.side()) ? line.amount() : line.amount().negate(), BigDecimal::add);
            }
        }
        List<Long> accountIds = new ArrayList<>(deltas.keySet());
        accountIds.sort(Comparator.naturalOrder());
        Map<Long, PositionView> locked = new HashMap<>();
        for (Long accountId : accountIds) locked.put(accountId, lockedPosition(accountId));
        for (JournalLine line : request.lines()) if (line.bankAccountId() != null) requireFence(line.bankAccountId(), line.side());
        Map<String, Object> hold = request.holdId() == null ? null : requireHold(request.holdId());
        BigDecimal heldAmount = hold == null ? BigDecimal.ZERO : (BigDecimal) hold.get("AMOUNT");
        long heldAccount = hold == null ? -1 : ((Number) hold.get("BANK_ACCOUNT_ID")).longValue();
        if (hold != null) {
            if (!"ACTIVE".equals(hold.get("STATUS")) || request.paymentId() == null ||
                    ((Number) hold.get("PAYMENT_ID")).longValue() != request.paymentId() ||
                    deltas.getOrDefault(heldAccount, BigDecimal.ZERO).compareTo(heldAmount.negate()) != 0)
                throw invalid("HOLD_MISMATCH", "Journal must consume the active payment hold as an equal net debit");
        }
        for (Long accountId : accountIds) {
            BigDecimal delta = deltas.get(accountId);
            BigDecimal available = locked.get(accountId).spendable().add(accountId == heldAccount ? heldAmount : BigDecimal.ZERO);
            if (delta.signum() < 0 && available.add(delta).signum() < 0)
                throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Spendable funds are insufficient");
        }
        long id = insertId("INSERT INTO M05_GL_JOURNAL(POSTING_KEY,TXN_ID,PAYMENT_ID,SETTLEMENT_CYCLE_ID,LOAN_FACILITY_ID,JOURNAL_TYPE,BOOKED_AT,VALUE_DATE,REVERSAL_OF_JOURNAL_ID,DESCRIPTION,CREATED_BY) VALUES (?,?,?,?,?,?,SYSTIMESTAMP,?,?,?,?)",
                "JOURNAL_ID", request.postingKey(), request.transactionId(), request.paymentId(), request.settlementCycleId(), request.loanFacilityId(), request.journalType(), request.valueDate(), request.reversalOfJournalId(), request.description(), actor);
        int lineNo = 1;
        for (JournalLine line : request.lines()) {
            db.update("INSERT INTO M05_GL_POSTING(JOURNAL_ID,LINE_NO,GL_ACCOUNT_ID,BANK_ACCOUNT_ID,LOAN_FACILITY_ID,LOAN_COMPONENT_CODE,ENTRY_SIDE,AMOUNT,NARRATIVE) VALUES (?,?,?,?,?,?,?,?,?)",
                    id, lineNo++, line.glAccountId(), line.bankAccountId(), line.loanFacilityId(), line.loanComponentCode(), line.side(), line.amount(), line.narrative());
        }
        if (hold != null) db.update("UPDATE M05_FUNDS_HOLD SET STATUS='CONSUMED',CONSUMED_BY_JOURNAL_ID=?,CLOSED_AT=SYSTIMESTAMP,UPDATED_AT=SYSTIMESTAMP,VERSION_NO=VERSION_NO+1 WHERE HOLD_ID=?", id, request.holdId());
        for (Long accountId : accountIds) {
            BigDecimal released = accountId == heldAccount ? heldAmount : BigDecimal.ZERO;
            db.update("UPDATE M05_ACCOUNT_POSITION SET POSTED_BALANCE=POSTED_BALANCE+?,ACTIVE_HOLD_AMOUNT=ACTIVE_HOLD_AMOUNT-?,LAST_JOURNAL_ID=?,POSITION_VERSION=POSITION_VERSION+1,AS_OF=SYSTIMESTAMP WHERE BANK_ACCOUNT_ID=? AND CURRENCY_CODE='INR'",
                    deltas.get(accountId), released, id, accountId);
            outbox("m05.account.position.changed.v1", "ACCOUNT", accountId,
                    Map.of("accountId", accountId, "journalId", id, "delta", deltas.get(accountId)), null);
        }
        outbox("m05.journal.posted.v1", "JOURNAL", id, Map.of("journalId", id, "postingKey", request.postingKey()), null);
        audit(actor, "JOURNAL_POSTED", "JOURNAL", id, null);
        db.update("INSERT INTO M05_IDEMPOTENCY_RECORD(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,RESOURCE_TYPE,RESOURCE_ID,HTTP_STATUS,RESPONSE_REF) VALUES ('M05_JOURNAL','INTERNAL',?,?,'JOURNAL',?,201,?)",
                idempotencyKey(request.postingKey()), sha256(request), id, request.postingKey());
        BigDecimal total = request.lines().stream().filter(l -> "DR".equals(l.side())).map(JournalLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new JournalView(id, request.postingKey(), total, total, "INR");
    }

    /** Creates and immediately posts a same-currency internal transfer with a stable request key. */
    @Transactional
    public TransactionView transfer(TransferRequest request, String actor) {
        requireMoney(request.amount());
        if (Objects.equals(request.sourceAccountId(), request.targetAccountId()))
            throw invalid("SAME_ACCOUNT", "Source and target accounts must differ");
        byte[] requestHash = sha256(request);
        List<Map<String, Object>> existing = db.queryForList("SELECT TXN_ID,REQUEST_HASH,STATUS,AMOUNT,CORRELATION_ID FROM M05_TXN_TRANSACTION_LOG WHERE ORIGINATOR_ID=? AND CHANNEL_CODE=? AND REQUEST_KEY=?",
                actor, request.channelCode(), request.requestKey());
        if (!existing.isEmpty()) {
            Map<String, Object> old = existing.get(0);
            if (!Arrays.equals((byte[]) old.get("REQUEST_HASH"), requestHash))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Request key was reused with another payload");
            long id = ((Number) old.get("TXN_ID")).longValue();
            return transaction(id);
        }
        var source = accounts.requireActive(request.sourceAccountId());
        var target = accounts.requireActive(request.targetAccountId());
        accounts.requireTransferAllowed(source.productVersionId(), request.channelCode());
        accounts.requireTransferAllowed(target.productVersionId(), request.channelCode());
        long sourceGl = mappedGl(source.productVersionId(), "TRANSFER", "CUSTOMER_LIABILITY", request.valueDate());
        long targetGl = mappedGl(target.productVersionId(), "TRANSFER", "CUSTOMER_LIABILITY", request.valueDate());
        long id = insertId("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,PRODUCT_VERSION_ID,AMOUNT,VALUE_DATE) VALUES (?,?,?,?,?,'INTERNAL_TRANSFER','RECEIVED',?,?,?,?,?)",
                "TXN_ID", actor, request.channelCode(), request.requestKey(), requestHash, request.correlationId(),
                request.sourceAccountId(), request.targetAccountId(), source.productVersionId(), request.amount(), request.valueDate());
        history(id, null, "RECEIVED", actor, null);
        status(id, "RECEIVED", "VALIDATED", actor, null);
        postJournal(new JournalRequest("transfer:" + id, "TRANSFER", id, null, null, null, null, null,
                request.valueDate(), "Internal transfer", List.of(
                new JournalLine(sourceGl, request.sourceAccountId(), null, null, "DR", request.amount(), "Transfer out"),
                new JournalLine(targetGl, request.targetAccountId(), null, null, "CR", request.amount(), "Transfer in"))), actor);
        status(id, "VALIDATED", "POSTED", actor, null);
        outbox("m05.transaction.posted.v1", "TRANSACTION", id,
                Map.of("transactionId", id, "sourceAccountId", request.sourceAccountId(), "targetAccountId", request.targetAccountId(), "amount", request.amount()), request.correlationId());
        audit(actor, "TRANSFER_POSTED", "TRANSACTION", id, request.correlationId());
        return transaction(id);
    }

    /** Records a maker's request to reverse one posted internal transfer. */
    @Transactional
    public TransactionView requestReversal(long originalId, ReversalRequest request, String maker) {
        List<Map<String, Object>> replay = db.queryForList("SELECT TXN_ID,REQUEST_HASH FROM M05_TXN_TRANSACTION_LOG WHERE ORIGINATOR_ID=? AND CHANNEL_CODE='OPS' AND REQUEST_KEY=?", maker, request.requestKey());
        byte[] hash = sha256(List.of(originalId, request));
        if (!replay.isEmpty()) {
            if (!Arrays.equals((byte[]) replay.get(0).get("REQUEST_HASH"), hash))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Request key was reused with another payload");
            return transaction(((Number) replay.get(0).get("TXN_ID")).longValue());
        }
        List<Map<String, Object>> originals = db.queryForList("SELECT TXN_ID,STATUS,TXN_TYPE,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,AMOUNT FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=? FOR UPDATE", originalId);
        if (originals.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction does not exist");
        Map<String, Object> original = originals.get(0);
        if (!"POSTED".equals(original.get("STATUS")) || !"INTERNAL_TRANSFER".equals(original.get("TXN_TYPE")))
            throw new ApiException(HttpStatus.CONFLICT, "REVERSAL_NOT_ALLOWED", "Only a posted internal transfer can be reversed here");
        Integer prior = db.queryForObject("SELECT COUNT(*) FROM M05_TXN_TRANSACTION_LOG WHERE REVERSAL_OF_TXN_ID=?", Integer.class, originalId);
        if (prior != null && prior > 0) throw new ApiException(HttpStatus.CONFLICT, "REVERSAL_EXISTS", "A reversal already exists");
        long id = insertId("INSERT INTO M05_TXN_TRANSACTION_LOG(ORIGINATOR_ID,CHANNEL_CODE,REQUEST_KEY,REQUEST_HASH,CORRELATION_ID,TXN_TYPE,STATUS,SOURCE_ACCOUNT_ID,TARGET_ACCOUNT_ID,AMOUNT,VALUE_DATE,REVERSAL_OF_TXN_ID) VALUES (?,'OPS',?,?,?,'REVERSAL','RECEIVED',?,?,?,?,?)",
                "TXN_ID", maker, request.requestKey(), hash, request.correlationId(), original.get("TARGET_ACCOUNT_ID"), original.get("SOURCE_ACCOUNT_ID"), original.get("AMOUNT"), request.valueDate(), originalId);
        history(id, null, "RECEIVED", maker, null);
        status(id, "RECEIVED", "APPROVAL_PENDING", maker, null);
        db.update("INSERT INTO M05_TXN_APPROVAL(APPROVAL_KEY,TXN_ID,ACTION_CODE,MAKER_ID,REASON_TEXT) VALUES (?,?,'REVERSAL',?,?)",
                request.requestKey(), id, maker, request.reasonText());
        outbox("m05.reversal.requested.v1", "TRANSACTION", id, Map.of("transactionId", id, "originalTransactionId", originalId), request.correlationId());
        audit(maker, "REVERSAL_REQUESTED", "TRANSACTION", id, request.correlationId());
        return transaction(id);
    }

    /** Enforces maker-checker separation and books opposite entries on approval. */
    @Transactional
    public TransactionView decideReversal(long reversalId, ApprovalDecision decision, String checker) {
        List<Map<String, Object>> approvals = db.queryForList("SELECT APPROVAL_ID,MAKER_ID,STATUS FROM M05_TXN_APPROVAL WHERE TXN_ID=? AND ACTION_CODE='REVERSAL' FOR UPDATE", reversalId);
        if (approvals.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "Reversal approval does not exist");
        Map<String, Object> approval = approvals.get(0);
        if (!"PENDING".equals(approval.get("STATUS"))) throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_CLOSED", "Approval has already been decided");
        if (checker.equals(approval.get("MAKER_ID"))) throw new ApiException(HttpStatus.FORBIDDEN, "MAKER_CHECKER", "Maker cannot approve or reject their own reversal");
        if (!List.of("APPROVED", "REJECTED").contains(decision.decision())) throw invalid("DECISION", "Decision must be APPROVED or REJECTED");
        List<Map<String, Object>> reversals = db.queryForList("SELECT REVERSAL_OF_TXN_ID,CORRELATION_ID,VALUE_DATE FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=? AND STATUS='APPROVAL_PENDING' FOR UPDATE", reversalId);
        if (reversals.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "STATE_CHANGED", "Reversal is not pending");
        long originalId = ((Number) reversals.get(0).get("REVERSAL_OF_TXN_ID")).longValue();
        if ("APPROVED".equals(decision.decision())) {
            List<Map<String, Object>> journal = db.queryForList("SELECT JOURNAL_ID FROM M05_GL_JOURNAL WHERE TXN_ID=? AND JOURNAL_TYPE='TRANSFER'", originalId);
            if (journal.size() != 1) throw new ApiException(HttpStatus.CONFLICT, "ORIGINAL_JOURNAL_MISSING", "Original transfer journal is missing");
            long journalId = ((Number) journal.get(0).get("JOURNAL_ID")).longValue();
            List<JournalLine> lines = db.query("SELECT GL_ACCOUNT_ID,BANK_ACCOUNT_ID,LOAN_FACILITY_ID,LOAN_COMPONENT_CODE,ENTRY_SIDE,AMOUNT,NARRATIVE FROM M05_GL_POSTING WHERE JOURNAL_ID=? ORDER BY LINE_NO",
                    (rs, n) -> new JournalLine(rs.getLong(1), nullableLong(rs.getObject(2)), nullableLong(rs.getObject(3)), rs.getString(4),
                            "DR".equals(rs.getString(5)) ? "CR" : "DR", rs.getBigDecimal(6), "Reversal of " + rs.getString(7)), journalId);
            status(reversalId, "APPROVAL_PENDING", "VALIDATED", checker, null);
            LocalDate valueDate = db.queryForObject("SELECT VALUE_DATE FROM M05_TXN_TRANSACTION_LOG WHERE TXN_ID=?",
                    (rs, n) -> rs.getDate(1).toLocalDate(), reversalId);
            postJournal(new JournalRequest("reversal:" + reversalId, "ADJUSTMENT", reversalId, null, null, null,
                    null, journalId, valueDate, "Approved reversal", lines), checker);
            status(reversalId, "VALIDATED", "POSTED", checker, null);
            status(originalId, "POSTED", "REVERSED", checker, null);
        } else {
            status(reversalId, "APPROVAL_PENDING", "REJECTED", checker, null);
        }
        db.update("UPDATE M05_TXN_APPROVAL SET STATUS=?,CHECKER_ID=?,DECIDED_AT=SYSTIMESTAMP WHERE APPROVAL_ID=?",
                decision.decision(), checker, approval.get("APPROVAL_ID"));
        outbox("m05.reversal.decided.v1", "TRANSACTION", reversalId,
                Map.of("transactionId", reversalId, "originalTransactionId", originalId, "decision", decision.decision()), (String) reversals.get(0).get("CORRELATION_ID"));
        audit(checker, "REVERSAL_DECIDED", "TRANSACTION", reversalId, (String) reversals.get(0).get("CORRELATION_ID"));
        return transaction(reversalId);
    }

    /** Reads one transaction and its first journal, if one exists. */
    public TransactionView transaction(long id) {
        List<TransactionView> rows = db.query("SELECT T.TXN_ID,T.STATUS,(SELECT MIN(J.JOURNAL_ID) FROM M05_GL_JOURNAL J WHERE J.TXN_ID=T.TXN_ID),T.TXN_TYPE,T.AMOUNT,T.CURRENCY_CODE,T.CORRELATION_ID FROM M05_TXN_TRANSACTION_LOG T WHERE T.TXN_ID=?",
                (rs, n) -> new TransactionView(rs.getLong(1), rs.getString(2), nullableLong(rs.getObject(3)), rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7)), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction does not exist");
        return rows.get(0);
    }

    /** Returns GL balance checks for an immutable journal. */
    public Map<String, Object> journalControl(long journalId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT JOURNAL_ID,LINE_COUNT,DEBIT_TOTAL,CREDIT_TOTAL,BALANCE_DIFFERENCE,IS_BALANCED FROM M05_V_GL_JOURNAL_CONTROL WHERE JOURNAL_ID=?", journalId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "JOURNAL_NOT_FOUND", "Journal does not exist");
        return rows.get(0);
    }

    /** Returns account positions that differ from the immutable posting-derived balance. */
    public List<Map<String, Object>> positionExceptions() {
        return db.queryForList("SELECT BANK_ACCOUNT_ID,POSTED_BALANCE,GL_DERIVED_BALANCE,DIFFERENCE,AS_OF FROM M05_V_ACCOUNT_POSITION_RECON WHERE IS_MATCHED='N' ORDER BY BANK_ACCOUNT_ID FETCH FIRST 200 ROWS ONLY");
    }

    /** Returns journals that fail the double-entry control view. */
    public List<Map<String, Object>> unbalancedJournals() {
        return db.queryForList("SELECT JOURNAL_ID,LINE_COUNT,DEBIT_TOTAL,CREDIT_TOTAL,BALANCE_DIFFERENCE FROM M05_V_GL_JOURNAL_CONTROL WHERE IS_BALANCED='N' ORDER BY JOURNAL_ID FETCH FIRST 200 ROWS ONLY");
    }

    /** Finds the active account liability mapping selected by Product Master version. */
    private long mappedGl(long productVersionId, String postingType, String role, LocalDate valueDate) {
        List<Long> ids = db.query("SELECT GL_ACCOUNT_ID FROM M05_GL_PRODUCT_MAPPING WHERE PRODUCT_VERSION_ID=? AND POSTING_TYPE=? AND GL_ROLE_CODE=? AND STATUS='ACTIVE' AND EFFECTIVE_FROM<=? AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>=?) ORDER BY EFFECTIVE_FROM DESC FETCH FIRST 1 ROW ONLY",
                (rs, n) -> rs.getLong(1), productVersionId, postingType, role, valueDate, valueDate);
        if (ids.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "GL_MAPPING_MISSING", "Product version has no active GL mapping");
        return ids.get(0);
    }

    /** Appends a schema-valid transaction state transition and its audit history. */
    private void status(long id, String from, String to, String actor, String reason) {
        int updated = db.update("UPDATE M05_TXN_TRANSACTION_LOG SET STATUS=?,UPDATED_AT=SYSTIMESTAMP WHERE TXN_ID=? AND STATUS=?", to, id, from);
        if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "STATE_CHANGED", "Transaction state changed concurrently");
        history(id, from, to, actor, reason);
    }

    /** Appends status history, which the supplied Oracle schema makes immutable. */
    private void history(long id, String from, String to, String actor, String reason) {
        db.update("INSERT INTO M05_TXN_STATUS_HISTORY(TXN_ID,FROM_STATUS,TO_STATUS,ACTOR_ID,REASON_CODE) VALUES (?,?,?,?,?)", id, from, to, actor, reason);
    }

    /** Serializes an event in the same Oracle transaction as its business change. */
    private void outbox(String type, String aggregateType, long aggregateId, Map<String, ?> payload, String correlation) {
        try {
            UUID eventId = UUID.randomUUID();
            db.update("INSERT INTO M05_OUTBOX_EVENT(EVENT_ID,EVENT_TYPE,SCHEMA_VERSION,AGGREGATE_TYPE,AGGREGATE_ID,CORRELATION_ID,PARTITION_KEY,PAYLOAD_JSON,OCCURRED_AT) VALUES (?,?,1,?,?,?,?,?,SYSTIMESTAMP)",
                    raw(eventId), type, aggregateType, Long.toString(aggregateId), correlation, Long.toString(aggregateId), json.writeValueAsString(payload));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize outbox payload", error);
        }
    }

    /** Adds a successful financial action to Module 5's security audit table. */
    private void audit(String actor, String action, String resourceType, long resourceId, String correlation) {
        db.update("INSERT INTO M05_SECURITY_AUDIT_EVENT(ACTOR_ID,CORRELATION_ID,ACTION_CODE,RESOURCE_TYPE,RESOURCE_ID,RESULT_CODE) VALUES (?,?,?,?,?,'SUCCESS')",
                actor, correlation, action, resourceType, Long.toString(resourceId));
    }

    /** Locks the account position before every spend or control mutation. */
    private PositionView lockedPosition(long accountId) {
        List<Long> rows = db.query("SELECT BANK_ACCOUNT_ID FROM M05_ACCOUNT_POSITION WHERE BANK_ACCOUNT_ID=? AND CURRENCY_CODE='INR' FOR UPDATE", (rs, n) -> rs.getLong(1), accountId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "POSITION_NOT_READY", "Account control has not initialized the position");
        return position(accountId);
    }

    /** Enforces the latest Module 4 directional posting fence. */
    private void requireFence(long accountId, String entrySide) {
        List<Map<String, Object>> rows = db.queryForList("SELECT DEBIT_STATUS,CREDIT_STATUS FROM M05_POSTING_FENCE WHERE BANK_ACCOUNT_ID=?", accountId);
        String column = "DR".equals(entrySide) ? "DEBIT_STATUS" : "CREDIT_STATUS";
        if (rows.isEmpty() || !"OPEN".equals(rows.get(0).get(column)))
            throw new ApiException(HttpStatus.CONFLICT, "POSTING_FENCE_CLOSED", "Account posting direction is closed");
    }

    /** Locks a hold after its account position is locked. */
    private Map<String, Object> requireHold(long holdId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT HOLD_ID,BANK_ACCOUNT_ID,PAYMENT_ID,STATUS,AMOUNT FROM M05_FUNDS_HOLD WHERE HOLD_ID=? FOR UPDATE", holdId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Hold does not exist");
        return rows.get(0);
    }

    /** Blocks a backdated journal after the most recent approved period close. */
    private void checkPeriod(LocalDate valueDate) {
        List<CloseRow> closes = db.query("SELECT CLOSED_THROUGH_DATE,STATUS FROM M05_GL_PERIOD_CLOSE WHERE STATUS IN ('PENDING','APPROVED') FOR UPDATE",
                (rs, n) -> new CloseRow(rs.getDate(1).toLocalDate(), rs.getString(2)));
        LocalDate closed = closes.stream().filter(row -> "APPROVED".equals(row.status()))
                .map(CloseRow::date)
                .max(LocalDate::compareTo).orElse(null);
        if (closed != null && !valueDate.isAfter(closed))
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_CLOSED", "Value date is in an approved closed period");
    }

    /** Validates the journal envelope before any financial write. */
    private void validateJournal(JournalRequest request) {
        if (request.lines() == null || request.lines().size() < 2)
            throw invalid("JOURNAL_LINES", "A journal needs at least two lines");
        if (request.lines().stream().anyMatch(line -> line.amount() == null || line.side() == null))
            throw invalid("JOURNAL_LINE", "Every line needs side and amount");
        long bankLines = request.lines().stream().filter(line -> line.bankAccountId() != null).count();
        long uniqueAccounts = request.lines().stream().map(JournalLine::bankAccountId).filter(Objects::nonNull).distinct().count();
        if (bankLines != uniqueAccounts)
            throw invalid("DUPLICATE_BANK_ACCOUNT", "A journal may contain only one line per bank account");
        if (!List.of("OPENING_FUNDING","DEPOSIT","WITHDRAWAL","TRANSFER","FEE","INTEREST","ADJUSTMENT",
                "PAYMENT_PROVISIONAL","PAYMENT_REFUND","SETTLEMENT","RETURN","LOAN_DISBURSEMENT","LOAN_REPAYMENT",
                "LOAN_ACCRUAL","LOAN_FEE","LOAN_WAIVER","LOAN_WRITE_OFF","LOAN_SETTLEMENT","CARD_PURCHASE","CARD_REFUND","CARD_REPAYMENT","CARD_INTEREST").contains(request.journalType()))
            throw invalid("JOURNAL_TYPE", "Unsupported journal type");
        if (request.transactionId() == null && request.paymentId() == null && request.settlementCycleId() == null)
            throw invalid("JOURNAL_SCOPE", "A transaction, payment, or settlement reference is required");
        if (request.paymentId() != null && request.settlementCycleId() != null)
            throw invalid("JOURNAL_SCOPE", "Payment and settlement references cannot coexist");
        BigDecimal debit = request.lines().stream().filter(l -> "DR".equals(l.side())).map(JournalLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = request.lines().stream().filter(l -> "CR".equals(l.side())).map(JournalLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debit.signum() <= 0 || debit.compareTo(credit) != 0)
            throw invalid("UNBALANCED_JOURNAL", "Debit and credit totals must match");
    }

    /** Validates positive INR cents without implicit rounding. */
    private void requireMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 18)
            throw invalid("AMOUNT", "Amount must be positive INR with no more than two decimals");
    }

    /** Inserts into an Oracle identity table and returns its generated numeric key. */
    private long insertId(String sql, String column, Object... values) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{column});
            for (int i = 0; i < values.length; i++) {
                Object value = values[i] instanceof LocalDate day ? java.sql.Date.valueOf(day) : values[i];
                ps.setObject(i + 1, value);
            }
            return ps;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Oracle did not return " + column);
        return id.longValue();
    }

    /** Converts the event UUID to Oracle RAW(16). */
    private byte[] raw(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    /** Hashes the request body for conflict-safe idempotency replay. */
    private byte[] sha256(Object request) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request));
        } catch (NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException("Cannot hash request", error);
        }
    }

    /** Fits the posting key into the schema's 100-character idempotency key. */
    private String idempotencyKey(String postingKey) {
        return java.util.HexFormat.of().formatHex(sha256(postingKey));
    }

    /** Converts Oracle NUMBER to a nullable Java identifier. */
    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /** Creates a concise input error. */
    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** Date and state of a locked period-close row. */
    private record CloseRow(LocalDate date, String status) { }
}
