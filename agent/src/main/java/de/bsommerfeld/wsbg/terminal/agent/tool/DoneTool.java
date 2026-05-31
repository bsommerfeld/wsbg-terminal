package de.bsommerfeld.wsbg.terminal.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stop signal from the agent. Setting this on the context tells the loop
 * to exit cleanly without further model calls.
 */
@Deprecated // legacy agent tool-loop — replaced by the deterministic EditorialAgent pipeline; no longer wired
public final class DoneTool implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(DoneTool.class);

    @Override
    public String name() {
        return "done";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("Signals you are done with this tick. Call when there are no further "
                        + "clusters to review or no further actions to take. Pass a short reason.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("reason",
                                "One short sentence: why you are stopping (e.g. 'no fresh activity', "
                                        + "'all dirty clusters reviewed').")
                        .required("reason")
                        .build())
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        String reason = args.path("reason").asText("").trim();
        ctx.signalDone(reason);
        LOG.info("[AGENT] done: {}", reason);
        return "Acknowledged. Stopping.";
    }
}
