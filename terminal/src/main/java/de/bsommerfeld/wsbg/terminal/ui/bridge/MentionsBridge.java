package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.MentionCounter;
import de.bsommerfeld.wsbg.terminal.briefing.ApeWisdomClient;
import de.bsommerfeld.wsbg.terminal.db.MentionDay;
import de.bsommerfeld.wsbg.terminal.stocknear.StocknearClient;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The mention counter's window into the page — and the one place where the
 * house's own count and the neighbours' rankings meet without being mixed.
 *
 * <p><b>Ours is the measurement, theirs is the view over the fence.</b> Our
 * count is scanned out of every line r/wallstreetbetsGER writes, day-sharp,
 * addressable over any window. The two foreign counters are handed on
 * UNTOUCHED: their own window, their own method, their own ranking - we do not
 * re-cut them to our days, because a number we did not measure must not be
 * dressed up as one we did. That is also why they answer in a second message:
 * ours renders instantly from disk, theirs waits on the network.
 *
 * <p>Inbound {@code {type:"mentions", payload:{command, …}}}:
 * <ul>
 *   <li>{@code {command:"window", from:"2026-08-01", to:"2026-08-04"}} - our
 *       count folded over that window, plus the day-by-day timeline the
 *       slider draws its panorama from. Both dates optional: the default is
 *       today.</li>
 *   <li>{@code {command:"sources"}} - the neighbours' current rankings.</li>
 * </ul>
 */
@Singleton
public final class MentionsBridge {

    private static final Logger LOG = LoggerFactory.getLogger(MentionsBridge.class);

    /** Rows shipped per answer — the room's long tail is not a bulk export. */
    static final int MAX_ROWS = 250;
    /** Rows the grid card shows — the leader plus the four behind it. */
    static final int THUMB_ROWS = 5;
    /** How long a neighbour's ranking is reused before we ask again. */
    static final Duration SOURCE_TTL = Duration.ofMinutes(15);
    /** The neighbours' subreddit and window — theirs, not ours (see the class note). */
    static final String FOREIGN_SUBREDDIT = "wallstreetbets";
    static final String FOREIGN_PERIOD = "oneDay";

    private final MentionCounter counter;
    private final PushHub hub;
    private final ExecutorService fetcher = DaemonSchedulers.single("mentions-sources");

    /** Optional: without them the foreign section simply stays empty. */
    private volatile StocknearClient stocknear;
    private volatile ApeWisdomClient apeWisdom;

    private volatile Map<String, Object> cachedSources;
    private volatile Instant cachedAt = Instant.EPOCH;

    @Inject
    public MentionsBridge(MentionCounter counter, PushHub hub) {
        this.counter = counter;
        this.hub = hub;
        hub.on("mentions", this::onRequest);
    }

    @Inject(optional = true)
    void setStocknearClient(StocknearClient client) {
        this.stocknear = client;
    }

    @Inject(optional = true)
    void setApeWisdomClient(ApeWisdomClient client) {
        this.apeWisdom = client;
    }

    private void onRequest(Map<String, Object> payload) {
        String command = Payloads.str(payload.get("command"));
        if ("sources".equals(command)) {
            fetcher.execute(this::pushSources);
            return;
        }
        hub.broadcastSafe("mentions-data", () -> window(payload));
    }

    // -- our own count --

    /** Our count over the requested window plus the slider's panorama. Package-private for tests. */
    Map<String, Object> window(Map<String, Object> payload) {
        LocalDate today = LocalDate.now(counter.zone());
        LocalDate to = date(payload.get("to"), today);
        LocalDate from = date(payload.get("from"), to);
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }

