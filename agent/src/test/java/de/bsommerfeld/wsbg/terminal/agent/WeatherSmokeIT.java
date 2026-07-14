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
 * temp archive — the live install is never touched). World legs beyond Yahoo
 * stay absent — every leg is optional by design, and the section loop is
 * what this smoke proves. Gated twice: {@code WEATHER_SMOKE=true} AND a
 * reachable Ollama; a run makes ~15-30 model calls.
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
    }
}
