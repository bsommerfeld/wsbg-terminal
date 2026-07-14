package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.FnRssClient;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AdhocEventArchive;
import de.bsommerfeld.wsbg.terminal.db.FearGreedHistoryArchive;
import de.bsommerfeld.wsbg.terminal.db.MarketEventArchive;
import de.bsommerfeld.wsbg.terminal.db.MarketEventRecord;
import de.bsommerfeld.wsbg.terminal.edgar.EdgarClient;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.Bar;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The market memory's live end-to-end smoke: every collection leg against its
 * REAL source (FN ad-hocs, CNN F&amp;G history, Yahoo deep bars, SEC EDGAR),
 * the deterministic enrichment (CARs + regime stamp + confounded flag) over a
 * real price history, the volume profile over real hourly bars, and — when
 * Ollama is up — the enum classifier over formulaic German ad-hoc titles.
 *
 * <pre>MEMORY_SMOKE=true mvn test -pl agent -Dtest=MarketMemorySmokeIT -Dtest.excludedGroups=</pre>
 *
 * <p>What it proves: the CNN date-suffix endpoint really serves the full
 * daily series (floor 2020-09-21), Yahoo's negative-period1 unlock really
 * reaches 1927, EDGAR items really map to house classes, an event 60 days
 * back gets finite market-adjusted CARs plus its t−1 regime band, and two
 * events of one instrument two days apart flag each other confounded.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "MEMORY_SMOKE", matches = "true")
class MarketMemorySmokeIT {

    @TempDir
    Path dir;

