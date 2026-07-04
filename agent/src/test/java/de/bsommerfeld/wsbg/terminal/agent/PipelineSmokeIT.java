package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditSnapshotStore;
import de.bsommerfeld.wsbg.terminal.reddit.RssRedditScraper;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
 * clustering), waits for live clusters to form, then drives the PRODUCTION
 * subject-unit path — {@link EditorialAgent#attributeCluster} per cluster, then
 * {@code mergeIdentities} + {@link EditorialAgent#composeAndPublishUnit} per dirty
 * {@link SubjectUnit} — with full visibility, so the human can judge <em>cluster
 * quality</em>, <em>information density</em> and <em>headline + data quality</em>
 * from one transcript. Every stage uses the production class directly; nothing is
 * mirrored by hand.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "PIPELINE_SMOKE", matches = "true")
class PipelineSmokeIT {

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
            LlmGate gate = new LlmGate();
            AgentBrain brain = new AgentBrain(config, bus, osm, gate);
            ClusterRegistry registry = new ClusterRegistry();
            SubjectRegistry subjectRegistry = new SubjectRegistry();
            YahooFinanceClient yahoo = new YahooFinanceClient(config);
            RssRedditScraper rss = new RssRedditScraper(redditRepo, config, bus);

            // Production clustering. The ctor self-starts the scan loop.
            ClusterEngine clusterEngine = new ClusterEngine(registry);
            new PassiveMonitorService(rss, brain, bus, redditRepo, agentRepo,
                    new RedditSnapshotStore(), new AgentSnapshotStore(), registry,
                    subjectRegistry, clusterEngine, config);

            // The production editorial pipeline (same wiring as PipelineStagesIT).
            EditorialAgent editorial = new EditorialAgent(brain, gate, registry, agentRepo, redditRepo,
                    bus, new I18nService(config), yahoo,
                    subjectRegistry, config);

            // RSS cold-start is slow: scanSubreddit fetches every thread context
            // serially (anon rate limiter, ~7s each) and only clusters the whole
            // batch afterwards — so first clusters land ~5-7 min in.
            System.out.println("[SMOKE] waiting for live clusters to form from r/" + SUB + " ...");
            waitForClusters(registry, 3, Duration.ofMinutes(14));
            // give vision + a couple of scan cycles a moment to enrich the briefs
            Thread.sleep(Duration.ofSeconds(20).toMillis());

            List<InvestigationCluster> all = new ArrayList<>(registry.getAllClusters());
            assertFalse(all.isEmpty(), "no clusters formed from live Reddit — cannot judge quality");
            assertNotNull(brain.getAgentModel(), "agent model not ready");
            // Bound the model work: evaluate at most the first 6 clusters.
            List<InvestigationCluster> clusters = all.size() > 6 ? all.subList(0, 6) : all;
            System.out.println("[SMOKE] " + all.size() + " live cluster(s) formed; evaluating "
                    + clusters.size() + ".\n");

            ReportBuilder reportBuilder = new ReportBuilder(redditRepo, brain);
            AtomicInteger newsBackedSubjects = new AtomicInteger();

            // Stage 1+2 — per cluster: extract + resolve + attribute into the
            // feed-wide SubjectRegistry (production attributeCluster).
            int idx = 0;
            for (InvestigationCluster cluster : clusters) {
                idx++;
                List<HeadlineRecord> prior = agentRepo.getHeadlinesByClusterId(cluster.id);
                String brief = reportBuilder.buildReportData(cluster, prior);
                printClusterHeader(idx, clusters.size(), cluster, brief);
                List<ResolvedSubject> resolved = editorial.attributeCluster(cluster.id);
                printResolved(resolved, newsBackedSubjects);
                System.out.println();
            }

            // Stage 3 — identity merge, then one headline per dirty unit
            // (production composeAndPublishUnit, heaviest evidence first).
            subjectRegistry.mergeIdentities();
            List<SubjectUnit> toCompose = subjectRegistry.drainDirty().stream()
                    .map(subjectRegistry::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(SubjectUnit::evidenceCount).reversed())
                    .toList();
            System.out.println("[SMOKE] " + toCompose.size() + " dirty subject unit(s) to compose.\n");

            AtomicInteger totalHeadlines = new AtomicInteger();
            AtomicInteger tickerHeadlines = new AtomicInteger();
            AtomicInteger withMarketData = new AtomicInteger();
            System.out.println("  HEADLINES:");
            for (SubjectUnit unit : toCompose) {
                boolean published = editorial.composeAndPublishUnit(unit);
                printUnitOutcome(unit, published, agentRepo, totalHeadlines, tickerHeadlines, withMarketData);
            }
            if (toCompose.isEmpty()) System.out.println("    (none — no dirty units)");

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
                for (RawNewsItem n : r.news()) {
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

    /** Prints the unit's compose outcome — the freshly published record, or the silence. */
    private static void printUnitOutcome(SubjectUnit unit, boolean published, AgentRepository repo,
            AtomicInteger total, AtomicInteger withTicker, AtomicInteger withData) {
        if (!published) {
            System.out.printf(Locale.ROOT, "    [silent]    %s (%d evidence)%n",
                    unit.canonicalName(), unit.evidenceCount());
            return;
        }
        total.incrementAndGet();
        List<HeadlineRecord> records = repo.getHeadlinesByClusterId(unit.id);
        HeadlineRecord r = records.isEmpty() ? null : records.get(records.size() - 1);
        if (r == null) {
            System.out.printf(Locale.ROOT, "    [PUBLISHED] %s — record not found in repo?!%n",
                    unit.canonicalName());
            return;
        }
        if (r.tickerSymbol() != null && !r.tickerSymbol().isBlank()) withTicker.incrementAndGet();
        if (r.snapshot() != null && r.snapshot().hasPrice()) withData.incrementAndGet();
        System.out.printf(Locale.ROOT,
                "    [PUBLISHED] %-9s %-7s %s%s%n      \"%s\"%n",
                nz(String.valueOf(r.highlight())), nz(String.valueOf(r.sentiment())),
                r.tickerSymbol() == null ? "" : r.tickerSymbol(),
                r.priceMovePercent() == null ? "" : String.format(Locale.ROOT, " %+.1f%%", r.priceMovePercent()),
                r.headline());
    }

    private static void printSummary(int clusters, int headlines, int withTicker, int withData, int newsBacked) {
        System.out.println("============================================================");
        System.out.println("[SMOKE] SUMMARY");
        System.out.println("  clusters evaluated   : " + clusters);
        System.out.println("  headlines published  : " + headlines);
        System.out.println("  …carrying a ticker   : " + withTicker);
        System.out.println("  …backed by live data : " + withData);
        System.out.println("  news-backed subjects : " + newsBacked);
        System.out.println("============================================================");
    }

    // ---- helpers ----

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
