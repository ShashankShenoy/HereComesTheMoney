package com.moneybags.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiResponsesClientTest {
    @Test
    void providerRateLimitIsReportedAsRateLimit() {
        var error = OpenAiResponsesClient.providerError(429);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
        assertEquals("MODEL_RATE_LIMITED", error.code());
    }

    @Test
    void otherProviderFailuresRemainUnavailable() {
        var error = OpenAiResponsesClient.providerError(503);
        assertEquals(HttpStatus.BAD_GATEWAY, error.status());
        assertEquals("MODEL_UNAVAILABLE", error.code());
    }
}
