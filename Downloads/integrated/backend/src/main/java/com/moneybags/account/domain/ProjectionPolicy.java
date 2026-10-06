package com.moneybags.account.domain;

import com.moneybags.account.api.ApiException;
import com.moneybags.account.api.Models.*;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;

/** Pure validation for Module 5's ordered read-only financial projection. */
public final class ProjectionPolicy {
    private ProjectionPolicy() { }
    /** Rejects gaps, replays, mismatched deltas, and negative control balances. */
    public static void validate(AccountView account, ProjectionEvent event) {
        if (event.version() != account.balanceSourceVersion() + 1) throw conflict("Projection version gap or replay");
        checkAfter(account.ledgerBalance(), event.ledgerDelta(), event.ledgerAfter(), "ledger");
        checkAfter(account.blockedBalance(), event.blockedDelta(), event.blockedAfter(), "blocked");
        checkAfter(account.lienBalance(), event.lienDelta(), event.lienAfter(), "lien");
        checkAfter(account.overdraftLimit(), event.overdraftDelta(), event.overdraftAfter(), "overdraft");
        if (event.blockedAfter().signum() < 0 || event.lienAfter().signum() < 0 ||
                event.overdraftAfter().signum() < 0) throw conflict("Negative projected control amount");
    }
    /** Checks one projected component against its previous value and event delta. */
    private static void checkAfter(BigDecimal before, BigDecimal delta, BigDecimal after, String component) {
        if (before.add(delta).compareTo(after) != 0)
            throw conflict("Incorrect " + component + " projection delta");
    }
    /** Returns a stable conflict error for an inconsistent source event. */
    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "EVENT_CONFLICT", message);
    }
}
