package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.Draft;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.DraftSubject;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooNewsItem;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the dirty clusters of one editorial tick into published headlines via a
 * fixed, deterministic pipeline — no free tool loop, no round cap.
 *
 * <p>Per cluster, two model calls with deterministic glue between them:
 * <ol>
 *   <li><b>Subject extraction</b> (model): the cluster brief in, a list of
 *       market-relevant subject names out (slang normalised to canonical/English
 *       via {@link WsbgJargon}).</li>
 *   <li><b>Resolve</b> (code, {@link TickerResolver}): each subject → validated
 *       Yahoo ticker + live market data + news, or news-only, or nothing.</li>
 *   <li><b>Headline composition</b> (model): the brief + the resolved data in,
 *       structured headline drafts out.</li>
 *   <li><b>Write</b> (code, {@link HeadlineWriter}): QA + persist + broadcast.</li>
 * </ol>
 *
 * <p>This replaces the former agentic tool-use loop (getCluster / lookupTicker /
 * publishHeadline / done). The editorial policy is "translate, almost always
 * publish", which is a per-cluster transform, not an investigation — so the
 * loop machinery was pure overhead.
 */
@Singleton
public class EditorialAgent {

    private static final Logger LOG = LoggerFactory.getLogger(EditorialAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SUBJECTS = 6;

    private final AgentBrain brain;
    private final ClusterRegistry clusterRegistry;
    private final AgentRepository agentRepository;
    private final ReportBuilder reportBuilder;
    private final TickerResolver tickerResolver;
    private final HeadlineWriter headlineWriter;

    @Inject
    public EditorialAgent(AgentBrain brain, ClusterRegistry clusterRegistry,
            AgentRepository agentRepository,
            RedditRepository redditRepository,
            ApplicationEventBus eventBus, I18nService i18n,
            YahooFinanceClient yahooFinance) {
        this.brain = brain;
        this.clusterRegistry = clusterRegistry;
        this.agentRepository = agentRepository;
        this.reportBuilder = new ReportBuilder(redditRepository, brain);
        this.tickerResolver = new TickerResolver(yahooFinance);
        this.headlineWriter = new HeadlineWriter(agentRepository, eventBus);
    }

    /**
     * Runs one editorial tick over the given dirty clusters. Each cluster is
     * processed independently through the pipeline; one failing cluster never
     * blocks the rest.
     */
    public void runTick(Set<String> dirtyClusterIds) {
        if (dirtyClusterIds == null || dirtyClusterIds.isEmpty()) return;
        ChatModel model = brain.getAgentModel();
        if (model == null) {
            LOG.warn("EditorialAgent: agent model not ready, skipping tick.");
            return;
        }
        int published = 0;
        for (String id : dirtyClusterIds) {
            try {
                published += processCluster(model, id);
            } catch (Exception e) {
                LOG.warn("EditorialAgent: cluster {} failed: {}", id, e.getMessage());
            }
        }
        LOG.info("[AGENT] tick done: {} cluster(s) → {} headline(s)", dirtyClusterIds.size(), published);
    }

    private int processCluster(ChatModel model, String clusterId) {
        InvestigationCluster cluster = clusterRegistry.getCluster(clusterId);
        if (cluster == null) return 0;

        List<HeadlineRecord> priorHeadlines = agentRepository.getHeadlinesByClusterId(clusterId);
        String brief = reportBuilder.buildReportData(cluster, priorHeadlines);

        // Stage 1 — subjects (slang-normalised for lookup).
        List<String> subjectNames = extractSubjects(model, brief);

        // Stage 2 — deterministic resolution.
        List<ResolvedSubject> resolved = new ArrayList<>();
        for (String name : subjectNames) {
            resolved.add(tickerResolver.resolve(name));
        }

        // Stage 3 — headline drafts.
        List<Draft> drafts = composeHeadlines(model, brief, resolved);

        // Stage 4 — QA + persist.
        int n = 0;
        for (Draft d : drafts) {
            if (headlineWriter.publish(cluster, d, resolved)) n++;
        }
        return n;
    }

    // ---- Stage 1: subject extraction ----

    private List<String> extractSubjects(ChatModel model, String brief) {
        String sys = PromptLoader.load("subject-extraction")
                .replace("{{ENTITY_ALIASES}}", WsbgJargon.entityAliasesForPrompt());
        String text = chat(model, sys, brief);
        JsonNode root = parseJson(text);
        List<String> out = new ArrayList<>();
        if (root != null && root.path("subjects").isArray()) {
            for (JsonNode s : root.path("subjects")) {
                String name = s.asText("").trim();
                if (!name.isEmpty() && out.size() < MAX_SUBJECTS) out.add(name);
            }
        }
        return out;
    }

    // ---- Stage 3: headline composition ----

    private List<Draft> composeHeadlines(ChatModel model, String brief, List<ResolvedSubject> resolved) {
        String sys = PromptLoader.load("headline-compose")
                .replace("{{LANGUAGE}}", brain.getUserLanguage().displayName())
                .replace("{{ROOM_SLANG}}", WsbgJargon.roomSlangForPrompt());
        String user = brief
                + "\n\n=== EXTERNAL MARKET DATA (Yahoo Finance — NOT Reddit; context & attribution only) ===\n"
                + renderResolved(resolved);
        String text = chat(model, sys, user);
        JsonNode root = parseJson(text);
        List<Draft> out = new ArrayList<>();
        if (root != null && root.path("headlines").isArray()) {
            for (JsonNode h : root.path("headlines")) {
                Draft d = toDraft(h);
                if (d != null) out.add(d);
            }
        }
        return out;
    }

    private static Draft toDraft(JsonNode h) {
        String headline = h.path("headline").asText("").trim();
        if (headline.isEmpty()) return null;
        Double priceMove = h.path("priceMovePercent").isNumber()
                ? h.path("priceMovePercent").asDouble() : null;
        List<DraftSubject> subjects = new ArrayList<>();
        if (h.path("subjects").isArray()) {
            for (JsonNode s : h.path("subjects")) {
                String name = s.path("name").asText("").trim();
                String ticker = s.path("ticker").asText("").trim();
                if (!name.isEmpty() && !ticker.isEmpty()) {
                    subjects.add(new DraftSubject(name, ticker));
                }
            }
        }
        return new Draft(
                headline,
                h.path("sentiment").asText(""),
                h.path("highlight").asText(""),
                emptyToNull(h.path("tickerSymbol").asText("")),
                subjects,
                priceMove,
                readStrings(h.path("sectors")),
                emptyToNull(h.path("assetClass").asText("")),
                readStrings(h.path("sourceThreadIds")),
                readStrings(h.path("sourceCommentIds")));
    }

    /** Renders resolved subjects into the data block the compose stage reads. */
    private static String renderResolved(List<ResolvedSubject> resolved) {
        if (resolved.isEmpty()) return "(no market subjects detected — write from the cluster's own sentiment)\n";
        StringBuilder sb = new StringBuilder();
        Instant now = Instant.now();
        for (ResolvedSubject r : resolved) {
            sb.append("- ").append(r.canonicalName());
            if (r.isInstrument()) {
                sb.append(" → ticker ").append(r.ticker());
                MarketSnapshot s = r.snapshot();
                if (s != null && s.hasPrice()) {
                    sb.append(String.format(Locale.ROOT, " | price %.2f%s", s.price(),
                            s.currency() == null || s.currency().isEmpty() ? "" : " " + s.currency()));
                    if (Double.isFinite(s.dayChangePercent())) {
                        sb.append(String.format(Locale.ROOT, ", day %+.2f%%", s.dayChangePercent()));
                    }
                }
            } else {
                sb.append(" → no ticker (theme/person — news only, write without a ticker)");
            }
            sb.append('\n');
            for (YahooNewsItem n : r.news()) {
                sb.append("    Yahoo news: ");
                if (n.publishedAt() != null) {
                    long mins = Duration.between(n.publishedAt(), now).toMinutes();
                    sb.append(mins < 60 ? mins + "m" : mins < 1440 ? (mins / 60) + "h" : (mins / 1440) + "d")
                            .append(" ago — ");
                }
                sb.append(n.title());
                if (n.publisher() != null && !n.publisher().isEmpty()) sb.append(" · ").append(n.publisher());
                sb.append('\n');
            }
            // Second-hop evidence: instruments this subject's news is about,
            // with their live move. Raw material for a causal read — the desk
            // links them only if it genuinely holds; never forced.
            for (TickerResolver.RelatedInstrument ri : r.related()) {
                MarketSnapshot s = ri.snapshot();
                sb.append("    related instrument (from the news above): ").append(ri.ticker());
                if (s != null && s.hasPrice() && Double.isFinite(s.dayChangePercent())) {
                    sb.append(String.format(Locale.ROOT, " %+.2f%% today", s.dayChangePercent()));
                }
                sb.append('\n');
                for (YahooNewsItem n : ri.news()) {
                    sb.append("        Yahoo news: ");
                    if (n.publishedAt() != null) {
                        long mins = Duration.between(n.publishedAt(), now).toMinutes();
                        sb.append(mins < 60 ? mins + "m" : mins < 1440 ? (mins / 60) + "h" : (mins / 1440) + "d")
                                .append(" ago — ");
                    }
                    sb.append(n.title());
                    if (n.publisher() != null && !n.publisher().isEmpty()) sb.append(" · ").append(n.publisher());
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ---- model + JSON helpers ----

    private String chat(ChatModel model, String systemPrompt, String userMessage) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
        ChatResponse response = model.chat(ChatRequest.builder().messages(messages).build());
        AiMessage ai = response.aiMessage();
        return ai == null || ai.text() == null ? "" : ai.text();
    }

    /**
     * Lenient JSON extraction — models wrap the object in ```json fences or
     * stray prose. Grabs the outermost {@code { ... }} and parses it.
     */
    private static JsonNode parseJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return JSON.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            LOG.debug("JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static List<String> readStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode el : node) {
                String s = el.asText("").trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
