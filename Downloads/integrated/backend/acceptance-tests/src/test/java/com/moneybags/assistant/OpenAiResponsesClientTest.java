package com.moneybags.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import java.util.*;

class OpenAiResponsesClientTest {
    @Test void missingKeysFailLocallyWithoutAnyProviderRequest() {
        for(String provider:List.of("openai","openrouter")) {
            var client=new OpenAiResponsesClient(new ObjectMapper(),provider,"","","test-model","");
            assertFalse(client.configured());
            var error=assertThrows(BusinessException.class,()->client.create(Map.of()));
            assertEquals("ASSISTANT_NOT_CONFIGURED",error.code());assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status());
        }
    }
    @Test void providerModelSelectionAndReasoningStayProviderSpecific() {
        var openAi=new OpenAiResponsesClient(new ObjectMapper(),"openai","synthetic-unused-key","","model-a","model-b");
        var request=new LinkedHashMap<String,Object>();openAi.selectModel(request);
        assertEquals(Map.of("model","model-a"),request);assertTrue(openAi.requiresEncryptedReasoning());
        var router=new OpenAiResponsesClient(new ObjectMapper(),"openrouter","","synthetic-unused-key","model-a","model-b");
        request.clear();router.selectModel(request);assertEquals(List.of("model-a","model-b"),request.get("models"));assertFalse(router.requiresEncryptedReasoning());
        assertThrows(IllegalArgumentException.class,()->new OpenAiResponsesClient(new ObjectMapper(),"unknown","","","model",""));
    }
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
