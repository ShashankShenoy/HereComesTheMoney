package com.moneybags;

import com.moneybags.privacy.audit.ConsentAuditWriter;
import com.moneybags.privacy.common.ApiException;
import com.moneybags.privacy.common.RequestContext;
import com.moneybags.privacy.consent.ConsentDecision;
import com.moneybags.privacy.consent.ConsentRepository;
import com.moneybags.privacy.consent.ConsentService;
import com.moneybags.privacy.consent.SubjectVerificationPort;
import com.moneybags.privacy.outbox.OutboxService;
import com.moneybags.privacy.purpose.PurposeRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivacyConsentReplayTest {
    @Test void changedEvidenceOrActorCannotReplayConsentDecision() {
        var consents = mock(ConsentRepository.class);
        var purposes = mock(PurposeRepository.class);
        var verification = mock(SubjectVerificationPort.class);
        var outbox = mock(OutboxService.class);
        var audit = mock(ConsentAuditWriter.class);
        var service = new ConsentService(consents, purposes, verification, outbox, audit);
        var now = OffsetDateTime.now();
        var prior = new ConsentDecision("decision", "purpose", "MARKETING", "CIF", "customer",
                null, 1, ConsentDecision.Decision.GRANTED, "WEB", now, now, null,
                "customer", "SESSION", "a".repeat(64), "vault:original", "b".repeat(64), "trace");
        when(consents.findByIdempotencyKey("retry-key")).thenReturn(Optional.of(prior));
        var context = new RequestContext("customer", "session", "trace", "retry-key", Set.of());
        var changed = new ConsentService.DecideConsent("CIF", "customer", null, "MARKETING",
                ConsentDecision.Decision.GRANTED, "WEB", "a".repeat(64), null, null,
                "vault:changed", "b".repeat(64));
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(ApiException.class,
                () -> service.decide(changed, context)).code());
        var identical = new ConsentService.DecideConsent("CIF", "customer", null, "MARKETING",
                ConsentDecision.Decision.GRANTED, "WEB", "a".repeat(64), null, null,
                "vault:original", "b".repeat(64));
        assertSame(prior, service.decide(identical, context));
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(ApiException.class,
                () -> service.decide(identical,
                        new RequestContext("other", "session", "trace", "retry-key", Set.of()))).code());
        verifyNoInteractions(verification, purposes, outbox, audit);
    }
}
