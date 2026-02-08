package de.bsommerfeld.wsbg.terminal.agent.service;

import de.bsommerfeld.wsbg.terminal.agent.ChatService;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.inject.Singleton;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;

@Singleton
public class TopicExtractionService {

    private static final Set<String> STOP_WORDS = Set.of(
            "THE", "AND", "FOR", "THAT", "THIS", "WITH", "YOU", "ARE", "NOT", "BUT", "HAVE", "WAS", "WHAT",
            "CAN", "WILL", "JUST", "YOUR", "ALL", "FROM", "OUT", "NOW", "ONE", "HAS", "WHY", "WHO", "HOW",
            "NEW", "GET", "LIKE", "MORE", "TIME", "SOME", "MARKET", "STOCK", "BUY", "SELL", "HOLD",
            "SHORT", "LONG", "PUT", "CALL", "MOON", "APE", "YOLO", "DD", "WSB", "RH", "GME", "AMC", "BB",
            "NOK", "PLTR", "TSLA", "NIO", "SNDL", "TLRY", "OCGN", "RKT", "MVIS", "CLOV", "WISH", "IT", "IS", "OF", "TO",
            "IN");

    private final ChatService chatService;
    private final de.bsommerfeld.wsbg.terminal.db.RedditRepository repository; // Changed
    private final de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus eventBus;
    private final de.bsommerfeld.wsbg.terminal.core.config.AgentConfig agentConfig;

    @com.google.inject.Inject
    public TopicExtractionService(ChatService chatService,
            de.bsommerfeld.wsbg.terminal.db.RedditRepository repository, // Changed
            de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus eventBus,
            de.bsommerfeld.wsbg.terminal.core.config.AgentConfig agentConfig) {
        this.chatService = chatService;
        this.repository = repository; // Changed
        this.eventBus = eventBus;
        this.agentConfig = agentConfig;
    }

    public static class Bridge {
        public String fromId;
        public String toCluster;

        public Bridge() {
        }

        public Bridge(String fromId, String toCluster) {
            this.fromId = fromId;
            this.toCluster = toCluster;
        }
    }

    public static class ClusteringResult {
        public Map<String, List<String>> clusters = new HashMap<>();
        public List<Bridge> bridges = new ArrayList<>();
    }

