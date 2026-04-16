package de.bsommerfeld.wsbg.terminal.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface Assistant {

    @SystemMessage(fromResource = "/prompts/assistant-system.txt")
    String chat(@MemoryId String memoryId, @UserMessage String userMessage,
            @V("LANGUAGE") String language);
}
