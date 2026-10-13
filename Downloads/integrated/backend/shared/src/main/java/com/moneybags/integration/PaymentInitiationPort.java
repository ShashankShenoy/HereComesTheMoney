package com.moneybags.integration;

import java.math.BigDecimal;

/** Payment initiation is idempotent on the original request key across HTTP retries. */
public interface PaymentInitiationPort {
    long initiatePayment(long sourceAccountId,String beneficiaryId,String railCode,BigDecimal amount,String requestKey);
}
