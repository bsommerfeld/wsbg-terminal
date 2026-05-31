package de.bsommerfeld.wsbg.terminal.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Contract every editorial-agent tool implements. Tools are stateless; all
 * shared state lives on {@link ToolContext} and is passed in per execute call.
 */
public interface Tool {
    /** Schema description used to advertise the tool to the model. */
    ToolSpecification specification();

    /** Canonical tool name (must match {@link #specification()}). */
    String name();

    /**
     * Executes the tool. Implementations should return either a useful result
     * string (handed back to the model in the next round) or an
     * {@code "Error: ..."} prefixed string when something went wrong. They
     * must never throw — wrap exceptions in error strings.
     */
    String execute(JsonNode args, ToolContext ctx);
}
