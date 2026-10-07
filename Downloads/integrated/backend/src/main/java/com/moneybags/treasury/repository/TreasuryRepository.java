package com.moneybags.treasury.repository;

import static com.moneybags.treasury.domain.TreasuryModels.*;

import com.moneybags.treasury.api.TreasuryRequests.*;
import com.moneybags.treasury.domain.DomainException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** Oracle SQL adapter. All cross-module identifiers remain opaque values, never joins. */
@Repository
public class TreasuryRepository {
    private final JdbcClient jdbc;
    private final SimpleJdbcInsert workItemInsert;

    public TreasuryRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.workItemInsert = new SimpleJdbcInsert(dataSource).withTableName("M07_TREASURY_WORK_ITEM")
            .usingGeneratedKeyColumns("WORK_ITEM_ID");
    }

    /** Creates an active reserve account and its zero-valued position projection. */
    public ReserveAccount createReserveAccount(CreateReserveAccount command) {
        jdbc.sql("""
            INSERT INTO M07_RESERVE_ACCOUNT
              (RESERVE_ACCOUNT_CODE, ACCOUNT_TYPE, EXTERNAL_ACCOUNT_REF, GL_ACCOUNT_ID,
               CURRENCY_CODE, SAFETY_BUFFER_AMOUNT, WARNING_THRESHOLD_AMOUNT)
            VALUES (:code, :type, :externalRef, :glAccountId, 'INR', :buffer, :warning)
            """).param("code", command.code()).param("type", command.accountType())
            .param("externalRef", command.externalAccountRef()).param("glAccountId", command.glAccountId())
            .param("buffer", command.safetyBuffer()).param("warning", command.warningThreshold()).update();
        ReserveAccount created = jdbc.sql("SELECT * FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_CODE=:code")
            .param("code", command.code()).query(this::reserveAccount).single();
        jdbc.sql("""
            INSERT INTO M07_RESERVE_POSITION
              (RESERVE_ACCOUNT_ID, CURRENCY_CODE, CONFIRMED_BALANCE, ACTIVE_HOLD_AMOUNT, VERSION_NO)
            VALUES (:id, 'INR', 0, 0, 0)
            """).param("id", created.id()).update();
        return created;
    }

    /** Returns all configured reserve accounts in stable identifier order. */
    public List<ReserveAccount> findReserveAccounts() {
        return jdbc.sql("SELECT * FROM M07_RESERVE_ACCOUNT ORDER BY RESERVE_ACCOUNT_ID")
            .query(this::reserveAccount).list();
    }

    /** Resolves one reserve account. */
    public Optional<ReserveAccount> findReserveAccount(long id) {
        return jdbc.sql("SELECT * FROM M07_RESERVE_ACCOUNT WHERE RESERVE_ACCOUNT_ID=:id")
            .param("id", id).query(this::reserveAccount).optional();
    }

    /** Reads the current reserve projection without treating it as posting authority. */
    public Optional<ReservePosition> findPosition(long accountId) {
        return jdbc.sql("SELECT * FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=:id AND CURRENCY_CODE='INR'")
            .param("id", accountId).query(this::position).optional();
    }

    /** Reads a bounded page of confirmed local reserve movements, newest first. */
    public List<ReserveLedgerLine> findLedger(long accountId, int limit, int offset) {
        return jdbc.sql("""
            SELECT L.*, E.EVIDENCE_STATUS FROM M07_CENTRAL_TREASURY_LEDGER L
            JOIN M07_SETTLEMENT_EVIDENCE E ON E.EVIDENCE_ID=L.EVIDENCE_ID
            WHERE L.RESERVE_ACCOUNT_ID=:accountId
            ORDER BY L.TREASURY_ENTRY_ID DESC OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
            """).param("accountId", accountId).param("offset", offset).param("limit", limit)
            .query(this::reserveLedgerLine).list();
    }

    /** Compares the event-fed reserve position with the append-only local mirror. */
    public Optional<ReserveReconciliation> findReserveReconciliation(long accountId) {
        return jdbc.sql("SELECT * FROM M07_V_RESERVE_POSITION_RECON WHERE RESERVE_ACCOUNT_ID=:id")
            .param("id", accountId).query(this::reserveReconciliation).optional();
    }

    /** Locks a reserve position until the surrounding transaction commits. */
    public ReservePosition lockPosition(long accountId) {
        return jdbc.sql("SELECT * FROM M07_RESERVE_POSITION WHERE RESERVE_ACCOUNT_ID=:id AND CURRENCY_CODE='INR' FOR UPDATE")
            .param("id", accountId).query(this::position).optional()
            .orElseThrow(() -> DomainException.notFound("Reserve position", accountId));
    }

    /** Inserts an idempotent liquidity hold after the caller has locked the position. */
    public LiquidityHold insertHold(CreateLiquidityHold command, byte[] requestHash, String responseReference) {
        jdbc.sql("""
            INSERT INTO M07_TREASURY_LIQUIDITY_HOLD
              (HOLD_KEY, RESERVE_ACCOUNT_ID, RAIL_CODE, PAYMENT_ID, SETTLEMENT_CYCLE_ID,
               AMOUNT, CURRENCY_CODE, STATUS, REQUEST_HASH, RESPONSE_REFERENCE, EXPIRES_AT)
            VALUES (:key, :accountId, :rail, :paymentId, :cycleId, :amount, 'INR',
                    'RESERVED', :requestHash, :responseRef, :expiresAt)
            """).param("key", command.holdKey()).param("accountId", command.reserveAccountId())
            .param("rail", command.railCode()).param("paymentId", command.paymentId())
            .param("cycleId", command.settlementCycleId()).param("amount", command.amount())
            .param("requestHash", requestHash).param("responseRef", responseReference)
            .param("expiresAt", command.expiresAt()).update();
        jdbc.sql("""
            UPDATE M07_RESERVE_POSITION
               SET ACTIVE_HOLD_AMOUNT=ACTIVE_HOLD_AMOUNT+:amount, VERSION_NO=VERSION_NO+1, AS_OF=SYSTIMESTAMP
             WHERE RESERVE_ACCOUNT_ID=:accountId AND CURRENCY_CODE='INR'
            """).param("amount", command.amount()).param("accountId", command.reserveAccountId()).update();
        return findHoldByKey(command.holdKey()).orElseThrow();
    }

    /** Resolves the idempotency key used by Module 6. */
    public Optional<LiquidityHold> findHoldByKey(String holdKey) {
        return jdbc.sql("SELECT * FROM M07_TREASURY_LIQUIDITY_HOLD WHERE HOLD_KEY=:key")
            .param("key", holdKey).query(this::hold).optional();
    }

    /** Resolves and locks a hold for a state transition. */
    public LiquidityHold lockHold(long id) {
        return jdbc.sql("SELECT * FROM M07_TREASURY_LIQUIDITY_HOLD WHERE LIQUIDITY_HOLD_ID=:id FOR UPDATE")
            .param("id", id).query(this::hold).optional()
            .orElseThrow(() -> DomainException.notFound("Liquidity hold", id));
    }

    /** Lists holds using optional account and status filters. */
    public List<LiquidityHold> findHolds(Long accountId, String status) {
        return jdbc.sql("""
            SELECT * FROM M07_TREASURY_LIQUIDITY_HOLD
             WHERE (:accountId IS NULL OR RESERVE_ACCOUNT_ID=:accountId)
               AND (:status IS NULL OR STATUS=:status)
             ORDER BY CREATED_AT DESC FETCH FIRST 500 ROWS ONLY
            """).param("accountId", accountId).param("status", status).query(this::hold).list();
    }

    /** Commits or releases a reserved hold with an optimistic version predicate. */
    public void transitionHold(LiquidityHold hold, HoldDecision command) {
        boolean commit = "COMMIT".equals(command.decision());
        String sql = commit ? """
            UPDATE M07_TREASURY_LIQUIDITY_HOLD SET STATUS='COMMITTED', COMMITTED_AT=SYSTIMESTAMP,
              VERSION_NO=VERSION_NO+1 WHERE LIQUIDITY_HOLD_ID=:id AND VERSION_NO=:version
            """ : """
            UPDATE M07_TREASURY_LIQUIDITY_HOLD SET STATUS='RELEASED', RELEASE_REASON=:reason,
              CLOSED_AT=SYSTIMESTAMP, VERSION_NO=VERSION_NO+1
              WHERE LIQUIDITY_HOLD_ID=:id AND VERSION_NO=:version
            """;
        var statement = jdbc.sql(sql).param("id", hold.id()).param("version", command.expectedVersion());
        if (!commit) statement.param("reason", command.reason());
        if (statement.update() != 1) throw DomainException.conflict("STALE_VERSION", "Liquidity hold changed concurrently");
        if (!commit) decrementActiveHold(hold.reserveAccountId(), hold.amount());
    }

    /** Locks a bounded batch of expired RESERVED holds; committed rail exposure is never auto-released. */
    public List<LiquidityHold> lockExpiredHolds() {
        return jdbc.sql("""
            SELECT * FROM M07_TREASURY_LIQUIDITY_HOLD
             WHERE LIQUIDITY_HOLD_ID IN (
               SELECT LIQUIDITY_HOLD_ID FROM M07_TREASURY_LIQUIDITY_HOLD
                WHERE STATUS='RESERVED' AND EXPIRES_AT<SYSTIMESTAMP
                ORDER BY EXPIRES_AT FETCH FIRST 100 ROWS ONLY)
             FOR UPDATE SKIP LOCKED
            """).query(this::hold).list();
    }

    /** Expires one locked reservation and releases its position capacity. */
    public void expireHold(LiquidityHold hold) {
        jdbc.sql("""
            UPDATE M07_TREASURY_LIQUIDITY_HOLD SET STATUS='EXPIRED', RELEASE_REASON='EXPIRY_REACHED',
              CLOSED_AT=SYSTIMESTAMP, VERSION_NO=VERSION_NO+1 WHERE LIQUIDITY_HOLD_ID=:id AND STATUS='RESERVED'
            """).param("id", hold.id()).update();
        decrementActiveHold(hold.reserveAccountId(), hold.amount());
    }

    /** Releases reserved capacity from the locked position. */
    public void decrementActiveHold(long accountId, BigDecimal amount) {
        jdbc.sql("""
            UPDATE M07_RESERVE_POSITION SET ACTIVE_HOLD_AMOUNT=ACTIVE_HOLD_AMOUNT-:amount,
              VERSION_NO=VERSION_NO+1, AS_OF=SYSTIMESTAMP WHERE RESERVE_ACCOUNT_ID=:id AND CURRENCY_CODE='INR'
            """).param("amount", amount).param("id", accountId).update();
    }

    /** Creates one open settlement cycle. */
    public SettlementCycle createCycle(CreateCycle command) {
        jdbc.sql("""
            INSERT INTO M07_SETTLEMENT_CYCLE
              (RAIL_CODE, RESERVE_ACCOUNT_ID, CYCLE_REFERENCE, CLEARING_BATCH_ID, CYCLE_DIRECTION, CURRENCY_CODE)
            VALUES (:rail, :accountId, :reference, :batchId, :direction, 'INR')
            """).param("rail", command.railCode()).param("accountId", command.reserveAccountId())
            .param("reference", command.cycleReference()).param("batchId", command.clearingBatchId())
            .param("direction", command.direction()).update();
        return jdbc.sql("SELECT * FROM M07_SETTLEMENT_CYCLE WHERE RAIL_CODE=:rail AND CYCLE_REFERENCE=:reference")
            .param("rail", command.railCode()).param("reference", command.cycleReference())
            .query(this::cycle).single();
    }

    /** Locks a settlement cycle for item or lifecycle changes. */
    public SettlementCycle lockCycle(long id) {
        return jdbc.sql("SELECT * FROM M07_SETTLEMENT_CYCLE WHERE SETTLEMENT_CYCLE_ID=:id FOR UPDATE")
            .param("id", id).query(this::cycle).optional()
            .orElseThrow(() -> DomainException.notFound("Settlement cycle", id));
    }

    /** Resolves a cycle without a lock. */
    public Optional<SettlementCycle> findCycle(long id) {
        return jdbc.sql("SELECT * FROM M07_SETTLEMENT_CYCLE WHERE SETTLEMENT_CYCLE_ID=:id")
            .param("id", id).query(this::cycle).optional();
    }

    /** Lists recent cycles for operations screens. */
    public List<SettlementCycle> findCycles(String status, String rail) {
        return jdbc.sql("""
            SELECT * FROM M07_SETTLEMENT_CYCLE WHERE (:status IS NULL OR STATUS=:status)
              AND (:rail IS NULL OR RAIL_CODE=:rail) ORDER BY OPENED_AT DESC FETCH FIRST 500 ROWS ONLY
            """).param("status", status).param("rail", rail).query(this::cycle).list();
    }

    /** Adds one payment snapshot to an open cycle. */
    public void addCycleItem(long cycleId, CycleItem item) {
        jdbc.sql("""
            INSERT INTO M07_SETTLEMENT_CYCLE_ITEM
              (SETTLEMENT_CYCLE_ID, PAYMENT_ID, DIRECTION, AMOUNT)
            VALUES (:cycleId, :paymentId, :direction, :amount)
            """).param("cycleId", cycleId).param("paymentId", item.paymentId())
            .param("direction", item.direction()).param("amount", item.amount()).update();
    }

    /** Recalculates immutable cycle totals from included items. */
    public void recalculateCycle(long cycleId) {
        jdbc.sql("""
            UPDATE M07_SETTLEMENT_CYCLE c SET
              GROSS_OUT_AMOUNT=(SELECT NVL(SUM(CASE WHEN DIRECTION='OUT' THEN AMOUNT ELSE 0 END),0) FROM M07_SETTLEMENT_CYCLE_ITEM WHERE SETTLEMENT_CYCLE_ID=:id AND ITEM_STATUS='INCLUDED'),
              GROSS_IN_AMOUNT=(SELECT NVL(SUM(CASE WHEN DIRECTION='IN' THEN AMOUNT ELSE 0 END),0) FROM M07_SETTLEMENT_CYCLE_ITEM WHERE SETTLEMENT_CYCLE_ID=:id AND ITEM_STATUS='INCLUDED')
            WHERE c.SETTLEMENT_CYCLE_ID=:id
            """).param("id", cycleId).update();
        jdbc.sql("""
            UPDATE M07_SETTLEMENT_CYCLE SET NET_AMOUNT=ABS(GROSS_IN_AMOUNT-GROSS_OUT_AMOUNT),
              NET_MOVEMENT_SIDE=CASE WHEN GROSS_IN_AMOUNT=GROSS_OUT_AMOUNT THEN NULL
                WHEN GROSS_IN_AMOUNT>GROSS_OUT_AMOUNT THEN 'IN' ELSE 'OUT' END
            WHERE SETTLEMENT_CYCLE_ID=:id
            """).param("id", cycleId).update();
    }

    /** Closes an open cycle after totals have been locked. */
    public void closeCycle(long cycleId) {
        jdbc.sql("UPDATE M07_SETTLEMENT_CYCLE SET STATUS='CLOSED', CLOSED_AT=SYSTIMESTAMP WHERE SETTLEMENT_CYCLE_ID=:id AND STATUS='OPEN'")
            .param("id", cycleId).update();
    }

    /** Lists all item snapshots belonging to a cycle. */
    public List<SettlementCycleItem> findCycleItems(long cycleId) {
        return jdbc.sql("SELECT * FROM M07_SETTLEMENT_CYCLE_ITEM WHERE SETTLEMENT_CYCLE_ID=:id ORDER BY SETTLEMENT_CYCLE_ITEM_ID")
            .param("id", cycleId).query(this::cycleItem).list();
    }

    /** Stores append-only settlement evidence and returns the generated row. */
    public SettlementEvidence insertEvidence(SettlementEvidenceCommand command, byte[] evidenceHash) {
        jdbc.sql("""
            INSERT INTO M07_SETTLEMENT_EVIDENCE
              (RESERVE_ACCOUNT_ID, PAYMENT_ID, SETTLEMENT_CYCLE_ID, RAIL_CODE, EVIDENCE_TYPE,
               EVIDENCE_STATUS, MOVEMENT_SIDE, AMOUNT, CURRENCY_CODE, EXTERNAL_SETTLEMENT_REF,
               EXTERNAL_STATEMENT_REF, EVIDENCE_HASH, EVIDENCE_REF, OCCURRED_AT)
            VALUES (:accountId, :paymentId, :cycleId, :rail, :type, :status, :side, :amount,
                    'INR', :settlementRef, :statementRef, :hash, :evidenceRef, :occurredAt)
            """).param("accountId", command.reserveAccountId()).param("paymentId", command.paymentId())
            .param("cycleId", command.settlementCycleId()).param("rail", command.railCode())
            .param("type", command.evidenceType()).param("status", command.evidenceStatus())
            .param("side", command.movementSide()).param("amount", command.amount())
            .param("settlementRef", command.externalSettlementRef()).param("statementRef", command.externalStatementRef())
            .param("hash", evidenceHash).param("evidenceRef", command.evidenceRef())
            .param("occurredAt", command.occurredAt()).update();
        return jdbc.sql("""
            SELECT * FROM M07_SETTLEMENT_EVIDENCE
             WHERE RESERVE_ACCOUNT_ID=:accountId AND EXTERNAL_SETTLEMENT_REF=:reference
            """).param("accountId", command.reserveAccountId()).param("reference", command.externalSettlementRef())
            .query(this::evidence).single();
    }

    /** Resolves evidence needed to authorize a local reserve mirror entry. */
    public Optional<SettlementEvidence> findEvidence(long id) {
        return jdbc.sql("SELECT * FROM M07_SETTLEMENT_EVIDENCE WHERE EVIDENCE_ID=:id")
            .param("id", id).query(this::evidence).optional();
    }

    /** Appends one immutable reserve movement linked to a Module 5 GL journal. */
    public TreasuryEntry insertTreasuryEntry(SettlementEvidence evidence, long glJournalId) {
        jdbc.sql("""
            INSERT INTO M07_CENTRAL_TREASURY_LEDGER
              (RESERVE_ACCOUNT_ID, PAYMENT_ID, SETTLEMENT_CYCLE_ID, EVIDENCE_ID, RAIL_CODE,
               MOVEMENT_SIDE, AMOUNT, CURRENCY_CODE, EXTERNAL_SETTLEMENT_REF,
               EXTERNAL_STATEMENT_REF, GL_JOURNAL_ID, SETTLED_AT)
            VALUES (:accountId, :paymentId, :cycleId, :evidenceId, :rail, :side, :amount,
                    'INR', :settlementRef, :statementRef, :journalId, :settledAt)
            """).param("accountId", evidence.reserveAccountId()).param("paymentId", evidence.paymentId())
            .param("cycleId", evidence.cycleId()).param("evidenceId", evidence.id())
            .param("rail", evidence.railCode()).param("side", evidence.movementSide())
            .param("amount", evidence.amount()).param("settlementRef", evidence.externalSettlementRef())
            .param("statementRef", evidence.externalStatementRef()).param("journalId", glJournalId)
            .param("settledAt", evidence.occurredAt()).update();
        return jdbc.sql("SELECT * FROM M07_CENTRAL_TREASURY_LEDGER WHERE EVIDENCE_ID=:id")
            .param("id", evidence.id()).query(this::entry).single();
    }

    /** Finds a previously confirmed movement for idempotent retries. */
    public Optional<TreasuryEntry> findEntryByEvidence(long evidenceId) {
        return jdbc.sql("SELECT * FROM M07_CENTRAL_TREASURY_LEDGER WHERE EVIDENCE_ID=:id")
            .param("id", evidenceId).query(this::entry).optional();
    }

    /** Applies an appended ledger movement to the locked reserve projection. */
    public void applyEntryToPosition(TreasuryEntry entry) {
        jdbc.sql("""
            UPDATE M07_RESERVE_POSITION SET
              CONFIRMED_BALANCE=CONFIRMED_BALANCE+:delta,
              LAST_TREASURY_ENTRY_ID=:entryId, VERSION_NO=VERSION_NO+1, AS_OF=SYSTIMESTAMP
             WHERE RESERVE_ACCOUNT_ID=:accountId AND CURRENCY_CODE='INR'
            """).param("delta", "IN".equals(entry.movementSide()) ? entry.amount() : entry.amount().negate())
            .param("entryId", entry.id()).param("accountId", entry.reserveAccountId()).update();
    }

    /** Consumes the matching committed hold, if one exists. */
    public void consumeMatchingHold(TreasuryEntry entry) {
        if (!"OUT".equals(entry.movementSide())) return;
        List<LiquidityHold> holds = jdbc.sql("""
            SELECT * FROM M07_TREASURY_LIQUIDITY_HOLD
             WHERE STATUS='COMMITTED'
               AND ((:paymentId IS NOT NULL AND PAYMENT_ID=:paymentId)
                 OR (:cycleId IS NOT NULL AND SETTLEMENT_CYCLE_ID=:cycleId)) FOR UPDATE
            """).param("paymentId", entry.paymentId()).param("cycleId", entry.cycleId()).query(this::hold).list();
        for (LiquidityHold hold : holds) {
            if (hold.amount().compareTo(entry.amount()) != 0) {
                throw DomainException.conflict("HOLD_AMOUNT_MISMATCH", "Settlement amount differs from committed liquidity hold");
            }
            jdbc.sql("""
                UPDATE M07_TREASURY_LIQUIDITY_HOLD SET STATUS='CONSUMED', CONSUMED_BY_ENTRY_ID=:entryId,
                  CLOSED_AT=SYSTIMESTAMP, VERSION_NO=VERSION_NO+1 WHERE LIQUIDITY_HOLD_ID=:id
                """).param("entryId", entry.id()).param("id", hold.id()).update();
            decrementActiveHold(hold.reserveAccountId(), hold.amount());
        }
    }

    /** Marks a cycle and its items settled after its reserve movement is confirmed. */
    public void settleCycle(long cycleId) {
        jdbc.sql("""
            UPDATE M07_SETTLEMENT_CYCLE SET STATUS='SETTLED', SETTLED_AT=SYSTIMESTAMP
             WHERE SETTLEMENT_CYCLE_ID=:id AND STATUS IN ('CLOSED','SUBMITTED','SETTLEMENT_PENDING')
            """).param("id", cycleId).update();
        jdbc.sql("""
            UPDATE M07_SETTLEMENT_CYCLE_ITEM SET ITEM_STATUS='SETTLED'
             WHERE SETTLEMENT_CYCLE_ID=:id AND ITEM_STATUS='INCLUDED'
            """).param("id", cycleId).update();
    }

    /** Creates an operations reconciliation exception. */
    public ReconciliationException insertException(OpenException command) {
        jdbc.sql("""
            INSERT INTO M07_RECONCILIATION_EXCEPTION
              (MISMATCH_KEY, RESERVE_ACCOUNT_ID, PAYMENT_ID, SETTLEMENT_CYCLE_ID,
               TREASURY_ENTRY_ID, EXCEPTION_TYPE, SEVERITY, EVIDENCE_JSON)
            VALUES (:key, :accountId, :paymentId, :cycleId, :entryId, :type, :severity, :evidence)
            """).param("key", command.mismatchKey()).param("accountId", command.reserveAccountId())
            .param("paymentId", command.paymentId()).param("cycleId", command.settlementCycleId())
            .param("entryId", command.treasuryEntryId()).param("type", command.exceptionType())
            .param("severity", command.severity()).param("evidence", command.evidenceJson()).update();
        return jdbc.sql("SELECT * FROM M07_RECONCILIATION_EXCEPTION WHERE MISMATCH_KEY=:key")
            .param("key", command.mismatchKey()).query(this::reconException).single();
    }

    /** Lists the operational exception queue. */
    public List<ReconciliationException> findExceptions(String status, String severity) {
        return jdbc.sql("""
            SELECT * FROM M07_RECONCILIATION_EXCEPTION WHERE (:status IS NULL OR STATUS=:status)
              AND (:severity IS NULL OR SEVERITY=:severity)
             ORDER BY CASE SEVERITY WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END,
                      OPENED_AT FETCH FIRST 500 ROWS ONLY
            """).param("status", status).param("severity", severity).query(this::reconException).list();
    }

    /** Resolves or waives an open reconciliation exception. */
    public void resolveException(long id, ResolveException command) {
        int changed = jdbc.sql("""
            UPDATE M07_RECONCILIATION_EXCEPTION SET STATUS=:decision, RESOLUTION_CODE=:code,
              RESOLUTION_TEXT=:text, RESOLVED_AT=SYSTIMESTAMP
             WHERE EXCEPTION_ID=:id AND STATUS IN ('OPEN','ASSIGNED')
            """).param("decision", command.decision()).param("code", command.resolutionCode())
            .param("text", command.resolutionText()).param("id", id).update();
        if (changed != 1) throw DomainException.conflict("NOT_OPEN", "Exception is absent or already terminal");
    }

    /** Creates a maker/checker work item. */
    public WorkItem insertWorkItem(CreateWorkItem command) {
        var parameters = new MapSqlParameterSource()
            .addValue("WORK_TYPE", command.workType()).addValue("SUBJECT_TYPE", command.subjectType())
            .addValue("RESERVE_ACCOUNT_ID", command.reserveAccountId()).addValue("PAYMENT_ID", command.paymentId())
            .addValue("SETTLEMENT_CYCLE_ID", command.settlementCycleId()).addValue("SOURCE_REFERENCE", command.sourceReference())
            .addValue("EXPECTED_AMOUNT", command.expectedAmount()).addValue("OBSERVED_AMOUNT", command.observedAmount())
            .addValue("DETAIL_TEXT", command.detailText()).addValue("OWNER_ID", command.ownerId());
        long id = workItemInsert.executeAndReturnKey(parameters).longValue();
        return jdbc.sql("SELECT * FROM M07_TREASURY_WORK_ITEM WHERE WORK_ITEM_ID=:id")
            .param("id", id).query(this::workItem).single();
    }

    /** Lists maker/checker work by status and owner. */
    public List<WorkItem> findWorkItems(String status, String ownerId) {
        return jdbc.sql("""
            SELECT * FROM M07_TREASURY_WORK_ITEM WHERE (:status IS NULL OR STATUS=:status)
              AND (:owner IS NULL OR OWNER_ID=:owner) ORDER BY OPENED_AT FETCH FIRST 500 ROWS ONLY
            """).param("status", status).param("owner", ownerId).query(this::workItem).list();
    }

    /** Records the maker submission before an independent checker decision. */
    public void submitWork(long id, String makerUserId, int expectedVersion) {
        int changed = jdbc.sql("""
            UPDATE M07_TREASURY_WORK_ITEM SET STATUS='PENDING_APPROVAL', MAKER_USER_ID=:maker,
              MADE_AT=SYSTIMESTAMP, VERSION_NO=VERSION_NO+1
             WHERE WORK_ITEM_ID=:id AND STATUS IN ('OPEN','ASSIGNED') AND VERSION_NO=:version
            """).param("maker", makerUserId).param("id", id).param("version", expectedVersion).update();
        if (changed != 1) throw DomainException.conflict("STALE_WORK_ITEM", "Work item changed or cannot be submitted");
    }

    /** Applies an independent checker decision with separation-of-duties enforcement. */
    public void decideWork(long id, WorkDecision command) {
        int changed = jdbc.sql("""
            UPDATE M07_TREASURY_WORK_ITEM SET STATUS=:decision, CHECKER_USER_ID=:checker,
              CHECKED_AT=SYSTIMESTAMP, RESOLVED_AT=SYSTIMESTAMP, GL_JOURNAL_ID=:journal,
              VERSION_NO=VERSION_NO+1
             WHERE WORK_ITEM_ID=:id AND STATUS='PENDING_APPROVAL' AND VERSION_NO=:version
               AND MAKER_USER_ID<>:checker
            """).param("decision", command.decision()).param("checker", command.checkerUserId())
            .param("journal", command.glJournalId()).param("id", id)
            .param("version", command.expectedVersion()).update();
        if (changed != 1) throw DomainException.conflict("CHECKER_CONFLICT", "Stale item, invalid state, or maker/checker collision");
    }

    /** Inserts an outbox event in the caller's local business transaction. */
    public void insertOutbox(UUID eventId, String type, String aggregateType, String aggregateId,
                             String correlationId, String payload) {
        jdbc.sql("""
            INSERT INTO M07_OUTBOX_EVENT
              (EVENT_ID, EVENT_TYPE, SCHEMA_VERSION, AGGREGATE_TYPE, AGGREGATE_ID,
               CORRELATION_ID, PARTITION_KEY, PAYLOAD_JSON, OCCURRED_AT)
            VALUES (:eventId, :type, 1, :aggregateType, :aggregateId,
                    :correlationId, :aggregateId, :payload, SYSTIMESTAMP)
            """).param("eventId", uuidBytes(eventId)).param("type", type)
            .param("aggregateType", aggregateType).param("aggregateId", aggregateId)
            .param("correlationId", correlationId).param("payload", payload).update();
    }

    /** Claims an inbound event; false means this consumer has already applied it. */
    public boolean claimInbox(String consumerName, UUID eventId, String eventType) {
        try {
            return jdbc.sql("""
                INSERT INTO M07_CONSUMER_INBOX (CONSUMER_NAME, EVENT_ID, EVENT_TYPE)
                VALUES (:consumer, :eventId, :eventType)
                """).param("consumer", consumerName).param("eventId", uuidBytes(eventId))
                .param("eventType", eventType).update() == 1;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** Marks an inbound event processed in the same transaction as its effect. */
    public void completeInbox(String consumerName, UUID eventId, String resultCode) {
        jdbc.sql("""
            UPDATE M07_CONSUMER_INBOX SET PROCESSED_AT=SYSTIMESTAMP, RESULT_CODE=:result
             WHERE CONSUMER_NAME=:consumer AND EVENT_ID=:eventId
            """).param("result", resultCode).param("consumer", consumerName)
            .param("eventId", uuidBytes(eventId)).update();
    }

    private ReserveAccount reserveAccount(ResultSet rs, int n) throws SQLException {
        return new ReserveAccount(rs.getLong("RESERVE_ACCOUNT_ID"), rs.getString("RESERVE_ACCOUNT_CODE"),
            rs.getString("ACCOUNT_TYPE"), rs.getString("EXTERNAL_ACCOUNT_REF"), rs.getLong("GL_ACCOUNT_ID"),
            rs.getString("CURRENCY_CODE"), rs.getString("ACCOUNT_STATUS"), rs.getBigDecimal("SAFETY_BUFFER_AMOUNT"),
            rs.getBigDecimal("WARNING_THRESHOLD_AMOUNT"), time(rs, "EFFECTIVE_FROM"), time(rs, "EFFECTIVE_TO"));
    }

    private ReservePosition position(ResultSet rs, int n) throws SQLException {
        return new ReservePosition(rs.getLong("RESERVE_ACCOUNT_ID"), rs.getString("CURRENCY_CODE"),
            rs.getBigDecimal("CONFIRMED_BALANCE"), rs.getBigDecimal("ACTIVE_HOLD_AMOUNT"),
            rs.getBigDecimal("AVAILABLE_BALANCE"), nullableLong(rs, "LAST_TREASURY_ENTRY_ID"),
            rs.getLong("VERSION_NO"), time(rs, "AS_OF"));
    }

    private LiquidityHold hold(ResultSet rs, int n) throws SQLException {
        return new LiquidityHold(rs.getLong("LIQUIDITY_HOLD_ID"), rs.getString("HOLD_KEY"),
            rs.getLong("RESERVE_ACCOUNT_ID"), rs.getString("RAIL_CODE"), nullableLong(rs, "PAYMENT_ID"),
            nullableLong(rs, "SETTLEMENT_CYCLE_ID"), rs.getBigDecimal("AMOUNT"), rs.getString("CURRENCY_CODE"),
            HoldStatus.valueOf(rs.getString("STATUS")), rs.getBytes("REQUEST_HASH"), rs.getString("RESPONSE_REFERENCE"), time(rs, "EXPIRES_AT"),
            time(rs, "COMMITTED_AT"), nullableLong(rs, "CONSUMED_BY_ENTRY_ID"), rs.getString("RELEASE_REASON"),
            time(rs, "CREATED_AT"), time(rs, "CLOSED_AT"), rs.getInt("VERSION_NO"));
    }

    private SettlementCycle cycle(ResultSet rs, int n) throws SQLException {
        return new SettlementCycle(rs.getLong("SETTLEMENT_CYCLE_ID"), rs.getString("RAIL_CODE"),
            rs.getLong("RESERVE_ACCOUNT_ID"), rs.getString("CYCLE_REFERENCE"), nullableLong(rs, "CLEARING_BATCH_ID"),
            rs.getString("CYCLE_DIRECTION"), rs.getString("CURRENCY_CODE"), rs.getBigDecimal("GROSS_OUT_AMOUNT"),
            rs.getBigDecimal("GROSS_IN_AMOUNT"), rs.getBigDecimal("NET_AMOUNT"), rs.getString("NET_MOVEMENT_SIDE"),
            CycleStatus.valueOf(rs.getString("STATUS")), time(rs, "OPENED_AT"), time(rs, "CLOSED_AT"), time(rs, "SETTLED_AT"));
    }

    private SettlementCycleItem cycleItem(ResultSet rs, int n) throws SQLException {
        return new SettlementCycleItem(rs.getLong("SETTLEMENT_CYCLE_ITEM_ID"), rs.getLong("SETTLEMENT_CYCLE_ID"),
            rs.getLong("PAYMENT_ID"), rs.getString("DIRECTION"), rs.getBigDecimal("AMOUNT"), rs.getString("ITEM_STATUS"),
            time(rs, "INCLUDED_AT"), time(rs, "RECONCILED_AT"));
    }

    private SettlementEvidence evidence(ResultSet rs, int n) throws SQLException {
        return new SettlementEvidence(rs.getLong("EVIDENCE_ID"), rs.getLong("RESERVE_ACCOUNT_ID"),
            nullableLong(rs, "PAYMENT_ID"), nullableLong(rs, "SETTLEMENT_CYCLE_ID"), rs.getString("RAIL_CODE"),
            rs.getString("EVIDENCE_TYPE"), rs.getString("EVIDENCE_STATUS"), rs.getString("MOVEMENT_SIDE"),
            rs.getBigDecimal("AMOUNT"), rs.getString("EXTERNAL_SETTLEMENT_REF"), rs.getString("EXTERNAL_STATEMENT_REF"),
            time(rs, "OCCURRED_AT"), time(rs, "RECEIVED_AT"));
    }

    private TreasuryEntry entry(ResultSet rs, int n) throws SQLException {
        return new TreasuryEntry(rs.getLong("TREASURY_ENTRY_ID"), rs.getLong("RESERVE_ACCOUNT_ID"),
            nullableLong(rs, "PAYMENT_ID"), nullableLong(rs, "SETTLEMENT_CYCLE_ID"), rs.getLong("EVIDENCE_ID"),
            rs.getString("RAIL_CODE"), rs.getString("MOVEMENT_SIDE"), rs.getBigDecimal("AMOUNT"),
            rs.getLong("GL_JOURNAL_ID"), time(rs, "SETTLED_AT"), time(rs, "RECORDED_AT"));
    }

    private ReserveLedgerLine reserveLedgerLine(ResultSet rs, int n) throws SQLException {
        return new ReserveLedgerLine(rs.getLong("TREASURY_ENTRY_ID"), rs.getLong("RESERVE_ACCOUNT_ID"),
            nullableLong(rs, "PAYMENT_ID"), nullableLong(rs, "SETTLEMENT_CYCLE_ID"), rs.getLong("EVIDENCE_ID"),
            rs.getString("EVIDENCE_STATUS"), rs.getString("RAIL_CODE"), rs.getString("MOVEMENT_SIDE"),
            rs.getBigDecimal("AMOUNT"), rs.getString("CURRENCY_CODE"), rs.getString("EXTERNAL_SETTLEMENT_REF"),
            rs.getString("EXTERNAL_STATEMENT_REF"), rs.getLong("GL_JOURNAL_ID"),
            time(rs, "SETTLED_AT"), time(rs, "RECORDED_AT"));
    }

    private ReserveReconciliation reserveReconciliation(ResultSet rs, int n) throws SQLException {
        return new ReserveReconciliation(rs.getLong("RESERVE_ACCOUNT_ID"), rs.getString("CURRENCY_CODE"),
            rs.getBigDecimal("CONFIRMED_BALANCE"), rs.getBigDecimal("LEDGER_BALANCE"),
            rs.getBigDecimal("DIFFERENCE"), rs.getString("IS_MATCHED"), time(rs, "AS_OF"));
    }

    private ReconciliationException reconException(ResultSet rs, int n) throws SQLException {
        return new ReconciliationException(rs.getLong("EXCEPTION_ID"), rs.getString("MISMATCH_KEY"),
            rs.getLong("RESERVE_ACCOUNT_ID"), nullableLong(rs, "PAYMENT_ID"), nullableLong(rs, "SETTLEMENT_CYCLE_ID"),
            nullableLong(rs, "TREASURY_ENTRY_ID"), rs.getString("EXCEPTION_TYPE"),
            ExceptionStatus.valueOf(rs.getString("STATUS")), rs.getString("SEVERITY"), rs.getString("OWNER_ID"),
            rs.getString("EVIDENCE_JSON"), rs.getString("RESOLUTION_CODE"), rs.getString("RESOLUTION_TEXT"),
            time(rs, "OPENED_AT"), time(rs, "RESOLVED_AT"));
    }

    private WorkItem workItem(ResultSet rs, int n) throws SQLException {
        return new WorkItem(rs.getLong("WORK_ITEM_ID"), rs.getString("WORK_TYPE"), rs.getString("SUBJECT_TYPE"),
            rs.getLong("RESERVE_ACCOUNT_ID"), nullableLong(rs, "PAYMENT_ID"), nullableLong(rs, "SETTLEMENT_CYCLE_ID"),
            rs.getString("SOURCE_REFERENCE"), rs.getBigDecimal("EXPECTED_AMOUNT"), rs.getBigDecimal("OBSERVED_AMOUNT"),
            rs.getString("DETAIL_TEXT"), rs.getString("OWNER_ID"), WorkStatus.valueOf(rs.getString("STATUS")),
            rs.getString("MAKER_USER_ID"), rs.getString("CHECKER_USER_ID"), nullableLong(rs, "GL_JOURNAL_ID"),
            time(rs, "OPENED_AT"), time(rs, "RESOLVED_AT"), rs.getInt("VERSION_NO"));
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static byte[] uuidBytes(UUID id) {
        return HexFormat.of().parseHex(id.toString().replace("-", ""));
    }

    /** Appends security-relevant action evidence without storing secrets or request bodies. */
    public void insertAudit(String actorId, String correlationId, String action, String resourceType,
                            String resourceId, String result, String reason) {
        jdbc.sql("""
            INSERT INTO M07_SECURITY_AUDIT_EVENT
              (ACTOR_ID, CORRELATION_ID, ACTION_CODE, RESOURCE_TYPE, RESOURCE_ID, RESULT_CODE, REASON_CODE)
            VALUES (:actor, :correlation, :action, :resourceType, :resourceId, :result, :reason)
            """).param("actor", actorId).param("correlation", correlationId).param("action", action)
            .param("resourceType", resourceType).param("resourceId", resourceId)
            .param("result", result).param("reason", reason).update();
    }
}
