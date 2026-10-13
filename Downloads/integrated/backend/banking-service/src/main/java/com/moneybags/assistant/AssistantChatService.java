package com.moneybags.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import com.moneybags.integration.CurrentActor;
import com.moneybags.integration.CustomerHashService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded conversation loop. Only server-side history is sent to the model. */
@Service
public class AssistantChatService {
    public record Reply(String conversationId,String answer,List<Map<String,Object>> pendingActions) {}
    private static final class Conversation {
        final List<Map<String,String>> turns=new ArrayList<>();
        volatile Instant touched=Instant.now();
    }
    private final Map<String,Conversation> conversations=new ConcurrentHashMap<>();
    private final AssistantTools tools;
    private final AssistantSkills skills;
    private final OpenAiResponsesClient model;
    private final CustomerHashService audit;
    private final ObjectMapper json;
    public AssistantChatService(AssistantTools tools,AssistantSkills skills,OpenAiResponsesClient model,
            CustomerHashService audit,ObjectMapper json) {
        this.tools=tools;this.skills=skills;this.model=model;this.audit=audit;this.json=json;
    }
    public Reply reply(String message,String conversationId,String customerHash,String requestedLocale) {
        var actor=CurrentActor.get();
        // Only these three fixed locales may influence response language; never trust a free-form prompt here.
        String locale=switch(requestedLocale==null?"en-IN":requestedLocale) {
            case "hi-IN" -> "hi-IN";
            case "kn-IN" -> "kn-IN";
            default -> "en-IN";
        };
        if(!"CUSTOMER".equals(actor.userType()) && !"EMPLOYEE".equals(actor.userType()))
            throw new BusinessException(HttpStatus.FORBIDDEN,"ASSISTANT_UNAVAILABLE","The assistant is for customers and officers");
        if(message==null || message.isBlank() || message.length()>2000)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_MESSAGE","Enter a message of 1 to 2000 characters");
        if(conversationId!=null && !conversationId.isBlank()) {
            try { UUID.fromString(conversationId); }
            catch(IllegalArgumentException e) { throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_CONVERSATION","Invalid conversation identifier"); }
        }
        String id=conversationId==null || conversationId.isBlank()?UUID.randomUUID().toString():conversationId;
        conversations.entrySet().removeIf(e->e.getValue().touched.isBefore(Instant.now().minusSeconds(1800)));
        if(conversations.size()>1000)throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"ASSISTANT_BUSY","Too many active conversations; try again shortly");
        String key=actor.userId()+":"+actor.sessionId()+":"+id;
        Conversation conversation=conversations.computeIfAbsent(key,unused->new Conversation());
        synchronized(conversation) {
            List<Object> input=new ArrayList<>(conversation.turns);
            input.add(Map.of("role","user","content",message.trim()));
            List<Map<String,Object>> pending=new ArrayList<>();
            String answer=null;
            for(int round=0;round<6;round++) {
                Map<String,Object> request=new LinkedHashMap<>();
                model.selectModel(request);request.put("instructions",skills.instructions()+"\n\n"+languageInstruction(locale));
                request.put("input",input);request.put("tools",tools.available().stream().map(AssistantTools.Definition::model).toList());
                request.put("parallel_tool_calls",false);request.put("store",false);
                if(model.requiresEncryptedReasoning())request.put("include",List.of("reasoning.encrypted_content"));
                request.put("max_output_tokens",1000);
                JsonNode response=model.create(request);
                JsonNode output=response.path("output");
                if(!output.isArray())throw new BusinessException(HttpStatus.BAD_GATEWAY,"MODEL_RESPONSE","Invalid assistant response");
                List<JsonNode> calls=new ArrayList<>();
                for(JsonNode item:output) {
                    input.add(item);
                    if("function_call".equals(item.path("type").asText()))calls.add(item);
                }
                if(calls.isEmpty()) { answer=extractText(response);break; }
                for(JsonNode call:calls) {
                    String name=call.path("name").asText();
                    String result;
                    try {
                        JsonNode args=json.readTree(call.path("arguments").asText("{}"));
                        Object value=tools.invoke(name,args,customerHash);
                        if(value instanceof Map<?,?> map && (map.containsKey("intentId") || map.containsKey("uiAction"))) {
                            @SuppressWarnings("unchecked") Map<String,Object> action=(Map<String,Object>)value;
                            pending.add(action);
                        }
                        audit.audit("ASSISTANT_TOOL_ALLOWED","ASSISTANT_TOOL",name,"ALLOWED");
                        result=json.writeValueAsString(value);
                    } catch(BusinessException e) {
                        audit.audit("ASSISTANT_TOOL_DENIED","ASSISTANT_TOOL",name,e.code());
                        result="{\"error\":\""+e.code()+"\",\"message\":"+quoted(e.getMessage())+"}";
                    } catch(Exception e) {
                        result="{\"error\":\"TOOL_FAILED\",\"message\":\"The operation could not be completed\"}";
                    }
                    input.add(Map.of("type","function_call_output","call_id",call.path("call_id").asText(),"output",result));
                }
            }
            if(answer==null || answer.isBlank())answer=switch(locale) {
                case "hi-IN" -> "मैं अनुरोध पूरा नहीं कर सका। कृपया अधिक स्पष्ट अनुरोध लिखें।";
                case "kn-IN" -> "ವಿನಂತಿಯನ್ನು ಪೂರ್ಣಗೊಳಿಸಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ. ದಯವಿಟ್ಟು ಇನ್ನಷ್ಟು ಸ್ಪಷ್ಟವಾಗಿ ಕೇಳಿ.";
                default -> "I couldn't complete that request. Please try a more specific request.";
            };
            conversation.turns.add(Map.of("role","user","content",message.trim()));
            conversation.turns.add(Map.of("role","assistant","content",answer));
            while(conversation.turns.size()>8)conversation.turns.subList(0,2).clear();
            conversation.touched=Instant.now();
            return new Reply(id,answer,List.copyOf(pending));
        }
    }
    private String quoted(String value) {
        try { return json.writeValueAsString(value); }
        catch(Exception e) { return "\"Request denied\""; }
    }
    /** Tells the model which user-facing language to use without altering tool schemas or safety rules. */
    private static String languageInstruction(String locale) {
        String language=switch(locale) {
            case "hi-IN" -> "Hindi (हिन्दी)";
            case "kn-IN" -> "Kannada (ಕನ್ನಡ)";
            default -> "English";
        };
        return "Reply to the user in "+language+". Keep account numbers, identifiers, currency codes, "
            +"amounts, dates, status codes, API names, and tool arguments exact. "
            +"Do not translate or change tool calls, authorization requirements, or confirmation steps.";
    }
    private static String extractText(JsonNode response) {
        StringBuilder text=new StringBuilder();
        for(JsonNode item:response.path("output"))if("message".equals(item.path("type").asText()))
            for(JsonNode content:item.path("content"))if("output_text".equals(content.path("type").asText())) {
                if(text.length()>0)text.append('\n');text.append(content.path("text").asText());
            }
        return text.length()>4000?text.substring(0,4000):text.toString();
    }
}
