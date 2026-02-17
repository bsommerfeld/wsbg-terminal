package de.bsommerfeld.wsbg.terminal.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface Assistant {

    @SystemMessage(fromResource = "/prompts/assistant-system.txt")
    TokenStream chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
