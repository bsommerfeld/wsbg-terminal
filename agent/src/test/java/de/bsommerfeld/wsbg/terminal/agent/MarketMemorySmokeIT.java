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

    /**
     * THE INTERPRETIVE loop end to end — the point of the whole module: the
     * desk gets TODAY's event beside the class's measured history on one
     * shelf and must write the weighing ("historically this class cost X —
     * against today's picture") — through the REAL weather-section prompt on
     * the REAL model, graded by the REAL deterministic examiner: every figure
     * the prose carries must exist on the shelf, and the text must actually
     * lean on the memory instead of ignoring the block.
     */
    @Test
    void deskWeighsTodaysEventAgainstTheMeasuredHistory() {
        OllamaAvailability.ensureOllama();
        GlobalConfig config = new GlobalConfig();
        LlmGate gate = new LlmGate();
        AgentBrain brain = new AgentBrain(config, new ApplicationEventBus(),
                new OllamaServerManager(), gate);
        ChatGateway gateway = new ChatGateway(brain, gate);

        // A register with a well-filled DOWNGRADE cell: 40 measured events,
        // skewed negative (median lands at -4.9 %, ~85 % negative).
        MarketEventArchive events = new MarketEventArchive(dir.resolve("interpret.jsonl"));
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 40; i++) {
            double car = i < 34 ? -2.5 - (i % 8) * 0.8 : 1.0 + (i % 3);
            events.append(new MarketEventRecord(today.minusMonths(6).plusDays(i).toString(),
                    "SYM" + i, null, "DOWNGRADE", "MarketBeat", null,
                    null, null, car, car * 1.2, "^GSPC", false));
        }
        events.append(MarketEventRecord.bare(today.toString(), "SAP.DE", "DE0007164600",
                "DOWNGRADE", "MarketBeat", "J.P. Morgan Overweight -> Neutral"));

        String memoryBlock = MarketMemoryBriefing.dayBlock(events, today, true);
        assertNotNull(memoryBlock);
        assertTrue(memoryBlock.contains("N=40"), "house cell must be licensed: " + memoryBlock);

        // The Abend shelf exactly as production assembles it: wire lines plus
        // the appended memory block.
        String shelf = "DATE: " + today + "\n"
                + "WIRE (Abend, kondensiert):\n"
                + "- [17:42] J.P. Morgan stuft SAP von Overweight auf Neutral herab - die Aktie "
                + "verliert 3,1 % auf 132,40 EUR, der Käfig reagiert gereizt.\n"
                + "- [18:05] Software-Werte europaweit schwächer, der Sektor gibt 1,2 % ab.\n"
                + memoryBlock;
        String system = PromptLoader.loadLocalized("weather-section", "de")
                .replace("{{LANGUAGE}}", "Deutsch");
        String user = "ABENDAUSGABE (Wetterbericht) vom " + today
                + "\n\nSECTION TO WRITE: ## Der Abend"
                + "\n\nMATERIAL (verified blocks — the only admissible evidence):\n" + shelf;

        String body = gateway.chat(brain.getDeepDiveModel(), system, user);
        assertNotNull(body);
        assertFalse(body.isBlank(), "the desk wrote nothing");
        System.out.println("[INTERPRET] ---\n" + body + "\n---");

        // Grade 1 (deterministic): no HARD examiner finding — every figure and
        // date the prose carries exists on the shelf (mixed-locale variant).
        List<DeepDiveFactCheck.Objection> hard = DeepDiveFactCheck
                .inspect(body, shelf, java.util.Set.of(), true, true).stream()
                .filter(DeepDiveFactCheck.Objection::hard).toList();
        assertTrue(hard.isEmpty(), "ungrounded figures/dates in the weighing: " + hard);

        // Grade 2: the weighing actually happened — today's subject AND a
        // historical anchor from the memory block both reached the prose.
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.contains("sap"), "today's event subject missing from the section");
        boolean leansOnMemory = lower.contains("historisch") || lower.contains("basisrate")
                || lower.contains("erfahrung") || lower.contains("median")
                || lower.contains("klasse") || body.contains("N=40")
                || lower.contains("solche") && lower.contains("herabstufung");
        assertTrue(leansOnMemory,
                "the section ignored the market-memory block entirely:\n" + body);
    }

    /**
     * The macro-surprise leg live end to end: real TradingView actuals of the
     * last days, groups judged by the REAL model (title-cached), signed
     * classes registered on the country's index — the ONE kind of world news
     * the register can honestly measure.
     */
    @Test
    void macroSurprisesRegisterOnTheIndex() {
        OllamaAvailability.ensureOllama();
        GlobalConfig config = new GlobalConfig();
        LlmGate gate = new LlmGate();
        AgentBrain brain = new AgentBrain(config, new ApplicationEventBus(),
                new OllamaServerManager(), gate);

        MarketEventArchive events = new MarketEventArchive(dir.resolve("macro.jsonl"));
        MarketMemoryService svc = new MarketMemoryService(
                new AdhocEventArchive(dir.resolve("a.jsonl")),
                new FearGreedHistoryArchive(dir.resolve("f.jsonl")), events, null);
        svc.setTradingViewCalendarClient(
                new de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient());
        svc.setMacroClassifier(new MacroClassifier(brain, gate));

        svc.harvestMacroSurprises();
        svc.harvestMacroSurprises(); // second sweep classifies what batch 1 didn't reach

        List<MarketEventRecord> registered = events.all();
        assertFalse(registered.isEmpty(),
                "no macro surprise registered from the last days' high-impact actuals");
        for (MarketEventRecord e : registered) {
            assertTrue(e.eventClass().matches(
                            "(INFLATION|ARBEITSMARKT|WACHSTUM|ZINSENTSCHEID|STIMMUNG)_(UEBER|UNTER)_PROGNOSE"),
                    "unexpected macro class: " + e.eventClass());
            assertTrue(e.symbol().startsWith("^"), "macro event must sit on an index");
            assertEquals("TV-Kalender", e.source());
        }
        System.out.println("[MACRO] registered=" + registered.size()
                + " first=" + registered.get(0).eventClass() + " / " + registered.get(0).detail());
    }

    /**
     * The TRANSMISSION-CHANNEL doctrine for singular world events (user
     * mandate 2026-07-15: no statistics, no symptom — describe what the event
     * touches and what that CAN lead to): a hazard on the shelf, no base
     * rate, and the section must read the channel without inventing a single
     * figure (the examiner guards that mechanically).
     */
    @Test
    void deskReadsTheChannelForASingularWorldEvent() {
        OllamaAvailability.ensureOllama();
        GlobalConfig config = new GlobalConfig();
        LlmGate gate = new LlmGate();
        AgentBrain brain = new AgentBrain(config, new ApplicationEventBus(),
                new OllamaServerManager(), gate);
        ChatGateway gateway = new ChatGateway(brain, gate);
        LocalDate today = LocalDate.now();

        String shelf = "DATE: " + today + "\n"
                + "WIRE (Abend, kondensiert):\n"
                + "- [18:10] Energie-Werte legen zu, der Sektor gewinnt 1,4 %.\n"
                + "WORLD CONTEXT (verified - hazards):\n"
                + "- STORM (hurricane): Hurrikan Delia, Kategorie 3, zieht auf die "
                + "US-Golfküste zu; Förderplattformen im Golf von Mexiko werden evakuiert "
                + "(NOAA NHC, Stand heute).\n";
        String system = PromptLoader.loadLocalized("weather-section", "de")
                .replace("{{LANGUAGE}}", "Deutsch");
        String user = "ABENDAUSGABE (Wetterbericht) vom " + today
                + "\n\nSECTION TO WRITE: ## Der Abend"
                + "\n\nMATERIAL (verified blocks — the only admissible evidence):\n" + shelf;

        String body = gateway.chat(brain.getDeepDiveModel(), system, user);
        assertNotNull(body);
        System.out.println("[CHANNEL] ---\n" + body + "\n---");

        // No invented figure survives (there is only ONE number on the shelf).
        List<DeepDiveFactCheck.Objection> hard = DeepDiveFactCheck
                .inspect(body, shelf, java.util.Set.of(), true, true).stream()
                .filter(DeepDiveFactCheck.Objection::hard).toList();
        assertTrue(hard.isEmpty(), "invented figures in the channel reading: " + hard);

        // The channel reading happened: the event reached the prose either as
        // possibility ("kann … führen") or as causal attribution of TODAY's
        // observed move ("wirkt als Treiber" — Ursache-vor-Band) — both are
        // channel language; a bare symptom claim ("Sektor wird steigen") is
        // neither, and an invented figure died at the examiner above.
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.contains("hurrikan") || lower.contains("golf") || lower.contains("sturm"),
                "the world event never reached the section:\n" + body);
        boolean channelLanguage = lower.contains("kann") || lower.contains("könnte")
                || lower.contains("droht") || lower.contains("risiko") || lower.contains("dürfte")
                || lower.contains("treiber") || lower.contains("wirkt") || lower.contains("stützt")
                || lower.contains("belast") || lower.contains("führt") || lower.contains("evaku");
        assertTrue(channelLanguage,
                "no transmission-channel language in the reading:\n" + body);
    }
}
