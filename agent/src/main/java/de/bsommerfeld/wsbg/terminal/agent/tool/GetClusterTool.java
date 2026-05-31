package de.bsommerfeld.wsbg.terminal.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.agent.InvestigationCluster;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Returns the full assembled context for a cluster — threads with title, body
 * snippet, image vision descriptions, and the fresh comments ranked by score.
 * This is the agent's "deep read" tool; expensive, so it should be called once
 * per cluster the agent is genuinely considering for a headline.
 */
@Deprecated // legacy agent tool-loop — replaced by the deterministic EditorialAgent pipeline; no longer wired
public final class GetClusterTool implements Tool {

    @Override
    public String name() {
        return "getCluster";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("Returns the full content of a cluster: source threads with title/body, "
                        + "image descriptions, fresh comments ranked by community score. "
                        + "Use this once per cluster you actually want to evaluate.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("id", "The cluster ID, as listed by listClusters.")
                        .required("id")
                        .build())
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        String id = args.path("id").asText("").trim();
        if (id.isEmpty())
            return "Error: 'id' is required.";
        InvestigationCluster c = ctx.clusterRegistry().getCluster(id);
        if (c == null)
            return "Error: no cluster with id '" + id + "'.";
        // Pass prior headlines so the report can annotate "already
        // covered" evidence and let the agent decide whether anything
        // new warrants a follow-up.
        return ctx.reportBuilder().buildReportData(
                c, ctx.agentRepository().getHeadlinesByClusterId(id));
    }
}
