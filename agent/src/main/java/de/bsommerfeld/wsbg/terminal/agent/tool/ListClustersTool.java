package de.bsommerfeld.wsbg.terminal.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.agent.ClusterRegistry.ClusterView;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

/**
 * Lists every active investigation cluster with a one-line summary per row.
 * The agent uses this as the entry point to decide which cluster (if any)
 * deserves attention this tick.
 */
@Deprecated // legacy agent tool-loop — replaced by the deterministic EditorialAgent pipeline; no longer wired
public final class ListClustersTool implements Tool {

    @Override
    public String name() {
        return "listClusters";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("Lists every active cluster as 'id | topic | threads | comments | "
                        + "ageMin | lastHeadlineSecsAgo (or '-' if none)'. "
                        + "Start every tick by calling this once. Takes no arguments.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        List<ClusterView> views = ctx.clusterRegistry().allViews();
        if (views.isEmpty())
            return "No active clusters.";

        Instant now = Instant.now();
        long nowEpoch = now.getEpochSecond();
        StringJoiner sj = new StringJoiner("\n");
        sj.add("clusters (" + views.size() + "):");
        for (ClusterView v : views) {
            List<HeadlineRecord> headlines = ctx.agentRepository().getHeadlinesByClusterId(v.id());
            String lastHeadlineAge = "-";
            if (!headlines.isEmpty()) {
                long last = headlines.get(headlines.size() - 1).createdAt();
                lastHeadlineAge = (nowEpoch - last) + "s";
            }
            long ageMin = Duration.between(v.firstSeen(), now).toMinutes();
            sj.add(String.format("%s | %s | %d threads | %d comments | age %dm | last %s",
                    v.id(), trim(v.initialTitle(), 60), v.threadCount(),
                    v.totalComments(), ageMin, lastHeadlineAge));
        }
        return sj.toString();
    }

    private static String trim(String s, int max) {
        if (s == null)
            return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