    @Test
    void everyLegDeliversAndTheEnrichmentMeasures() {
        AdhocEventArchive adhocs = new AdhocEventArchive(dir.resolve("adhocs.jsonl"));
        FearGreedHistoryArchive fg = new FearGreedHistoryArchive(dir.resolve("fg.jsonl"));
        MarketEventArchive events = new MarketEventArchive(dir.resolve("events.jsonl"));
        MarketMemoryService svc = new MarketMemoryService(adhocs, fg, events, null);

        // --- Leg 1: FN ad-hocs (live RSS) -------------------------------
        svc.setFnRssClient(new FnRssClient());
        svc.harvestAdhocs();
        assertTrue(adhocs.size() > 0, "FN ad-hoc feed delivered nothing");
        svc.harvestAdhocs();
        int afterSecond = adhocs.size();
        svc.harvestAdhocs();
        assertEquals(afterSecond, adhocs.size(), "re-harvest must be idempotent");

        // --- Leg 2: CNN F&G history (live, full backfill) ----------------
        svc.setFearGreedClient(new FearGreedClient());
        svc.topUpFearGreedHistory();
        assertTrue(fg.size() > 1000,
                "F&G backfill too small: " + fg.size() + " days (expected the full series since 2020-09-21)");
        assertTrue(fg.byDate("2022-06-15").isPresent(), "mid-series day missing from the backfill");

        // --- Leg 3: Yahoo deep bars + volume profile (live) --------------
        YahooFinanceClient yahoo = new YahooFinanceClient();
        List<Bar> gspc = yahoo.fetchDailyBars("^GSPC", LocalDate.of(1950, 1, 1));
        assertTrue(gspc.size() > 10_000,
                "negative/deep period1 did not unlock the ^GSPC floor: " + gspc.size() + " bars");
        assertTrue(gspc.get(0).date().getYear() <= 1951, "oldest ^GSPC bar: " + gspc.get(0).date());
        List<Bar> hourly = yahoo.fetchHourlyBars("NVDA", 180);
        assertTrue(hourly.size() > 300, "NVDA hourly bars too thin: " + hourly.size());
        VolumeProfile.Profile profile = VolumeProfile.build(hourly).orElseThrow();
        assertTrue(profile.val() <= profile.poc() && profile.poc() <= profile.vah(),
                "value area must bracket the POC");
        assertTrue(profile.totalUnits() > 0);

        // --- Leg 4: EDGAR 8-K classes (live) -----------------------------
        EdgarClient edgar = new EdgarClient();
        List<EdgarClient.EdgarEvent> aapl = edgar.eightKEvents("AAPL");
        assertFalse(aapl.isEmpty(), "EDGAR delivered no mapped 8-K events for AAPL");
        for (EdgarClient.EdgarEvent e : aapl) {
            assertNotNull(e.date());
            assertFalse(e.eventClass().isBlank());
            events.append(MarketEventRecord.bare(e.date().toString(), "AAPL", null,
                    e.eventClass(), "EDGAR", e.items()));
        }

        // --- Leg 5: enrichment end to end (real prices, real regime) -----
        LocalDate eventDay = LocalDate.now().minusDays(60);
        events.append(MarketEventRecord.bare(eventDay.toString(), "AAPL", null,
                "DOWNGRADE", "SMOKE", "synthetic"));
        events.append(MarketEventRecord.bare(eventDay.plusDays(1).toString(), "AAPL", null,
                "UPGRADE", "SMOKE", "synthetic neighbour"));
        svc.setYahooClient(yahoo);
        svc.enrichEvents();

        MarketEventRecord enriched = events.byInstrument("AAPL").stream()
                .filter(r -> "SMOKE".equals(r.source()) && "DOWNGRADE".equals(r.eventClass()))
                .findFirst().orElseThrow();
        assertNotNull(enriched.carEvent(), "synthetic event was not enriched");
        assertTrue(Double.isFinite(enriched.carEvent()) && Math.abs(enriched.carEvent()) < 50,
                "CAR(-1,+1) implausible: " + enriched.carEvent());
        assertTrue(Double.isFinite(enriched.carShort()), "CAR(0,+5) missing");
        assertEquals("^GSPC", enriched.benchmark());
        assertNotNull(enriched.regimeBand(), "t-1 regime stamp missing (F&G era event)");
        assertEquals(Boolean.TRUE, enriched.confounded(),
                "two events one day apart must flag confounded");

        // --- Leg 6: the finished briefing block over real register data --
        events.append(MarketEventRecord.bare(LocalDate.now().toString(), null, "DE0007164600",
                "GEWINNWARNUNG", "EQS", "smoke title"));
        String block = MarketMemoryBriefing.dayBlock(events, LocalDate.now(), true);
        assertNotNull(block, "today's event class produced no briefing block");
        assertTrue(block.contains("Attribuierter Prior") && block.contains("Disziplin"));
    }

    /**
     * The enum judge against the REAL resident gemma4 — formulaic titles must
     * land in their obvious classes (the whole point of the closed-enum
     * design). Separate test so the data legs above run without Ollama.
     */
    @Test
    void classifierLandsFormulaicTitlesInTheirClasses() {
        OllamaAvailability.ensureOllama();
        GlobalConfig config = new GlobalConfig();
        LlmGate gate = new LlmGate();
        AgentBrain brain = new AgentBrain(config, new ApplicationEventBus(),
                new OllamaServerManager(), gate);
        AdhocClassifier classifier = new AdhocClassifier(brain, gate);

        Map<Integer, String> verdicts = classifier.classify(List.of(
                "Musterwerk AG senkt Umsatz- und Ergebnisprognose für das Geschäftsjahr",
                "Beispiel SE beschließt Kapitalerhöhung um bis zu 10 % des Grundkapitals",
                "Muster Holding erhält Großauftrag über 240 Mio. EUR aus Nordamerika"));
        assertFalse(verdicts.isEmpty(), "classifier returned no verdicts");
        assertEquals("GEWINNWARNUNG", verdicts.get(1), "guidance cut must classify as GEWINNWARNUNG");
        assertEquals("KAPITALERHOEHUNG", verdicts.get(2));
        assertEquals("GROSSAUFTRAG", verdicts.get(3));
    }
}
