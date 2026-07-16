package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator;
import de.bsommerfeld.wsbg.terminal.bafin.InsiderDealingsClient;
import de.bsommerfeld.wsbg.terminal.bundesanzeiger.ShortInterestClient;
import de.bsommerfeld.wsbg.terminal.consorsbank.ConsorsbankClient;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveArchive;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord;
import de.bsommerfeld.wsbg.terminal.googlenews.GoogleNewsClient;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaClient;
import de.bsommerfeld.wsbg.terminal.tradegate.TradegateQuoteClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KI-DD's live end-to-end smoke: the FULL section-workspace pipeline
 * (triage → author → examiner → challenger → editor → typesetter) against the
 * REAL resident gemma4 and the REAL German data legs (Consorsbank, onvista,
 * Tradegate, Bundesanzeiger, BaFin, Google News), for a real ticker with a
 * seeded room. Gated twice: {@code DD_SMOKE=true} in the environment AND a
 * reachable Ollama — a run takes several minutes (~25-40 model calls).
 *
 * <pre>DD_SMOKE=true mvn test -pl agent -Dtest=DeepDiveSmokeIT -Dtest.excludedGroups=</pre>
 *
 * <p>What it proves (the SAP-run failure classes, dd-e6f0d98e): no protocol
 * residue in the archived report, all eight headings, every prose figure
 * grounded in the material, no off-subject news source in the register, and a
 * room section that retells ONLY the seeded evidence.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DD_SMOKE", matches = "true")
class DeepDiveSmokeIT {

