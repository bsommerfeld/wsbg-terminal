package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.WeatherReportService;
import de.bsommerfeld.wsbg.terminal.agent.event.WeatherReportFinishedEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.WeatherReportStartedEvent;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportArchive;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Wetterbericht widget's socket backend, both directions (the
 * {@link SettingsBridge} pattern): broadcasts topic {@code weather} — the
 * schedule state plus the recent report history — on client open, after every
 * generation start/finish, and after a command; handles inbound
 * {@code {type:"weather", payload:{command:"get"|"set-time", value?}}}.
 * The report-time change persists to {@code weather.report-time} and re-arms
 * the service's scheduler live.
 */
@Singleton
public class WeatherReportBridge {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherReportBridge.class);

    /** Reports shipped to the page per push — the widget's browsable history window. */
    private static final int HISTORY_LIMIT = 30;

    private final WeatherReportService service;
    private final WeatherReportArchive archive;
    private final GlobalConfig config;
    private final PushHub hub;

    @Inject
    public WeatherReportBridge(WeatherReportService service, WeatherReportArchive archive,
            GlobalConfig config, PushHub hub, ApplicationEventBus bus) {
        this.service = service;
        this.archive = archive;
        this.config = config;
        this.hub = hub;
        bus.register(this);
        hub.on("weather", this::onCommand);
        hub.onClientOpen(this::push);
    }

    @Subscribe
    public void onGenerationStarted(WeatherReportStartedEvent event) {
        push();
    }

    @Subscribe
    public void onGenerationFinished(WeatherReportFinishedEvent event) {
        push();
    }

    private void onCommand(Map<String, Object> payload) {
        String command = Payloads.str(payload.get("command"));
        if ("set-time".equals(command)) {
            String value = Payloads.str(payload.get("value"));
            if (isValidTime(value)) {
                config.getWeather().setReportTime(value);
                config.save();
                service.rearm();
                LOG.info("Wetterbericht report time set to {}", value);
            } else {
                LOG.warn("Ignoring invalid Wetterbericht report time: {}", value);
            }
        }
        push(); // covers "get" and echoes the state after "set-time"
    }

    private static boolean isValidTime(String value) {
        if (value == null) return false;
        try {
            LocalTime.parse(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void push() {
        hub.broadcastSafe("weather", this::snapshot);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generating", service.isGenerating());
        out.put("reportTime", config.getWeather().getReportTime());
        out.put("nextRunAt", service.nextRunEpochMs());
        out.put("today", LocalDate.now().toString());
        List<Map<String, Object>> reports = new ArrayList<>();
        for (WeatherReportRecord r : archive.recent(HISTORY_LIMIT)) {
            reports.add(reportJson(r));
        }
        out.put("reports", reports);
        return out;
    }

    private static Map<String, Object> reportJson(WeatherReportRecord r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", r.date());
        out.put("generatedAt", r.generatedAt());
        out.put("text", r.text());
        out.put("language", r.language());
        out.put("headlineCount", r.headlineCount());
        out.put("importantCount", r.importantCount());
        List<Map<String, Object>> indices = new ArrayList<>();
        for (WeatherReportRecord.IndexStat s : r.indices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("symbol", s.symbol());
            m.put("last", s.last());
            m.put("changePercent", s.changePercent());
            m.put("volume", s.volume());
            m.put("spark", s.spark());
            indices.add(m);
        }
        out.put("indices", indices);
        List<Map<String, Object>> tickers = new ArrayList<>();
        for (WeatherReportRecord.TickerStat s : r.tickers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticker", s.ticker());
            m.put("name", s.name());
            m.put("headlineCount", s.headlineCount());
            m.put("importantCount", s.importantCount());
            m.put("price", s.price());
            m.put("currency", s.currency());
            m.put("changePercent", s.changePercent());
            m.put("volume", s.volume());
            tickers.add(m);
        }
        out.put("tickers", tickers);
        List<Map<String, Object>> news = new ArrayList<>();
        for (WeatherReportRecord.NewsStat s : r.news()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", s.title());
            m.put("source", s.source());
            m.put("citations", s.citations());
            news.add(m);
        }
        out.put("news", news);
        return out;
    }
}
