package com.moneybags.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small Responses API adapter for fixed OpenAI/OpenRouter hosts. No credentials or tool results are logged. */
@Service
public class OpenAiResponsesClient {
    private static final URI OPENAI_ENDPOINT=URI.create("https://api.openai.com/v1/responses");
    private static final URI OPENROUTER_ENDPOINT=URI.create("https://openrouter.ai/api/v1/responses");
    private final ObjectMapper json;
    private final String key;
    private final String model;
    private final String fallbackModel;
    private final URI endpoint;
    private final String keySetting;
    private final boolean openAi;
    public OpenAiResponsesClient(ObjectMapper json,@Value("${moneybags.ai.provider:openai}")String provider,
            @Value("${moneybags.ai.openai-key:}")String openAiKey,
            @Value("${moneybags.ai.openrouter-key:}")String openRouterKey,
            @Value("${moneybags.ai.model:}")String model,
            @Value("${moneybags.ai.fallback-model:}")String fallbackModel) {
        this.json=json;
        this.model=model;
        this.fallbackModel=fallbackModel;
        switch(provider.trim().toLowerCase(Locale.ROOT)) {
            case "openai" -> { this.endpoint=OPENAI_ENDPOINT;this.key=openAiKey;this.keySetting="OPENAI_API_KEY";this.openAi=true; }
            case "openrouter" -> { this.endpoint=OPENROUTER_ENDPOINT;this.key=openRouterKey;this.keySetting="OPENROUTER_API_KEY";this.openAi=false; }
            default -> throw new IllegalArgumentException("AI_PROVIDER must be openai or openrouter");
        }
    }
    public boolean configured() { return !key.isBlank() && !model.isBlank(); }
    public boolean requiresEncryptedReasoning() { return openAi; }
    public void selectModel(Map<String,Object> request) {
        if(!openAi && !fallbackModel.isBlank() && !fallbackModel.equals(model))
            request.put("models",List.of(model,fallbackModel));
        else request.put("model",model);
    }
    public JsonNode create(Map<String,Object> request) {
        if(!configured())throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"ASSISTANT_NOT_CONFIGURED",
                "Set "+keySetting+" and AI_MODEL on the backend to use the assistant");
        try {
            String body=json.writeValueAsString(request);
            var connection=(HttpURLConnection)endpoint.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Authorization","Bearer "+key);
            connection.setRequestProperty("Content-Type","application/json");
            connection.setRequestProperty("Accept","application/json");
            try {
                try(var output=connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
                int status=connection.getResponseCode();
                if(status<200 || status>=300)
                    throw new BusinessException(HttpStatus.BAD_GATEWAY,"MODEL_UNAVAILABLE","The assistant model is unavailable (provider status "+status+")");
                JsonNode parsed;
                try(var input=connection.getInputStream()) { parsed=json.readTree(input); }
                if(!"completed".equals(parsed.path("status").asText()))
                    throw new BusinessException(HttpStatus.BAD_GATEWAY,"MODEL_INCOMPLETE","The assistant response was incomplete; try again");
                return parsed;
            } finally { connection.disconnect(); }
        } catch(BusinessException e) { throw e; }
          catch(Exception e) { throw new BusinessException(HttpStatus.BAD_GATEWAY,"MODEL_UNAVAILABLE","The assistant model could not be reached"); }
    }
    public String model() { return model; }
}
