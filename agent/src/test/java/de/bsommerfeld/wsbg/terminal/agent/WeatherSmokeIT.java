package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportArchive;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Wetterbericht Redaktion's live end-to-end smoke: the FULL section
 * workspace (window wires → shelves → author → examiner → challenger →
 * typesetter) against the REAL resident gemma4, fed with the REAL day's
 * headlines from the install's permanent archive (copied read-only into a
 * temp archive — the live install is never touched). Since 2026-07-15 the
 * FULL fishing net rides along live: every world leg collects, the relevance
 * triage judges the real day's signals, and the frozen record must carry the
 * catch plus the Weltlage map figure. Gated twice: {@code WEATHER_SMOKE=true}
 * AND a reachable Ollama; a run makes ~20-40 model calls.
 *
 * <pre>WEATHER_SMOKE=true mvn test -pl agent -Dtest=WeatherSmokeIT -Dtest.excludedGroups=</pre>
 *
 * <p>What it proves (the 2026-07-13 live-report failure classes): all five
 * system headings in order, no wire markup or block labels in the prose, the
 * bold budget held per section, and no protocol residue.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "WEATHER_SMOKE", matches = "true")
class WeatherSmokeIT {

    @Test
    void eveningEditionForTheRealDaySurvivesEveryGate() throws Exception {
        OllamaAvailability.ensureOllama();
        Path liveHeadlines = StorageUtils.getAppDataDir()
                .resolve("archive").resolve("headlines.jsonl");
        assertTrue(Files.isRegularFile(liveHeadlines),
                "no live headline archive to smoke against: " + liveHeadlines);

        Path tmp = Files.createTempDirectory("weather-smoke");
        Files.copy(liveHeadlines, tmp.resolve("headlines.jsonl"));
        HeadlineArchive headlines = new HeadlineArchive(tmp.resolve("headlines.jsonl"));
        WeatherReportArchive reports = new WeatherReportArchive(tmp.resolve("weather.jsonl"));

        GlobalConfig config = new GlobalConfig();
        ApplicationEventBus bus = new ApplicationEventBus();
        LlmGate gate = new LlmGate();
        AgentBrain brain = new AgentBrain(config, bus, new OllamaServerManager(), gate);
        WeatherStatsCollector collector = new WeatherStatsCollector(new YahooFinanceClient());
        // The attributed-press leg rides along live — the big-picture shelf
        // must carry real ARD top news, not just aggregates.
        collector.setTagesschauClient(
                new de.bsommerfeld.wsbg.terminal.briefing.TagesschauClient());
        // The 2026-07-14 legs ride the smoke live: the market press review is
        // what the weave loop chews through (the uncap mandate's core), the
        // world-weather/hazards legs feed the new shelves and figures.
        collector.setMarketPressClient(
                new de.bsommerfeld.wsbg.terminal.briefing.MarketPressClient());
        collector.setWorldWeatherClient(
                new de.bsommerfeld.wsbg.terminal.briefing.WorldWeatherClient());
        collector.setGlobalHazardsClient(
                new de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient());
        // The FULL fishing net rides the smoke live (2026-07-15): every world
        // leg collects, the relevance triage judges the real day's signals,
        // and the frozen record must carry the catch.
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
        world.setWhiteHouseClient(
                new de.bsommerfeld.wsbg.terminal.briefing.WhiteHouseActionsClient());
        world.setFederalRegisterClient(
                new de.bsommerfeld.wsbg.terminal.briefing.FederalRegisterClient());
        world.setDawumClient(new de.bsommerfeld.wsbg.terminal.briefing.DawumClient());
        world.setEuPresscornerClient(
                new de.bsommerfeld.wsbg.terminal.briefing.EuPresscornerClient());
        world.setPresseportalClient(
                new de.bsommerfeld.wsbg.terminal.briefing.PresseportalClient());
        world.setWhoOutbreakClient(
                new de.bsommerfeld.wsbg.terminal.briefing.WhoOutbreakClient());
        world.setDiviClient(new de.bsommerfeld.wsbg.terminal.briefing.DiviClient());
        world.setRkiClient(new de.bsommerfeld.wsbg.terminal.briefing.RkiSurveillanceClient());
        world.setCisaKevClient(new de.bsommerfeld.wsbg.terminal.briefing.CisaKevClient());
        world.setSportsClient(
                new de.bsommerfeld.wsbg.terminal.briefing.SportsCalendarClient());
        world.setHolidayClient(
                new de.bsommerfeld.wsbg.terminal.briefing.HolidayCalendarClient());
        world.setSanctionsMapClient(
                new de.bsommerfeld.wsbg.terminal.briefing.SanctionsMapClient());
        world.setTrafficClient(new de.bsommerfeld.wsbg.terminal.briefing.TrafficClient());
        world.setWikipediaClient(
                new de.bsommerfeld.wsbg.terminal.briefing.WikipediaCurrentEventsClient());
        collector.setWorldSignalsCollector(world);
        collector.setEcbFeedsClient(new de.bsommerfeld.wsbg.terminal.briefing.EcbFeedsClient());

        WeatherReportService svc = new WeatherReportService(brain, gate,
                headlines, reports, collector, config, bus);
        long t0 = System.currentTimeMillis();
        svc.generateForToday();
        System.out.println("Wetterbericht smoke took "
                + (System.currentTimeMillis() - t0) / 1000 + " s");

        List<WeatherReportRecord> written = reports.recent(1);
        assertFalse(written.isEmpty(), "no report was archived — the day had headlines");
        String report = written.get(0).text();
        System.out.println("\n===== WEATHER SMOKE REPORT (" + report.length()
                + " chars) =====\n" + report + "\n===== END =====\n");

        // The five system headings, in order — never a model heading.
        int last = -1;
        for (String heading : WeatherReportService.SECTIONS_DE) {
            int at = report.indexOf("## " + heading);
            assertTrue(at > last, "heading missing or out of order: " + heading);
            last = at;
        }
        // The 2026-07-13 formatting findings, now impossible by construction:
        assertFalse(report.contains("[!]"), "wire flag leaked");
        assertFalse(report.contains("<<<"), "protocol residue leaked");
        assertFalse(report.contains("```"), "code fence leaked");
        assertFalse(report.contains("WIRE STORIES"), "block label leaked");
        assertFalse(Pattern.compile("\\[[A-Z0-9.^\\-]{1,12}]").matcher(report).find(),
                "bracketed ticker markup leaked");
        // Bold budget per section.
        for (String section : report.split("(?m)^## ")) {
            Matcher bold = Pattern.compile("\\*\\*(.+?)\\*\\*", Pattern.DOTALL).matcher(section);
            int spans = 0;
            while (bold.find()) spans++;
            assertTrue(spans <= WeatherReportService.MAX_BOLD_SPANS,
                    "bold budget broken (" + spans + " spans) in: " + section);
        }

        // The fishing net (2026-07-15): the frozen record must carry the full
        // catch — every leg is network-best-effort, but a live machine where
        // EVERY world leg whiffed means the net is broken, not the weather.
        WeatherReportRecord rec = written.get(0);
        assertTrue(rec.world() != null, "no world block frozen");
        WeatherReportRecord.WorldSignals sig = rec.world().worldSignals();
        assertTrue(sig != null, "fishing net froze nothing — every world leg whiffed");
        System.out.println("world signals frozen: chokepoints=" + sig.chokepoints().size()
                + " oil=" + (sig.oilStocks() != null)
                + " freight=" + (sig.freight() != null)
                + " power=" + (sig.power() != null)
                + " policy=" + sig.policy().size()
                + " polls=" + sig.polls().size()
                + " civic=" + sig.civic().size()
                + " health=" + (sig.health() != null)
                + " cyber=" + sig.cyber().size()
                + " conflicts=" + sig.conflicts().size()
                + " sports=" + sig.sportsTomorrow().size()
                + " holidays=" + (sig.holidays() != null));
        // The frozen world map rides the charts whenever any geocoded marker
        // exists (chokepoints alone guarantee 28).
        if (!sig.chokepoints().isEmpty()) {
            assertTrue(rec.charts().stream().anyMatch(c ->
                            c.title() != null && c.title().contains("Weltlage")),
                    "chokepoints frozen but no Weltlage map figure");
        }
        System.out.println("charts frozen: " + rec.charts().size() + " — "
                + rec.charts().stream().map(WeatherReportRecord.ChartStat::title).toList());
    }
}
