package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.Draft;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.DraftSubject;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditSnapshotStore;
import de.bsommerfeld.wsbg.terminal.reddit.RssRedditScraper;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooNewsItem;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Live, end-to-end smoke of the deterministic editorial pipeline against REAL
 * Reddit (RSS) + REAL Ollama + REAL Yahoo Finance. Not part of CI — it costs a
 * minute of live fetching and a stack of model calls. Run with:
 * <pre>
 * PIPELINE_SMOKE=true mvn test -pl agent -Dtest=PipelineSmokeIT -Dtest.excludedGroups=
 * </pre>
 *
 * <p>It wires the production graph ({@link PassiveMonitorService} does the real
 * clustering), waits for live clusters to form, then walks each cluster through
 * the four production stages with full visibility — so the human can judge
 * <em>cluster quality</em>, <em>information density</em> and <em>headline +
 * data quality</em> from one transcript. The model-call glue mirrors
 * {@link EditorialAgent} verbatim (the only two private methods); every other
 * stage uses the production class directly.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "PIPELINE_SMOKE", matches = "true")
class PipelineSmokeIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SUBJECTS = 6;
    private static final String SUB = "wallstreetbetsGER";

    @Test
    void liveEditorialPipeline_clusterAndHeadlineQuality() throws Exception {
        // --- force a fresh cold-start fetch: move any session snapshots aside ---
        Path appDir = StorageUtils.getSnapshotsDir();
        List<Path> parked = parkSnapshots(appDir);
        try {
            GlobalConfig config = new GlobalConfig();
            ApplicationEventBus bus = new ApplicationEventBus();
            RedditRepository redditRepo = new RedditRepository();
            AgentRepository agentRepo = new AgentRepository();
            OllamaServerManager osm = new OllamaServerManager();
            AgentBrain brain = new AgentBrain(config, bus, osm);
            ClusterRegistry registry = new ClusterRegistry();
            YahooFinanceClient yahoo = new YahooFinanceClient(config);
            RssRedditScraper rss = new RssRedditScraper(redditRepo, config, bus);

            // Production clustering. The ctor self-starts the scan loop.
            new PassiveMonitorService(rss, brain, bus, redditRepo, agentRepo,
                    new RedditSnapshotStore(), new AgentSnapshotStore(), registry, config);

            // RSS cold-start is slow: scanSubreddit fetches every thread context
            // serially (anon rate limiter, ~7s each) and only clusters the whole
            // batch afterwards — so first clusters land ~5-7 min in.
            System.out.println("[SMOKE] waiting for live clusters to form from r/" + SUB + " ...");
            waitForClusters(registry, 3, Duration.ofMinutes(14));
            // give vision + a couple of scan cycles a moment to enrich the briefs
            Thread.sleep(Duration.ofSeconds(20).toMillis());

            List<InvestigationCluster> all = new ArrayList<>(registry.getAllClusters());
            assertFalse(all.isEmpty(), "no clusters formed from live Reddit — cannot judge quality");
            // Bound the model work: evaluate at most the first 8 clusters.
            List<InvestigationCluster> clusters = all.size() > 6 ? all.subList(0, 6) : all;
            System.out.println("[SMOKE] " + all.size() + " live cluster(s) formed; evaluating "
                    + clusters.size() + ".\n");

            ChatModel model = brain.getAgentModel();
            assertNotNull(model, "agent model not ready");

            ReportBuilder reportBuilder = new ReportBuilder(redditRepo, brain);
            TickerResolver resolver = new TickerResolver(yahoo);
            HeadlineWriter writer = new HeadlineWriter(agentRepo, bus);

            AtomicInteger totalHeadlines = new AtomicInteger();
            AtomicInteger tickerHeadlines = new AtomicInteger();
            AtomicInteger withMarketData = new AtomicInteger();
            AtomicInteger newsBackedSubjects = new AtomicInteger();

            int idx = 0;
            for (InvestigationCluster cluster : clusters) {
                idx++;
                List<HeadlineRecord> prior = agentRepo.getHeadlinesByClusterId(cluster.id);
                String brief = reportBuilder.buildReportData(cluster, prior);

                printClusterHeader(idx, clusters.size(), cluster, brief);

                // Stage 1 — subjects (mirrors EditorialAgent.extractSubjects)
                List<String> subjects = extractSubjects(model, brief);
                System.out.println("  SUBJECTS: " + (subjects.isEmpty() ? "(none)" : subjects));

                // Stage 2 — deterministic Yahoo resolution (production TickerResolver)
                List<ResolvedSubject> resolved = new ArrayList<>();
                for (String s : subjects) resolved.add(resolver.resolve(s));
                printResolved(resolved, newsBackedSubjects);

                // Stage 3 — headline drafts (mirrors EditorialAgent.composeHeadlines)
                List<Draft> drafts = composeHeadlines(model, brain, brief, resolved);

                // Stage 4 — production QA + persist
                System.out.println("  HEADLINES:");
                for (Draft d : drafts) {
                    boolean published = writer.publish(cluster, d, resolved);
                    printDraft(d, published, totalHeadlines, tickerHeadlines, withMarketData, resolved);
                }
                if (drafts.isEmpty()) System.out.println("    (none — empty draft list)");
                System.out.println();
            }

            printSummary(clusters.size(), totalHeadlines.get(), tickerHeadlines.get(),
                    withMarketData.get(), newsBackedSubjects.get());
        } finally {
            unparkSnapshots(parked);
        }
    }

    // ---- diagnostics printing ----

    private static void printClusterHeader(int idx, int total, InvestigationCluster c, String brief) {
        System.out.println("============================================================");
        System.out.printf(Locale.ROOT,
                "CLUSTER %d/%d  id=%s%n  title=\"%s\"%n  threads=%d score=%d comments=%d tickers=%s%n",
                idx, total, c.id, c.initialTitle, c.threadCount, c.totalScore,
                c.totalComments, c.tickers.isEmpty() ? "[]" : c.tickers);
        System.out.println("  --- BRIEF (information density) ---");
        for (String line : cap(brief, 2600).split("\n")) System.out.println("  | " + line);
        System.out.println("  -----------------------------------");
    }

    private static void printResolved(List<ResolvedSubject> resolved, AtomicInteger newsBacked) {
        if (resolved.isEmpty()) { System.out.println("  RESOLVED: (no subjects)"); return; }
        System.out.println("  RESOLVED:");
        Instant now = Instant.now();
        for (ResolvedSubject r : resolved) {
            StringBuilder sb = new StringBuilder("    - ").append(r.canonicalName());
            if (r.isInstrument()) {
                sb.append(" → ").append(r.ticker());
                MarketSnapshot s = r.snapshot();
                if (s != null && s.hasPrice()) {
                    sb.append(String.format(Locale.ROOT, "  [%.2f %s, day %+.2f%%]",
                            s.price(), nz(s.currency()), s.dayChangePercent()));
                } else {
                    sb.append("  [ticker but NO market data]");
                }
            } else {
                sb.append(" → (no ticker — theme/person)");
            }
            System.out.println(sb);
            if (r.hasNews()) {
                newsBacked.incrementAndGet();
                for (YahooNewsItem n : r.news()) {
                    String age = n.publishedAt() == null ? "?" :
                            humanAge(Duration.between(n.publishedAt(), now).toMinutes());
                    System.out.println("        news[" + age + "]: " + n.title()
                            + (n.publisher() == null || n.publisher().isEmpty() ? "" : " · " + n.publisher()));
                }
            }
            for (TickerResolver.RelatedInstrument ri : r.related()) {
                MarketSnapshot s = ri.snapshot();
                System.out.println("        related: " + ri.ticker()
                        + (s != null && s.hasPrice() ? String.format(Locale.ROOT, " %+.2f%% today", s.dayChangePercent()) : ""));
            }
        }
    }

    private static void printDraft(Draft d, boolean published, AtomicInteger total,
            AtomicInteger withTicker, AtomicInteger withData, List<ResolvedSubject> resolved) {
        total.incrementAndGet();
        if (d.tickerSymbol() != null && !d.tickerSymbol().isBlank()) withTicker.incrementAndGet();
        boolean dataBacked = d.tickerSymbol() != null && resolved.stream()
                .anyMatch(r -> r.isInstrument() && r.ticker().equalsIgnoreCase(d.tickerSymbol())
                        && r.snapshot() != null && r.snapshot().hasPrice());
        if (dataBacked) withData.incrementAndGet();
        System.out.printf(Locale.ROOT,
                "    [%s] %-9s %-5s %s%s%n      \"%s\"%n",
                published ? "PUBLISHED" : "dropped",
                nz(d.highlight()), nz(d.sentiment()),
                d.tickerSymbol() == null ? "" : d.tickerSymbol(),
                d.priceMovePercent() == null ? "" : String.format(Locale.ROOT, " %+.1f%%", d.priceMovePercent()),
                d.headline());
    }

    private static void printSummary(int clusters, int headlines, int withTicker, int withData, int newsBacked) {
        System.out.println("============================================================");
        System.out.println("[SMOKE] SUMMARY");
        System.out.println("  clusters evaluated   : " + clusters);
        System.out.println("  headlines drafted    : " + headlines);
        System.out.println("  …carrying a ticker   : " + withTicker);
        System.out.println("  …backed by live data : " + withData);
        System.out.println("  news-backed subjects : " + newsBacked);
        System.out.println("============================================================");
    }

    // ---- stage-1 / stage-3 model glue (mirrors EditorialAgent) ----

    private static List<String> extractSubjects(ChatModel model, String brief) {
        String sys = PromptLoader.load("subject-extraction")
                .replace("{{ENTITY_ALIASES}}", WsbgJargon.entityAliasesForPrompt());
        JsonNode root = parseJson(chat(model, sys, brief));
        List<String> out = new ArrayList<>();
        if (root != null && root.path("subjects").isArray()) {
            for (JsonNode s : root.path("subjects")) {
                String name = s.asText("").trim();
                if (!name.isEmpty() && out.size() < MAX_SUBJECTS) out.add(name);
            }
        }
        return out;
    }

    private static List<Draft> composeHeadlines(ChatModel model, AgentBrain brain,
            String brief, List<ResolvedSubject> resolved) {
        String sys = PromptLoader.load("headline-compose")
                .replace("{{LANGUAGE}}", brain.getUserLanguage().displayName())
                .replace("{{ROOM_SLANG}}", WsbgJargon.roomSlangForPrompt());
        String user = brief + "\n\n=== RESOLVED SUBJECTS ===\n" + renderResolved(resolved);
        JsonNode root = parseJson(chat(model, sys, user));
        List<Draft> out = new ArrayList<>();
        if (root != null && root.path("headlines").isArray()) {
            for (JsonNode h : root.path("headlines")) {
                String headline = h.path("headline").asText("").trim();
                if (headline.isEmpty()) continue;
                Double priceMove = h.path("priceMovePercent").isNumber() ? h.path("priceMovePercent").asDouble() : null;
                List<DraftSubject> subs = new ArrayList<>();
                if (h.path("subjects").isArray()) {
                    for (JsonNode s : h.path("subjects")) {
                        String name = s.path("name").asText("").trim();
                        String ticker = s.path("ticker").asText("").trim();
                        if (!name.isEmpty() && !ticker.isEmpty()) subs.add(new DraftSubject(name, ticker));
                    }
                }
                out.add(new Draft(headline, h.path("sentiment").asText(""), h.path("highlight").asText(""),
                        emptyToNull(h.path("tickerSymbol").asText("")), subs, priceMove,
                        readStrings(h.path("sectors")), emptyToNull(h.path("assetClass").asText("")),
                        readStrings(h.path("sourceThreadIds")), readStrings(h.path("sourceCommentIds"))));
            }
        }
        return out;
    }

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
                    sb.append(String.format(Locale.ROOT, " | price %.2f%s", s.price(), nz(s.currency()).isEmpty() ? "" : " " + nz(s.currency())));
                    if (Double.isFinite(s.dayChangePercent())) sb.append(String.format(Locale.ROOT, ", day %+.2f%%", s.dayChangePercent()));
                }
            } else {
                sb.append(" → no ticker (theme/person — news only, write without a ticker)");
            }
            sb.append('\n');
            for (YahooNewsItem n : r.news()) {
                sb.append("    Yahoo news: ");
                if (n.publishedAt() != null) sb.append(humanAge(Duration.between(n.publishedAt(), now).toMinutes())).append(" — ");
                sb.append(n.title());
                if (n.publisher() != null && !n.publisher().isEmpty()) sb.append(" · ").append(n.publisher());
                sb.append('\n');
            }
            for (TickerResolver.RelatedInstrument ri : r.related()) {
                MarketSnapshot s = ri.snapshot();
                sb.append("    related instrument (from the news above): ").append(ri.ticker());
                if (s != null && s.hasPrice() && Double.isFinite(s.dayChangePercent())) {
                    sb.append(String.format(Locale.ROOT, " %+.2f%% today", s.dayChangePercent()));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ---- helpers ----

    private static String chat(ChatModel model, String sys, String user) {
        List<ChatMessage> messages = List.of(SystemMessage.from(sys), UserMessage.from(user));
        ChatResponse resp = model.chat(ChatRequest.builder().messages(messages).build());
        AiMessage ai = resp.aiMessage();
        return ai == null || ai.text() == null ? "" : ai.text();
    }

    private static JsonNode parseJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try { return JSON.readTree(text.substring(start, end + 1)); } catch (Exception e) { return null; }
    }

    private static List<String> readStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode el : node) {
            String s = el.asText("").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String humanAge(long mins) {
        return mins < 60 ? mins + "m ago" : mins < 1440 ? (mins / 60) + "h ago" : (mins / 1440) + "d ago";
    }
    private static String cap(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…[truncated]"; }

    private void waitForClusters(ClusterRegistry registry, int target, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            int n = registry.size();
            if (n >= target) return;
            Thread.sleep(3000);
        }
    }

    private static List<Path> parkSnapshots(Path appDir) throws Exception {
        List<Path> parked = new ArrayList<>();
        for (String name : List.of("reddit-snapshot.json", "agent-snapshot.json")) {
            Path f = appDir.resolve(name);
            if (Files.exists(f)) {
                Path bak = appDir.resolve(name + ".pipesmoke-bak");
                Files.move(f, bak);
                parked.add(f);
            }
        }
        return parked;
    }

    private static void unparkSnapshots(List<Path> parked) throws Exception {
        for (Path f : parked) {
            Path bak = f.resolveSibling(f.getFileName() + ".pipesmoke-bak");
            Files.deleteIfExists(f);
            if (Files.exists(bak)) Files.move(bak, f);
        }
    }
}
