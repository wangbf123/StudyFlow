package com.studyflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Provider-neutral single-tool turn. Observations are data in userPrompt, never system instructions. */
public record AiToolRequest(String systemPrompt, String userPrompt, String question, String model,
                            List<Definition> tools,Integer maxOutputTokens) {
    public AiToolRequest(String system,String user,String question,String model,List<Definition> tools){this(system,user,question,model,tools,null); }
    public record Definition(String name, String description, JsonNode parameters) {}
}