    /**
     * Uses LLM to cluster threads into topics.
     * Returns a ClusteringResult.
     */
    public java.util.concurrent.CompletableFuture<ClusteringResult> identifyClusters(
            List<RedditThread> threads) {
        if (threads.isEmpty() || !agentConfig.isAllowGraphView()) {
            return java.util.concurrent.CompletableFuture.completedFuture(new ClusteringResult());
        }

        // 1. Prepare Batch Prompt (Limit to recent 50-100 to avoid context overflow)
        int limit = Math.min(threads.size(), 60);
        List<RedditThread> batch = threads.subList(0, limit);

        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze these Reddit threads and group them by ID into semantic clusters.\n");
        prompt.append(
                "Context: Financial/Stock Market discussions (r/wallstreetbetsGER / r/mauerstrassenwetten). Heavy slang.\n");
        prompt.append("OBJECTIVE: Cluster by SUBJECTIVE TOPIC (Specific Stocks, Companies, Commodities, Sectors).\n");
        prompt.append("RULES:\n");
        prompt.append(
                "1. ENTITIES FIRST: Group by specific Ticker/Company (e.g. 'NVIDIA', 'GME', 'Rheinmetall', 'Tesla') or Commodity ('Gold', 'Silver', 'Oil').\n");
        prompt.append(
                "2. BROAD TOPICS SECOND: If no specific entity, group by theme ('Macro', 'Politics', 'Shitpost').\n");
        prompt.append(
                "3. CROSS-LINKING (CRITICAL): Check COMMENTS inside threads. If a comment mentions a DIFFERENT topic (e.g. discussing Gold inside a Rheinmetall thread), you MUST create a 'bridge' entry.\n");
        prompt.append(
                "4. OUTPUT STRICT JSON ONLY: { \"clusters\": { \"Gold\": [\"id1\"] }, \"bridges\": [] }. NO MARKDOWN. NO WRAPPER OBJECTS like 'output' or 'posts'.\n");
        prompt.append("5. DO NOT REPEAT INPUT DATA. JUST IDs.\n\n");
        prompt.append("Threads:\n");

        for (RedditThread t : batch) {
            // sanitize
            String safeTitle = t.getTitle().replace("\"", "'").replace("\n", " ");
            String textSnippet = t.getTextContent().length() > 50 ? t.getTextContent().substring(0, 50) + "..."
                    : t.getTextContent();
            textSnippet = textSnippet.replace("\"", "'").replace("\n", " ");
            String imgFlag = t.getImageUrl() != null ? "[HAS_IMAGE]" : "";

            // Fetch top comments for context
            List<de.bsommerfeld.wsbg.terminal.core.domain.RedditComment> comments = repository
                    .getCommentsForThread(t.getId(), 3);
            StringBuilder commentsSnippet = new StringBuilder();
            if (!comments.isEmpty()) {
                commentsSnippet.append(" | Comments: [");
                for (de.bsommerfeld.wsbg.terminal.core.domain.RedditComment c : comments) {
                    String body = c.getBody().length() > 40 ? c.getBody().substring(0, 40) + "..." : c.getBody();
                    body = body.replace("\"", "'").replace("\n", " ");
                    commentsSnippet.append(String.format("'%s', ", body));
                }
                commentsSnippet.append("]");
            }

            prompt.append(String.format("- ID: %s | Score: %d | %s Title: %s | Text: %s%s\n",
                    t.getId(), t.getScore(), imgFlag, safeTitle, textSnippet, commentsSnippet.toString()));
        }

        prompt.append("\n\nJSON Output:");

        // 2. Call LLM (Experimental: Use lfm2.5-thinking:latest directly)
        return CompletableFuture.supplyAsync(() -> {
            try {
                OllamaChatModel clusteringModel = OllamaChatModel.builder()
                        .baseUrl("http://localhost:11434")
                        .modelName("lfm2.5-thinking:latest")
                        .temperature(0.1)
                        .timeout(Duration.ofMinutes(5))
                        .build();

                return clusteringModel.generate(prompt.toString());
            } catch (Exception e) {
                throw new RuntimeException("Clustering Model Failed: " + e.getMessage(), e);
            }
        }).thenApply(response -> {
            ClusteringResult result = new ClusteringResult();
            String rawText = response; // Capture for debug
            try {
                String json = extractJson(rawText);
                if (json != null) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

                    // Parse 'clusters'
                    if (root.has("clusters")) {
                        com.fasterxml.jackson.databind.JsonNode cNode = root.get("clusters");
                        cNode.fields().forEachRemaining(entry -> {
                            List<String> ids = new ArrayList<>();
                            if (entry.getValue().isArray()) {
                                entry.getValue().forEach(n -> ids.add(n.asText()));
                            }
                            result.clusters.put(entry.getKey(), ids);
                        });
                    } else {
                        // Check if root ITSELF is the map (Flat Format)
                        // But verify values are arrays
                        if (root.isObject() && !root.isEmpty()) {
                            root.fields().forEachRemaining(entry -> {
                                if (entry.getValue().isArray()) {
                                    List<String> ids = new ArrayList<>();
                                    entry.getValue().forEach(n -> ids.add(n.asText()));
                                    result.clusters.put(entry.getKey(), ids);
                                }
                            });
                        }
                    }

                    // Parse 'bridges'
                    if (root.has("bridges") && root.get("bridges").isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode bNode : root.get("bridges")) {
                            if (bNode.has("from") && bNode.has("to_cluster")) {
                                result.bridges.add(new Bridge(bNode.get("from").asText(),
                                        bNode.get("to_cluster").asText()));
                            }
                        }
                    }
                } else {
                    eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                            "Clustering AI produced no JSON. Raw: "
                                    + rawText.substring(0, Math.min(rawText.length(), 100)),
                            "WARN"));
                }
            } catch (Exception e) {
                eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                        "Topic Clustering JSON Parse Failed: " + e.getMessage(), "ERROR"));
                // System.out.println("DEBUG RAW JSON: " + rawText);
            }
            return result;
        });
    }

    private String extractJson(String text) {
        // Remove <thinking> blocks to avoid False Positives
        String clean = text.replaceAll("<thinking>[\\s\\S]*?</thinking>", "").trim();

        int start = clean.indexOf("{");
        int end = clean.lastIndexOf("}");
        if (start != -1 && end != -1) {
            return clean.substring(start, end + 1);
        }
        return null;
    }

    // Fallback/Helper if needed, but we rely on batch now
    public Map<String, Integer> extractTopics(List<RedditThread> threads) {
        // Legacy regex method removed/deprecated in favor of AI batching
        return new HashMap<>();
    }

    public Set<String> getTopicsForThread(RedditThread thread, Set<String> validTopics) {
        return Collections.emptySet(); // Deprecated
    }
}
