package com.moneybags.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.common.api.BusinessException;
import com.moneybags.integration.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    public record ChatRequest(@NotBlank @Size(max=2000)String message,String conversationId) {}
    private final AssistantChatService chat;
    private final AssistantTools tools;
    private final AssistantIntentService intents;
    private final OpenAiResponsesClient model;
    private final ObjectMapper json;
    public AssistantController(AssistantChatService chat,AssistantTools tools,AssistantIntentService intents,
            OpenAiResponsesClient model,ObjectMapper json) {
        this.chat=chat;this.tools=tools;this.intents=intents;this.model=model;this.json=json;
    }
    @GetMapping("/status")
    public Map<String,Object> status() {
        CurrentActor.get();
        return Map.of("configured",model.configured(),"tools",tools.available().stream().map(AssistantTools.Definition::name).toList());
    }
    @PostMapping("/chat")
    public AssistantChatService.Reply chat(@Valid @RequestBody ChatRequest request,
            @RequestHeader(value="X-Customer-Hash",required=false)String customerHash) {
        return chat.reply(request.message(),request.conversationId(),customerHash);
    }
    @PostMapping("/intents/{id}/confirm")
    public Map<String,Object> confirm(@PathVariable String id) { return intents.confirm(id); }

    /** Stateless MCP 2026-07-28 transport, using the same authenticated tool dispatcher as chat. */
    @PostMapping(value="/mcp",produces="application/json")
    public Map<String,Object> mcp(@RequestHeader(value="MCP-Protocol-Version",required=false)String version,
            @RequestHeader(value="Mcp-Method",required=false)String methodHeader,
            @RequestHeader(value="Mcp-Name",required=false)String nameHeader,
            @RequestHeader(value="X-Customer-Hash",required=false)String customerHash,
            @RequestBody JsonNode body) {
        CurrentActor.get();
        JsonNode id=body.path("id");String method=body.path("method").asText();
        if(!"2026-07-28".equals(version) || !"2.0".equals(body.path("jsonrpc").asText()) ||
                methodHeader==null || !methodHeader.equals(method))return rpcError(id,-32600,"Invalid MCP request or protocol headers");
        if("server/discover".equals(method))return rpc(id,Map.of(
                "supportedVersions",List.of("2026-07-28"),"capabilities",Map.of("tools",Map.of()),
                "ttlMs",0,"cacheScope","private",
                "_meta",Map.of("io.modelcontextprotocol/serverInfo",Map.of("name","moneybags-banking","version","1.0.0"))));
        if("tools/list".equals(method))return rpc(id,Map.of("tools",tools.available().stream().map(AssistantTools.Definition::mcp).toList(),"ttlMs",0,"cacheScope","private"));
        if("tools/call".equals(method)) {
            String name=body.path("params").path("name").asText();
            if(nameHeader==null || !nameHeader.equals(name))return rpcError(id,-32600,"Mcp-Name must match the tool name");
            try {
                Object value=tools.invoke(name,body.path("params").path("arguments"),customerHash);
                return rpc(id,Map.of("content",List.of(Map.of("type","text","text",json.writeValueAsString(value))),"isError",false));
            } catch(BusinessException e) {
                return rpc(id,Map.of("content",List.of(Map.of("type","text","text",e.code()+": "+e.getMessage())),"isError",true));
            } catch(Exception e) { return rpcError(id,-32603,"Tool failed"); }
        }
        return rpcError(id,-32601,"Method not found");
    }
    private static Map<String,Object> rpc(JsonNode id,Object result) {return Map.of("jsonrpc","2.0","id",id,"result",result);}
    private static Map<String,Object> rpcError(JsonNode id,int code,String message) {return Map.of("jsonrpc","2.0","id",id,"error",Map.of("code",code,"message",message));}
}
