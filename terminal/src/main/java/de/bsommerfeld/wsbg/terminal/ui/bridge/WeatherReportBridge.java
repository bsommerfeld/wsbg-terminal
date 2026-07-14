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
            m.put("currency", s.currency());
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
            m.put("turnoverEur", s.turnoverEur());
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
        if (r.sentiment() != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("score", r.sentiment().score());
            s.put("band", r.sentiment().band());
            s.put("previousClose", r.sentiment().previousClose());
            if (!r.sentiment().components().isEmpty()) {
                List<Map<String, Object>> components = new ArrayList<>();
                for (WeatherReportRecord.SentimentComponent c : r.sentiment().components()) {
                    components.add(map("key", c.key(), "score", c.score()));
                }
                s.put("components", components);
            }
            s.put("cryptoScore", r.sentiment().cryptoScore());
            s.put("cryptoBand", r.sentiment().cryptoBand());
            out.put("sentiment", s);
        }
        if (r.world() != null) {
            out.put("world", worldJson(r.world()));
        }
        if (!r.charts().isEmpty()) {
            List<Map<String, Object>> charts = new ArrayList<>(r.charts().size());
            for (WeatherReportRecord.ChartStat c : r.charts()) {
                charts.add(map("section", c.section(), "title", c.title(),
                        "note", c.note(), "svg", c.svg()));
            }
            out.put("charts", charts);
        }
        return out;
    }

    /**
     * The Abendausgabe's world block, section by section; empty lists and null
     * scalars are simply omitted so old reports and failed legs cost nothing
     * on the wire and the page gates sections on presence.
     */
    private static Map<String, Object> worldJson(WeatherReportRecord.WorldStats w) {
        Map<String, Object> out = new LinkedHashMap<>();
        putList(out, "sectors", w.sectors(), WeatherReportBridge::indexJson);
        putList(out, "overnight", w.overnight(), WeatherReportBridge::indexJson);
        putList(out, "rates", w.rates(), r -> map("name", r.name(), "percent", r.percent(),
                "previousPercent", r.previousPercent(), "dateIso", r.dateIso()));
        if (w.pulse() != null) {
            out.put("pulse", map("bullish", w.pulse().bullish(), "bearish", w.pulse().bearish(),
                    "neutral", w.pulse().neutral(), "redCount", w.pulse().redCount(),
                    "busiestHour", w.pulse().busiestHour(),
                    "distinctSubjects", w.pulse().distinctSubjects()));
        }
        putList(out, "adhocs", w.adhocs(), a -> map("title", a.title(), "isin", a.isin(),
                "time", a.time(), "kaefigTicker", a.kaefigTicker()));
        putList(out, "analystActions", w.analystActions(),
                a -> map("title", a.title(), "time", a.time()));
        putList(out, "macroActuals", w.macroActuals(), WeatherReportBridge::macroJson);
        putList(out, "macroEvents", w.macroEvents(), WeatherReportBridge::macroJson);
        if (w.pressDigest() != null) out.put("pressDigest", w.pressDigest());
        putList(out, "movers", w.movers(), m -> map("symbol", m.symbol(), "name", m.name(),
                "changePercent", m.changePercent(), "price", m.price(), "kind", m.kind(),
                "inKaefig", m.inKaefig()));
        if (w.putCall() != null) {
            out.put("putCall", map("total", w.putCall().total(), "equity", w.putCall().equity(),
                    "index", w.putCall().index(), "dateIso", w.putCall().dateIso()));
        }
        putList(out, "social", w.social(), s -> map("ticker", s.ticker(), "name", s.name(),
                "mentions", s.mentions(), "rank", s.rank(), "rankClimb", s.rankClimb()));
        if (w.crypto() != null) {
            Map<String, Object> c = map("marketCapUsd", w.crypto().marketCapUsd(),
                    "mcapChangePercent", w.crypto().mcapChangePercent(),
                    "btcDominance", w.crypto().btcDominance(),
                    "fearGreedScore", w.crypto().fearGreedScore(),
                    "fearGreedBand", w.crypto().fearGreedBand(),
                    "fundingRatePercent", w.crypto().fundingRatePercent(),
                    "dvol", w.crypto().dvol());
            if (!w.crypto().trending().isEmpty()) {
                List<Map<String, Object>> trending = new ArrayList<>();
                for (WeatherReportRecord.TrendingCoin t : w.crypto().trending()) {
                    trending.add(map("name", t.name(), "symbol", t.symbol(),
                            "changePercent", t.changePercent()));
                }
                c.put("trending", trending);
            }
            out.put("crypto", c);
        }
        putList(out, "bets", w.bets(), b -> map("question", b.question(), "outcome", b.outcome(),
                "probabilityPercent", b.probabilityPercent(), "volume24hUsd", b.volume24hUsd()));
        putList(out, "shortVolume", w.shortVolume(), s -> map("symbol", s.symbol(),
                "shortPercent", s.shortPercent(), "dateIso", s.dateIso()));
        putList(out, "depth", w.depth(), d -> map("ticker", d.ticker(),
                "targetPrice", d.targetPrice(), "targetCurrency", d.targetCurrency(),
                "upsidePercent", d.upsidePercent(), "buy", d.buy(), "hold", d.hold(),
                "sell", d.sell(), "nextEventTitle", d.nextEventTitle(),
                "nextEventDate", d.nextEventDate(), "shortPercent", d.shortPercent(),
                "topShortHolder", d.topShortHolder(), "insiderNote", d.insiderNote()));
        putList(out, "watchlist", w.watchlist(), e -> map("name", e.name(), "ticker", e.ticker(),
                "changePercent", e.changePercent(), "price", e.price(), "currency", e.currency(),
                "tldr", e.tldr()));
        if (!w.deepDives().isEmpty()) out.put("deepDives", w.deepDives());
        putList(out, "outlook", w.outlook(), o -> map("title", o.title(), "detail", o.detail(),
                "impact", o.impact(), "time", o.time(), "kind", o.kind()));
        if (w.pegel() != null) {
            out.put("pegel", map("centimeters", w.pegel().centimeters(), "state", w.pegel().state()));
        }
        if (w.usDebtUsd() != null) out.put("usDebtUsd", w.usDebtUsd());
        if (w.exchangeWeather() != null) {
            out.put("exchangeWeather", map("temperatureCelsius",
                    w.exchangeWeather().temperatureCelsius(), "icon", w.exchangeWeather().icon()));
        }
        if (w.moon() != null) {
            out.put("moon", map("phase", w.moon().phase(),
                    "illuminationPercent", w.moon().illuminationPercent(),
                    "daysToFull", w.moon().daysToFull()));
        }
        putList(out, "dayparts", w.dayparts(), d -> map("key", d.key(), "icon", d.icon(),
                "lines", d.lines(), "bullish", d.bullish(), "bearish", d.bearish(),
                "red", d.red(), "note", d.note()));
        putList(out, "econOutcomes", w.econOutcomes(), o -> map("title", o.title(),
                "country", o.country(), "time", o.time(), "impact", o.impact(),
                "actual", o.actual(), "forecast", o.forecast(), "previous", o.previous(),
                "unit", o.unit()));
        putList(out, "worldEvents", w.worldEvents(), e -> map("category", e.category(),
                "text", e.text(), "source", e.source()));
        putList(out, "eventReviews", w.eventReviews(), r -> map("event", r.event(),
                "headlines", r.headlines().isEmpty() ? null : r.headlines()));
        putList(out, "cbDates", w.cbDates(), c -> map("bank", c.bank(), "title", c.title(),
                "dateIso", c.dateIso()));
        putList(out, "topNews", w.topNews(), n -> map("topline", n.topline(),
                "title", n.title(), "firstSentence", n.firstSentence(),
                "time", n.time(), "ressort", n.ressort(),
                "breaking", n.breaking() ? Boolean.TRUE : null));
        putList(out, "pressReview", w.pressReview(), p -> map("title", p.title(),
                "teaser", p.teaser(), "source", p.source(), "category", p.category(),
                "time", p.time()));
        putList(out, "worldWeather", w.worldWeather(), p -> map("place", p.place(),
                "role", p.role(), "tempC", p.tempC(), "word", p.word(),
                "windKmh", p.windKmh(), "tomorrowMaxC", p.tomorrowMaxC(),
                "tomorrowMinC", p.tomorrowMinC(), "tomorrowWord", p.tomorrowWord()));
        putList(out, "hazards", w.hazards(), h -> map("kind", h.kind(),
                "text", h.text(), "severity", h.severity()));
        putList(out, "tickerNews", w.tickerNews(), n -> map("ticker", n.ticker(),
                "title", n.title(), "publisher", n.publisher(), "time", n.time()));
        return out;
    }

    private static Map<String, Object> indexJson(WeatherReportRecord.IndexStat s) {
        return map("name", s.name(), "symbol", s.symbol(), "last", s.last(),
                "changePercent", s.changePercent(), "volume", s.volume(),
                "currency", s.currency(), "spark", s.spark().isEmpty() ? null : s.spark());
    }

    private static Map<String, Object> macroJson(WeatherReportRecord.MacroStat m) {
        return map("title", m.title(), "source", m.source(), "time", m.time(),
                "impact", m.impact(), "forecast", m.forecast(), "previous", m.previous());
    }

    private static <T> void putList(Map<String, Object> out, String key, List<T> items,
            java.util.function.Function<T, Map<String, Object>> mapper) {
        if (items == null || items.isEmpty()) return;
        List<Map<String, Object>> mapped = new ArrayList<>(items.size());
        for (T item : items) mapped.add(mapper.apply(item));
        out.put(key, mapped);
    }

    /** Alternating key/value pairs → ordered map, null values skipped. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }
}
