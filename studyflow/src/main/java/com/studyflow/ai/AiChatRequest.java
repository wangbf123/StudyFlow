package com.studyflow.ai;

public record AiChatRequest(
        String systemPrompt,
        String userPrompt,
        String question,
        String model,
        Double temperature,
        Integer maxOutputTokens
) {
    public AiChatRequest(String system,String user,String question,String model,Double temperature) {this(system,user,question,model,temperature,null);}
}
