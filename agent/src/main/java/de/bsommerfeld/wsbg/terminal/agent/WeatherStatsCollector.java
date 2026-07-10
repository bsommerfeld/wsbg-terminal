package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubject;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.NewsStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Freezes the Wetterbericht's market stats at generation time: the broad
 * indices' day (Yahoo v8/chart — the one keyless source for {@code ^…} points,
 * volume and intraday spark), the day's most-discussed wire instruments (their
 * quotes read straight off the archived headline snapshots — no re-fetch, so
 * the numbers are exactly what the wire showed), and the news the day's lines
 * actually cited. Everything is best-effort: a failed section degrades to an
 * empty list, never blocks the report text.
 */
@Singleton
class WeatherStatsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherStatsCollector.class);

    /** The broad-market day at a glance — the room's home index first, then the US pair, then fear. */
    private static final List<String[]> INDICES = List.of(
            new String[]{"^GDAXI", "DAX"},
            new String[]{"^GSPC", "S&P 500"},
            new String[]{"^NDX", "Nasdaq 100"},
            new String[]{"^VIX", "VIX"});

    private static final int MAX_TICKERS = 8;
    private static final int MAX_NEWS = 8;

    private final YahooFinanceClient yahoo;

    record Stats(List<IndexStat> indices, List<TickerStat> tickers, List<NewsStat> news) {
    }

    @Inject
    WeatherStatsCollector(YahooFinanceClient yahoo) {
        this.yahoo = yahoo;
    }

    Stats collect(List<HeadlineRecord> todaysHeadlines) {
        return new Stats(indices(), tickers(todaysHeadlines), news(todaysHeadlines));
    }

    private List<IndexStat> indices() {
        List<IndexStat> out = new ArrayList<>();
        for (String[] idx : INDICES) {
            try {
                Optional<MarketSnapshot> snap = yahoo.fetchChart(idx[0]);
                if (snap.isEmpty() || !snap.get().hasPrice()) continue;
                MarketSnapshot s = snap.get();
                out.add(new IndexStat(idx[1], idx[0], finiteOrNull(s.price()),
                        finiteOrNull(s.dayChangePercent()),
                        s.volume() < 0 ? null : s.volume(), s.spark()));
            } catch (Exception e) {
                LOG.warn("Wetterbericht index stat {} failed: {}", idx[0], e.getMessage());
            }
        }
        return out;
    }

    /**
     * The day's most-discussed instruments, quote frozen from the LATEST archived
     * headline snapshot of each ticker — the wire's own numbers, not a re-fetch.
     */
    private List<TickerStat> tickers(List<HeadlineRecord> headlines) {
        try {
            Map<String, List<HeadlineRecord>> byTicker = new LinkedHashMap<>();
            for (HeadlineRecord r : headlines) {
                if (r.tickerSymbol() == null || r.tickerSymbol().isBlank()) continue;
                byTicker.computeIfAbsent(r.tickerSymbol().toUpperCase(Locale.ROOT),
                        k -> new ArrayList<>()).add(r);
            }
            List<TickerStat> out = new ArrayList<>();
            for (Map.Entry<String, List<HeadlineRecord>> e : byTicker.entrySet()) {
                List<HeadlineRecord> records = e.getValue();
                int important = (int) records.stream()
                        .filter(r -> r.highlight() == HeadlineHighlight.IMPORTANT).count();
                MarketSnapshot snap = records.stream()
                        .sorted(Comparator.comparingLong(HeadlineRecord::createdAt).reversed())
                        .map(HeadlineRecord::snapshot)
                        .filter(s -> s != null && s.hasPrice())
                        .findFirst().orElse(null);
                out.add(new TickerStat(e.getKey(), displayName(records, e.getKey()),
                        records.size(), important,
                        snap == null ? null : finiteOrNull(snap.price()),
                        snap == null ? null : snap.currency(),
                        snap == null ? null : finiteOrNull(snap.dayChangePercent()),
                        snap == null || snap.volume() < 0 ? null : snap.volume()));
            }
            out.sort(Comparator.comparingInt(TickerStat::headlineCount).reversed()
                    .thenComparing(Comparator.comparingInt(TickerStat::importantCount).reversed()));
            return out.size() > MAX_TICKERS ? List.copyOf(out.subList(0, MAX_TICKERS)) : out;
        } catch (Exception e) {
            LOG.warn("Wetterbericht ticker stats failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String displayName(List<HeadlineRecord> records, String ticker) {
        for (HeadlineRecord r : records) {
            for (HeadlineSubject s : r.subjects()) {
                if (ticker.equalsIgnoreCase(s.ticker()) && s.name() != null && !s.name().isBlank()) {
                    return s.name();
                }
            }
        }
        return ticker;
    }

    /** The news items the day's lines actually leaned on, ranked by how often they were cited. */
    private List<NewsStat> news(List<HeadlineRecord> headlines) {
        try {
            Map<String, NewsStat> byTitle = new LinkedHashMap<>();
            for (HeadlineRecord r : headlines) {
                for (HeadlineNewsRef ref : r.newsRefs()) {
                    if (ref.title() == null || ref.title().isBlank()) continue;
                    String key = ref.title().toLowerCase(Locale.ROOT).strip();
                    NewsStat prior = byTitle.get(key);
                    byTitle.put(key, new NewsStat(ref.title(), ref.publisher(),
                            prior == null ? 1 : prior.citations() + 1));
                }
            }
            return byTitle.values().stream()
                    .sorted(Comparator.comparingInt(NewsStat::citations).reversed())
                    .limit(MAX_NEWS).toList();
        } catch (Exception e) {
            LOG.warn("Wetterbericht news stats failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static Double finiteOrNull(double v) {
        return Double.isFinite(v) ? v : null;
    }
}
