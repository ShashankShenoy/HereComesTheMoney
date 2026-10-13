package com.moneybags.privacy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/** Typed configuration for service endpoints, event topics and safe timeout defaults. */
@ConfigurationProperties(prefix = "moneybags.integration")
public record IntegrationProperties(
        Map<String, ServiceEndpoint> services,
        Map<String, String> topics,
        Duration connectTimeout,
        Duration readTimeout) {

    public IntegrationProperties {
        services = services == null ? Map.of() : Map.copyOf(services);
        topics = topics == null ? Map.of() : Map.copyOf(topics);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }

    /** Endpoint details for one Moneybags bounded context. */
    public record ServiceEndpoint(String baseUrl, String audience) {}
}
