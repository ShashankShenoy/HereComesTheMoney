package com.moneybags.assistant;

import com.moneybags.integration.CurrentActor;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned workflow instructions. Permissions remain in application code. */
@Component
public class AssistantSkills {
    public String instructions() {
        String audience="CUSTOMER".equals(CurrentActor.get().userType())?"customer":"officer";
        return read("trust")+"\n\n"+read(audience);
    }
    private String read(String name) {
        String path="/assistant-skills/"+name+".md";
        try(var stream=getClass().getResourceAsStream(path)) {
            if(stream==null)throw new IllegalStateException("Missing assistant skill: "+path);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) { throw new IllegalStateException("Cannot read assistant skill",e); }
    }
}
