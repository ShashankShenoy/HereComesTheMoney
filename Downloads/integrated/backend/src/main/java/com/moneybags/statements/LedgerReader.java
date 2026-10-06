package com.moneybags.statements;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only Module 5 adapter for customer-account posted GL entries. */
@Component
public class LedgerReader {
    private final JdbcTemplate jdbc;
    public LedgerReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Posting(long journalId, long postingId, Long txnId, Long paymentId, Long loanId,
        OffsetDateTime bookedAt, LocalDate valueDate, String entryClass, BigDecimal signed,
        String narration) {}
    public record Cut(OffsetDateTime asOf, long maxJournal, long maxPosting,
        BigDecimal opening, BigDecimal closing, BigDecimal total, List<Posting> postings,
        String controlHash, String marker) {}
    /** Reads one repeatable ordered cut, using journal/posting IDs as the immutable fence. */
    public Cut cut(long accountId, LocalDate from, LocalDate through, OffsetDateTime asOf) {
        Long maxJournal = jdbc.queryForObject("""
            SELECT NVL(MAX(j.JOURNAL_ID),0) FROM M05_GL_JOURNAL j
            JOIN M05_GL_POSTING p ON p.JOURNAL_ID=j.JOURNAL_ID
            LEFT JOIN M05_TXN_TRANSACTION_LOG t ON t.TXN_ID=j.TXN_ID
            WHERE p.BANK_ACCOUNT_ID=? AND j.BOOKED_AT<=?
              
            """, Long.class, accountId, asOf);
        Long maxPosting = jdbc.queryForObject("""
            SELECT NVL(MAX(p.POSTING_ID),0) FROM M05_GL_JOURNAL j
            JOIN M05_GL_POSTING p ON p.JOURNAL_ID=j.JOURNAL_ID
            LEFT JOIN M05_TXN_TRANSACTION_LOG t ON t.TXN_ID=j.TXN_ID
            WHERE p.BANK_ACCOUNT_ID=? AND j.BOOKED_AT<=? AND j.JOURNAL_ID<=?
             
              
            """, Long.class, accountId, asOf, maxJournal);
        List<Posting> all = jdbc.query("""
            SELECT j.JOURNAL_ID,p.POSTING_ID,j.TXN_ID,j.PAYMENT_ID,j.LOAN_FACILITY_ID,
                   j.BOOKED_AT,j.VALUE_DATE,j.JOURNAL_TYPE,p.AMOUNT,p.ENTRY_SIDE,
                   a.NORMAL_SIDE,p.NARRATIVE,j.REVERSAL_OF_JOURNAL_ID
            FROM M05_GL_JOURNAL j JOIN M05_GL_POSTING p ON p.JOURNAL_ID=j.JOURNAL_ID
            JOIN M05_GL_ACCOUNT a ON a.GL_ACCOUNT_ID=p.GL_ACCOUNT_ID
            LEFT JOIN M05_TXN_TRANSACTION_LOG t ON t.TXN_ID=j.TXN_ID
            WHERE p.BANK_ACCOUNT_ID=? AND j.JOURNAL_ID<=? AND p.POSTING_ID<=?
              AND j.BOOKED_AT<=?
              
            ORDER BY j.BOOKED_AT,j.JOURNAL_ID,p.POSTING_ID
            """, (rs,n) -> new Posting(rs.getLong(1),rs.getLong(2),nullableLong(rs,3),
                nullableLong(rs,4),nullableLong(rs,5),rs.getObject(6,OffsetDateTime.class),
                rs.getDate(7).toLocalDate(), classify(rs.getString(8),rs.getObject(13)!=null),
                rs.getString(10).equals(rs.getString(11)) ? rs.getBigDecimal(9) : rs.getBigDecimal(9).negate(),
                rs.getString(12)), accountId,maxJournal,maxPosting,asOf);
        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        List<Posting> period = new java.util.ArrayList<>();
        for (Posting p : all) {
            if (p.valueDate().isBefore(from)) opening = opening.add(p.signed());
            else if (!p.valueDate().isAfter(through)) { period.add(p); total = total.add(p.signed()); }
        }
        BigDecimal closing = opening.add(total);
        StringBuilder canonical = new StringBuilder(accountId+"|"+from+"|"+through+"|"+opening+"|"+closing);
        for (Posting p : period) canonical.append('|').append(p.journalId()).append(':').append(p.postingId()).append(':').append(p.signed());
        String marker = asOf.toInstant()+":"+maxJournal+":"+maxPosting+":"+from+":"+through;
        return new Cut(asOf,maxJournal,maxPosting,opening,closing,total,List.copyOf(period),
            Hashing.sha256(canonical.toString()),marker);
    }
    /** Classifies independently posted reversals and returns for the statement line. */
    private String classify(String journalType,boolean reversesPrior) {
        if(reversesPrior) return "REVERSAL";
        return switch (journalType) {
            case "RETURN", "PAYMENT_REFUND" -> "RETURN";
            case "ADJUSTMENT" -> "ADJUSTMENT";
            default -> "NORMAL";
        };
    }
    /** Preserves null optional source references returned as Oracle NUMBER. */
    private Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
