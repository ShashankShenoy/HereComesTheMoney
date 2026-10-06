package com.moneybags.treasury.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe deployment settings for integrations and security. */
@ConfigurationProperties("moneybags")
public record TreasuryProperties(Integrations integrations, Outbox outbox, Security security, Cors cors) {
    public record Integrations(String transactionServiceUrl, String paymentServiceUrl,
                               String accountServiceUrl, Duration connectTimeout, Duration readTimeout) {}
    public record Outbox(boolean relayEnabled, int batchSize) {}
    public record Security(boolean permitAll, String issuerUri) {
        public Security { /* absent configuration safely defaults to authenticated access */ }
    }
    public record Cors(List<String> allowedOrigins) {}
}