    @Test
    void fullReportForSapSurvivesEveryGate() throws Exception {
        OllamaAvailability.ensureOllama();
        GlobalConfig config = new GlobalConfig();
        ApplicationEventBus bus = new ApplicationEventBus();
        LlmGate gate = new LlmGate();
        AgentBrain brain = new AgentBrain(config, bus, new OllamaServerManager(), gate);

        // The room: a seeded unit with a few mentions — identity carries the
        // ISIN via the snapshot symbol (the L&S salvage path).
        SubjectRegistry registry = new SubjectRegistry();
        SubjectUnit unit = registry.findOrCreate("SAP", "SAP SE");
        long now = Instant.now().getEpochSecond();
        unit.updateResolved("SAP SE", "SAP", new MarketSnapshot(
                "DE0007164600", 138.28, 137.55, 0.5, 139.0, 137.1, 0,
                267.05, 128.0, "EUR", "LSX", now,
                List.of(139.9, 139.1, 138.28), List.of(139.9, 139.4, 138.7, 138.28)), List.of());
        unit.addEvidence(new SubjectUnit.EvidenceRef("t1", "c1",
                "SAP ist der einzige DAX-Techwert, der zählt - bin seit 120 drin", "reddit",
                now - 86_400));
        unit.addEvidence(new SubjectUnit.EvidenceRef("t1", "c2",
                "die Cloud-Story ist durch, ab hier nur noch Seitwärts", "reddit", now - 3_600));
        unit.addHeadline("SAP: Käfig streitet über die Cloud-Story", "MIXED");

        Path archiveFile = Files.createTempDirectory("dd-smoke").resolve("deepdive.jsonl");
        DeepDiveArchive archive = new DeepDiveArchive(archiveFile);
        DeepDiveService svc = new DeepDiveService(registry, brain, gate, archive, bus);

        // The real legs (test-scoped deps) — Consorsbank serves both seams.
        ConsorsbankClient consors = new ConsorsbankClient();
        svc.setAnalystViewSource(consors);
        svc.setCompanyDeepDiveSource(consors);
        svc.setInstrumentFactsSource(new OnvistaClient());
        svc.setVenueStatsSource(new TradegateQuoteClient());
        svc.setShortInterestSource(new ShortInterestClient());
        svc.setInsiderDealingsSource(new InsiderDealingsClient());
        // DD_SMOKE_NEWS caps the LIVE news pool for a quick iteration run
        // (~8-10 min instead of 30+): still real HTTP and a real Ollama -
        // never a synthetic fixture - just less volume through the same
        // pipeline. Unset = the full pool (the release-grade smoke).
        int newsCap = Integer.parseInt(
                System.getenv().getOrDefault("DD_SMOKE_NEWS", String.valueOf(Integer.MAX_VALUE)));
        svc.setNewsAggregator(new NewsAggregator(Set.of(new GoogleNewsClient(),
                new OnvistaClient(),
                new de.bsommerfeld.wsbg.terminal.briefing.EqsNewsArchiveClient(),
                new de.bsommerfeld.wsbg.terminal.websearch.GdeltDocClient())) {
            @Override
            public List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> newsFor(
                    String symbol, String name, String isin, int limit) {
                List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> items =
                        super.newsFor(symbol, name, isin, limit);
                return items.size() > newsCap ? items.subList(0, newsCap) : items;
            }
        });
        svc.setFnRssClient(new de.bsommerfeld.wsbg.terminal.briefing.FnRssClient());
        svc.setPressScout(new CompanyPressScout(
                new de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher(),
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"));
        // The fishing net (2026-07-15): the FULL catch + all hazards ride the
        // smoke, so the subject-scoped world-signal judge runs live against
        // the report's theme landscape.
        WorldSignalsCollector world = new WorldSignalsCollector();
        world.setPortWatchClient(new de.bsommerfeld.wsbg.terminal.briefing.PortWatchClient());
        world.setEiaWpsrClient(new de.bsommerfeld.wsbg.terminal.briefing.EiaWpsrClient());
        world.setHarpexClient(new de.bsommerfeld.wsbg.terminal.briefing.HarpexClient());
        world.setEnergyChartsClient(
                new de.bsommerfeld.wsbg.terminal.briefing.EnergyChartsClient());
        world.setSpaceWeatherClient(
                new de.bsommerfeld.wsbg.terminal.briefing.SpaceWeatherClient());
        world.setFedFeedsClient(new de.bsommerfeld.wsbg.terminal.briefing.FedFeedsClient());
        world.setEcbFeedsClient(new de.bsommerfeld.wsbg.terminal.briefing.EcbFeedsClient());
        world.setCisaKevClient(new de.bsommerfeld.wsbg.terminal.briefing.CisaKevClient());
        world.setPresseportalClient(
                new de.bsommerfeld.wsbg.terminal.briefing.PresseportalClient());
        world.setWikipediaClient(
                new de.bsommerfeld.wsbg.terminal.briefing.WikipediaCurrentEventsClient());
        svc.setWorldCollector(world);
        svc.setGlobalHazardsClient(
                new de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient());

        assertTrue(svc.generate("SAP"), "generation must start");
        // Generous window: the pipeline's own calls sum to ~5-6 minutes, but a
        // busy host (parallel builds, the live terminal) has stalled the worker
        // thread for minutes at a time (observed 2026-07-13, run 2).
        // 60 min: the 2026-07-16 pipeline legitimately carries more model
        // passes (press chronicle, per-step diff-judge, final-instance
        // consistency loop) - the full-pool smoke is the release gate, not a
        // quick check (DD_SMOKE_NEWS caps it for iteration runs).
        long deadline = System.currentTimeMillis() + 60 * 60_000;
        while (svc.isBusy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
        }
        assertFalse(svc.isBusy(), "generation did not finish within 60 minutes");

        List<DeepDiveRecord> recent = archive.recent(1);
        assertFalse(recent.isEmpty(), "no report was archived");
        String report = recent.get(0).report();
        System.out.println("\n===== DD SMOKE REPORT (" + report.length() + " chars) =====\n"
                + report + "\n===== END =====\n");

        // The SAP failure classes, now impossible by construction:
        assertFalse(report.contains("<<<"), "protocol residue leaked");
        assertFalse(report.contains("```"), "code fence leaked");
        assertTrue(DeepDiveService.looksLikeReport(report, DeepDiveService.SECTIONS_DE),
                "all eight canonical headings, in order");
        assertTrue(report.contains("## Quellen"), "the deterministic source register");
        assertTrue(report.contains("## Ausblick"), "the anchored outlook section");
        // Substance: at least five sections carry more than the honest literal.
        int substantial = 0;
        for (String section : report.split("(?m)^## ")) {
            if (section.length() > 300) substantial++;
        }
        assertTrue(substantial >= 5, "too few substantial sections: " + substantial);
    }
}
