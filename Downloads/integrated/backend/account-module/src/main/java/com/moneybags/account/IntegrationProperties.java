package com.moneybags.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** URLs are adapter contracts, never implicit direct access to another module's tables. */
@ConfigurationProperties(prefix = "moneybags.integration")
public record IntegrationProperties(String cifUrl, String productUrl, String iamUrl,
                                    String transactionUrl, String paymentUrl, String loanUrl,
                                    String eventRelayUrl, int timeoutSeconds, String tokenUri,
                                    String clientId, String clientSecret, String scope) { }