        List<MentionCounter.MentionRow> counted = counter.rows(from, to);
        List<Map<String, Object>> rows = rows(counted, MAX_ROWS);
        int total = totalOf(counted);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("today", today.toString());
        // The grid card always reads TODAY, whatever span the open widget is
        // showing — a card that silently followed the slider would label a
        // month's leader as today's.
        out.put("todayTop", rows(counter.rows(today, today), THUMB_ROWS));
        out.put("todayTotal", totalOf(counter.rows(today, today)));
        out.put("earliest", counter.earliestDay().map(LocalDate::toString).orElse(null));
        out.put("total", total);
        out.put("rows", rows);
        out.put("timeline", timeline());
        Object requestId = payload.get("requestId");
        if (requestId != null) out.put("requestId", requestId);
        return out;
    }

    /** Counted rows in wire shape, capped — an unresolved row simply carries no symbol. */
    private static List<Map<String, Object>> rows(List<MentionCounter.MentionRow> counted, int cap) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MentionCounter.MentionRow r : counted) {
            if (out.size() >= cap) break;
            Map<String, Object> m = new LinkedHashMap<>();
            if (r.symbol() != null) m.put("symbol", r.symbol());
            m.put("label", r.label());
            m.put("mentions", r.mentions());
            m.put("resolved", r.resolved());
            out.add(m);
        }
        return out;
    }

    /** Every mention in the window, including the rows beyond the cap. */
    private static int totalOf(List<MentionCounter.MentionRow> counted) {
        int total = 0;
        for (MentionCounter.MentionRow r : counted) total += r.mentions();
        return total;
    }

    /**
     * Every day the archive holds, with its total - the waveform the slider
     * rides over. Always the WHOLE history, never just the selection: the
     * panorama has to show what lies outside the current view, otherwise
     * there is nothing to slide towards.
     */
    private List<Map<String, Object>> timeline() {
        LocalDate earliest = counter.earliestDay().orElse(null);
        if (earliest == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (MentionDay d : counter.days(earliest, LocalDate.now(counter.zone()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", d.day().toString());
            m.put("total", d.total());
            m.put("items", d.items());
            out.add(m);
        }
        return out;
    }

    private static LocalDate date(Object raw, LocalDate fallback) {
        String s = Payloads.str(raw);
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // -- the neighbours --

    private void pushSources() {
        Map<String, Object> cached = cachedSources;
        if (cached != null && Instant.now().isBefore(cachedAt.plus(SOURCE_TTL))) {
            hub.broadcast("mentions-sources", cached);
            return;
        }
        Map<String, Object> fresh = fetchSources();
        cachedSources = fresh;
        cachedAt = Instant.now();
        hub.broadcast("mentions-sources", fresh);
    }

    /**
     * Both neighbours, each carrying its OWN scope and window rather than a
     * common one - Stocknear counts r/wallstreetbets over a two-day bucket
     * (their cron's {@code oneDay} really is two), ApeWisdom ranks across all
     * retail boards over 24 h. Labelling that honestly is the whole point;
     * a failing leg costs only its own list.
     */
    private Map<String, Object> fetchSources() {
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(source("stocknear", "wallstreetbets", "twoDays", stocknearRows()));
        sources.add(source("apewisdom", "allBoards", "day", apeWisdomRows()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sources", sources);
        out.put("fetchedAt", Instant.now().getEpochSecond());
        return out;
    }

    private static Map<String, Object> source(String id, String scope, String window,
            List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("scope", scope);
        m.put("window", window);
        m.put("rows", rows);
        return m;
    }

    private List<Map<String, Object>> stocknearRows() {
        StocknearClient client = stocknear;
        if (client == null) return List.of();
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (StocknearClient.MentionCount c : client.mentions(FOREIGN_SUBREDDIT, FOREIGN_PERIOD)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", c.symbol());
                if (c.name() != null) m.put("name", c.name());
                m.put("mentions", c.mentions());
                if (c.sentiment() != null) m.put("sentiment", c.sentiment());
                rows.add(m);
                if (rows.size() >= MAX_ROWS) break;
            }
            return rows;
        } catch (Exception e) {
            LOG.debug("[MENTIONS] Stocknear leg failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> apeWisdomRows() {
        ApeWisdomClient client = apeWisdom;
        if (client == null) return List.of();
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ApeWisdomClient.SocialTicker s : client.topTickers()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", s.ticker());
                if (s.name() != null) m.put("name", s.name());
                m.put("mentions", s.mentions());
                m.put("rank", s.rank());
                m.put("rankClimb", s.rankClimb());
                rows.add(m);
                if (rows.size() >= MAX_ROWS) break;
            }
            return rows;
        } catch (Exception e) {
            LOG.debug("[MENTIONS] ApeWisdom leg failed: {}", e.getMessage());
            return List.of();
        }
    }
}
