package de.bsommerfeld.wsbg.terminal.marketbeat;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.UsShortStats;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource;
import de.bsommerfeld.wsbg.terminal.core.price.PressTimeline;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MarketBeat client — the analyst-action HISTORY leg (who upgraded/downgraded,
 * when, from what to what) plus the US short-interest facts including percent
 * of float. Keyless, no bot wall; robots.txt explicitly allows AI use and the
 * /stocks/ and /ratings/ paths (probed 2026-07-14).
 *
 * <p>Addressing is {@code /stocks/<EXCHANGE>/<TICKER>/<tab>/} where a WRONG
 * exchange 301-redirects to the right one (probed: {@code /stocks/NYSE/OTLK/}
 * → 301 → {@code /stocks/NASDAQ/OTLK/}), so the client guesses NASDAQ for a
 * bare US shape and lets the redirect route it. A venue-suffixed symbol maps
 * deterministically ({@code RHM.DE} → ETR, {@code RR.L} → LON); an unknown
 * suffix shape resolves to empty without network. An unknown ticker answers a
 * 200-shaped exchange LISTING page (never a 404), so misses are detected by
 * CONTENT: no history table, no consensus block, no short-interest facts.
 *
 * <p>Pages are server-rendered HTML; the parse anchors are the
 * {@code data-clean="Old|New"} cell attributes (both halves machine-readable
 * even where the visible cell mixes in All-Access upsell markup) plus the
 * 14-digit {@code data-sort-value} timestamp on the history table's date cell.
 * Targets arrive in the LISTING currency ("$315.00", "GBX 1,500"); a zero
 * target half is the provider's "no prior target" marker and maps to NaN.
 *
 * <p>Beyond the ratings legs the client also reads the per-ticker news tab
 * ({@code /news/}) into a dated {@link PressTimeline} — see
 * {@link #pressTimelineFor}.
 *
 * <p>Per-symbol cache 1 h, daily ratings table 30 min; a definite miss is
 * cached, a network failure never (an outage heals itself).
 */
@Singleton
public class MarketBeatClient implements AnalystActionsSource {

    private static final Logger LOG = LoggerFactory.getLogger(MarketBeatClient.class);

    private static final String BASE = "https://www.marketbeat.com";
    private static final long SYMBOL_TTL_MS = 60 * 60 * 1000L;
    private static final long DAILY_TTL_MS = 30 * 60 * 1000L;
    /** Newest actions kept per symbol — the history table can run to hundreds of rows. */
    private static final int MAX_ACTIONS = 25;
    /** Newest timeline entries kept per symbol — the news page serves ~50. */
    private static final int MAX_TIMELINE = 60;

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);

    private record CachedActions(Optional<AnalystActions> value, long atMs) {}

    private record CachedDaily(List<Action> value, long atMs) {}

    private record CachedTimeline(Optional<PressTimeline> value, long atMs) {}

    /** Symbol (UPPER) → actions or a cached definite miss. */
    private final Map<String, CachedActions> cache = new ConcurrentHashMap<>();
    /** Ratings path → the day's table. */
    private final Map<String, CachedDaily> dailyCache = new ConcurrentHashMap<>();
    /** Symbol (UPPER) → press timeline or a cached definite miss. */
    private final Map<String, CachedTimeline> timelineCache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public MarketBeatClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public MarketBeatClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Optional<AnalystActions> actionsFor(String symbol) {
        String route = routePath(symbol);
        if (route == null) return Optional.empty();
        String key = symbol.trim().toUpperCase(Locale.ROOT);
        CachedActions cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.atMs() < SYMBOL_TTL_MS) {
            return cached.value();
        }
        try {
            Fetched forecast = get(BASE + "/stocks/" + route + "/forecast/");
            Fetched shorts = get(BASE + "/stocks/" + route + "/short-interest/");
            if (forecast.transient_() && shorts.transient_()) return Optional.empty();

            List<Action> actions = forecast.ok() ? parseForecastActions(forecast.body()) : List.of();
            Consensus consensus = forecast.ok() ? parseConsensus(forecast.body()) : Consensus.EMPTY;
            UsShortStats shortStats = shorts.ok() ? parseShortInterest(shorts.body()) : null;

            if (actions.isEmpty() && consensus.isEmpty() && shortStats == null) {
                if (forecast.transient_() || shorts.transient_()) {
                    // One leg failed on the network — don't turn that into a cached miss.
                    return Optional.empty();
                }
                cache.put(key, new CachedActions(Optional.empty(), System.currentTimeMillis()));
                LOG.info("[marketbeat] {} → no coverage (definite miss)", key);
                return Optional.empty();
            }
            AnalystActions result = new AnalystActions(
                    consensus.rating(), consensus.target(), consensus.currency(),
                    actions, shortStats, System.currentTimeMillis() / 1000);
            cache.put(key, new CachedActions(Optional.of(result), System.currentTimeMillis()));
            LOG.info("[marketbeat] {} → {} actions, consensus {} {}, shorts {}",
                    key, actions.size(), consensus.rating(),
                    Double.isFinite(consensus.target()) ? consensus.target() : "n/a",
                    shortStats != null ? "yes" : "no");
            return Optional.of(result);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[marketbeat] actions for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The day's ratings table ({@code /ratings/}) — every action MarketBeat
     * logged today, each row carrying its ticker + company name. {@code country}
     * scopes the table: null/blank/"us" is the US default, other tokens map to
     * the country-scoped page ({@code "uk"} → {@code /ratings/uk/},
     * {@code "canada"} → {@code /ratings/canada/}; both live-probed 2026-07-14).
     * Rows are stamped with TODAY's date (the page is strictly the current day).
     * Empty on outage; a garbage country token is empty without network.
     */
    public List<Action> todaysActions(String country) {
        String path = ratingsPath(country);
        if (path == null) return List.of();
        CachedDaily cached = dailyCache.get(path);
        if (cached != null && System.currentTimeMillis() - cached.atMs() < DAILY_TTL_MS) {
            return cached.value();
        }
        try {
            Fetched page = get(BASE + path);
            if (!page.ok()) return List.of();
            List<Action> actions = parseDailyTable(page.body(), LocalDate.now().toString());
            dailyCache.put(path, new CachedDaily(actions, System.currentTimeMillis()));
            LOG.info("[marketbeat] {} → {} daily actions", path, actions.size());
            return actions;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[marketbeat] daily ratings {} failed: {}", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * The dated press timeline off the per-ticker news tab ({@code /news/}).
     * The default "Most Recent" view serves ~50 dated headlines spanning
     * MONTHS for a normal name (probed 2026-07-14: SAP back ~2.5 months; a
     * firehose name like AAPL covers only days — the page's month filter is
     * an ASP.NET postback, deliberately not chased). Entries newest-first as
     * delivered; a covered page with no rows is a definite, cacheable miss.
     */
    @Override
    public Optional<PressTimeline> pressTimelineFor(String symbol) {
        String route = routePath(symbol);
        if (route == null) return Optional.empty();
        String key = symbol.trim().toUpperCase(Locale.ROOT);
        CachedTimeline cached = timelineCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.atMs() < SYMBOL_TTL_MS) {
            return cached.value();
        }
        try {
            Fetched page = get(BASE + "/stocks/" + route + "/news/");
            if (!page.ok()) {
                if (page.transient_()) return Optional.empty();
                timelineCache.put(key, new CachedTimeline(Optional.empty(), System.currentTimeMillis()));
                return Optional.empty();
            }
            List<PressTimeline.Entry> entries =
                    parsePressTimeline(page.body(), LocalDate.now());
            Optional<PressTimeline> result = entries.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new PressTimeline(key, entries));
            timelineCache.put(key, new CachedTimeline(result, System.currentTimeMillis()));
            LOG.info("[marketbeat] {} → {} timeline headline(s)", key, entries.size());
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[marketbeat] press timeline for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // ---- routing ----

    private static final Pattern US_SHAPE = Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");
    private static final Pattern DE_SHAPE = Pattern.compile("([A-Z0-9]{1,6})\\.DE");
    private static final Pattern LON_SHAPE = Pattern.compile("([A-Z0-9]{1,6})\\.L");
    private static final Pattern COUNTRY_SHAPE = Pattern.compile("[a-z][a-z-]{0,19}");

    /**
     * Maps a ticker to the {@code <EXCHANGE>/<TICKER>} path element, or null
     * (no network for un-routable shapes). Bare US shapes guess NASDAQ — a
     * wrong guess 301s to the right exchange; {@code .DE} → ETR (XETRA),
     * {@code .L} → LON. Everything else (index {@code ^…}, crypto {@code -USD},
     * FX {@code =X}, unknown venue suffixes like {@code .KS}) is out of scope.
     */
    static String routePath(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        Matcher de = DE_SHAPE.matcher(s);
        if (de.matches()) return "ETR/" + de.group(1);
        Matcher lon = LON_SHAPE.matcher(s);
        if (lon.matches()) return "LON/" + lon.group(1);
        if (US_SHAPE.matcher(s).matches()) return "NASDAQ/" + s;
        return null;
    }

    /** Maps a country token to the ratings page path, or null for garbage. */
    static String ratingsPath(String country) {
        if (country == null || country.isBlank()) return "/ratings/";
        String c = country.trim().toLowerCase(Locale.ROOT);
        if (c.equals("us") || c.equals("usa") || c.equals("united-states")) return "/ratings/";
        if (!COUNTRY_SHAPE.matcher(c).matches()) return null;
        return "/ratings/" + c + "/";
    }

    // ---- transport ----

    /**
     * One fetch outcome. {@code ok} = usable 200 body; {@code transient_} =
     * network-shaped failure that must never be cached as a miss (status 0,
     * 403/429/5xx); a 404 is neither (a definite, cacheable miss).
     */
    private record Fetched(boolean ok, boolean transient_, String body) {}

    private Fetched get(String url) throws Exception {
        WebResponse resp = fetcher.fetch(url,
                Map.of("User-Agent", userAgent,
                        "Accept", "text/html,application/xhtml+xml"),
                requestTimeout);
        // Defensive: the direct transport follows redirects itself; a browser-leg
        // or stub response may still surface the 301 — follow the Location once.
        if ((resp.status() == 301 || resp.status() == 302)) {
            Optional<String> location = resp.header("Location");
            if (location.isPresent()) {
                String next = location.get().startsWith("http") ? location.get() : BASE + location.get();
                resp = fetcher.fetch(next,
                        Map.of("User-Agent", userAgent,
                                "Accept", "text/html,application/xhtml+xml"),
                        requestTimeout);
            }
        }
        if (resp.status() == 200) return new Fetched(true, false, resp.body());
        boolean definite = resp.isDefinitive();
        LOG.warn("[marketbeat] HTTP {} for {}", resp.status(), url);
        return new Fetched(false, !definite, "");
    }

    // ---- forecast page parsing ----

    record Consensus(String rating, double target, String currency) {
        static final Consensus EMPTY = new Consensus(null, Double.NaN, null);

        boolean isEmpty() {
            return rating == null && !Double.isFinite(target);
        }
    }

    private static final Pattern CONSENSUS_TARGET = Pattern.compile(
            "<strong>Consensus Price Target</strong></td><td>([^<]*)</td>");
    private static final Pattern CONSENSUS_RATING = Pattern.compile(
            "<strong>Consensus Rating</strong></td><td>(.*?)</td>", Pattern.DOTALL);

    /**
     * The consensus header off the forecast page's comparison table: the first
     * value column after the {@code <strong>} row label is the subject's own
     * figure (the following columns are industry/market comparisons).
     */
    static Consensus parseConsensus(String html) {
        String rating = null;
        Matcher r = CONSENSUS_RATING.matcher(html);
        if (r.find()) {
            String text = clean(stripTags(r.group(1)));
            if (!text.isEmpty() && !"N/A".equalsIgnoreCase(text)) rating = text;
        }
        double target = Double.NaN;
        String currency = null;
        Matcher t = CONSENSUS_TARGET.matcher(html);
        if (t.find()) {
            Money m = parseMoney(clean(t.group(1)));
            target = m.value();
            currency = m.currency();
        }
        return new Consensus(rating, target, currency);
    }

    /**
     * The rating-history table ({@code id="history-table"}): one row per action,
     * newest first. Row anchor is the leading date cell's 14-digit
     * {@code data-sort-value} timestamp — rows without it (upsell/ad injections)
     * are skipped, never fatal. Columns (pinned live 2026-07-14): date |
     * brokerage ({@code data-clean="Name|stars"}) | analyst
     * ({@code data-clean="Name|score-or-upsell"}) | action (plain text) |
     * rating ({@code data-clean="Old|New"}) | target ({@code data-clean="Old|New"}).
     */
    static List<Action> parseForecastActions(String html) {
        String table = tableHtml(html, "id=\"history-table\"");
        if (table == null) return List.of();
        List<Action> actions = new ArrayList<>();
        for (String row : rows(table)) {
            if (actions.size() >= MAX_ACTIONS) break;
            List<Cell> cells = cells(row);
            if (cells.size() < 6) continue;
            String dateIso = dateFromSortValue(cells.get(0).attrs());
            if (dateIso == null) continue; // not an action row
            String brokerage = cleanHalf(cells.get(1).cleanLeft());
            if (brokerage == null) continue;
            String analyst = cleanHalf(cells.get(2).cleanLeft());
            String actionType = cells.get(3).text();
            String ratingOld = cleanHalf(cells.get(4).cleanLeft());
            String ratingNew = cleanHalf(cells.get(4).cleanRight());
            Money targetOld = parseMoney(cells.get(5).cleanLeft());
            Money targetNew = parseMoney(cells.get(5).cleanRight());
            actions.add(new Action(null, null, dateIso, brokerage, analyst,
                    actionType.isEmpty() ? null : actionType,
                    ratingOld, ratingNew,
                    targetOld.value(), targetNew.value(),
                    targetNew.currency() != null ? targetNew.currency() : targetOld.currency()));
        }
        return actions;
    }

    // ---- daily ratings table parsing ----

    /**
     * The day's ratings table ({@code class="scroll-table sort-table"} — the
     * page's one table). Columns (pinned live 2026-07-14): company
     * ({@code data-clean="TICKER|Name"}) | action (plain text) | brokerage |
     * analyst | current price (skipped) | target ({@code Old|New}) | rating
     * ({@code Old|New}) | details. Rows without the ticker anchor are skipped.
     */
    static List<Action> parseDailyTable(String html, String dateIso) {
        String table = tableHtml(html, "class=\"scroll-table sort-table\"");
        if (table == null) return List.of();
        List<Action> actions = new ArrayList<>();
        for (String row : rows(table)) {
            List<Cell> cells = cells(row);
            if (cells.size() < 7) continue;
            String symbol = cleanHalf(cells.get(0).cleanLeft());
            String companyName = cleanHalf(cells.get(0).cleanRight());
            if (symbol == null || !symbol.matches("[A-Z0-9.]{1,6}")) continue; // not a company row
            String actionType = cells.get(1).text();
            String brokerage = cleanHalf(cells.get(2).cleanLeft());
            if (brokerage == null) continue;
            String analyst = cleanHalf(cells.get(3).cleanLeft());
            Money targetOld = parseMoney(cells.get(5).cleanLeft());
            Money targetNew = parseMoney(cells.get(5).cleanRight());
            String ratingOld = cleanHalf(cells.get(6).cleanLeft());
            String ratingNew = cleanHalf(cells.get(6).cleanRight());
            actions.add(new Action(symbol, companyName, dateIso, brokerage, analyst,
                    actionType.isEmpty() ? null : actionType,
                    ratingOld, ratingNew,
                    targetOld.value(), targetNew.value(),
                    targetNew.currency() != null ? targetNew.currency() : targetOld.currency()));
        }
        return actions;
    }

    // ---- news page parsing ----

    private static final String NEWS_ROW = "<div class=\"headline-row";
    private static final Pattern NEWS_TITLE = Pattern.compile(
            "<a class=\"c-black stretched-link[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern NEWS_BYLINE = Pattern.compile(
            "<div class=\"byline[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern NEWS_TIME_ATTR = Pattern.compile(
            "datetime=\"(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern NEWS_MONTH_DAY_AT = Pattern.compile(
            "([A-Za-z]+)\\.?\\s+(\\d{1,2})\\s+at\\b");

    /**
     * The news tab's {@code headline-row} blocks. Anchors (pinned live
     * 2026-07-14): title = the {@code c-black stretched-link} anchor's text;
     * date = the byline's {@code <time datetime="ISO">} where fresh, else the
     * byline text in one of the three text forms — "July 10, 2026" (older,
     * with year), "July 13 at 8:14 PM" (recent, year inferred), "2 hours ago"
     * (today); publisher = the byline's host token after the {@code |}
     * separator. Syndication double-posts (same date + title under a
     * {@code ?utm_source} link variant) are deduped, first wins.
     */
    static List<PressTimeline.Entry> parsePressTimeline(String html, LocalDate today) {
        List<PressTimeline.Entry> entries = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int i = html.indexOf(NEWS_ROW);
        while (i >= 0 && entries.size() < MAX_TIMELINE) {
            int next = html.indexOf(NEWS_ROW, i + NEWS_ROW.length());
            String row = html.substring(i, next < 0 ? html.length() : next);
            i = next;
            Matcher t = NEWS_TITLE.matcher(row);
            if (!t.find()) continue;
            String title = clean(stripTags(t.group(1)));
            if (title.isEmpty()) continue;
            Matcher b = NEWS_BYLINE.matcher(row);
            if (!b.find()) continue;
            String dateIso = timelineDate(b.group(1), today);
            if (dateIso == null) continue; // undated injection, not a headline row
            if (seen.add(dateIso + '|' + title)) {
                entries.add(new PressTimeline.Entry(dateIso, title, timelinePublisher(b.group(1))));
            }
        }
        return entries;
    }

    /** The byline's date in ISO, or null when no form matches. */
    static String timelineDate(String bylineHtml, LocalDate today) {
        Matcher attr = NEWS_TIME_ATTR.matcher(bylineHtml);
        if (attr.find()) return attr.group(1);
        String datePart = clean(stripTags(bylineHtml)).split("\\|", 2)[0];
        String longDate = parseLongDate(datePart);
        if (longDate != null) return longDate;
        Matcher m = NEWS_MONTH_DAY_AT.matcher(datePart);
        if (m.find()) {
            String month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
            if (month != null) {
                try {
                    LocalDate d = LocalDate.of(today.getYear(),
                            Integer.parseInt(month), Integer.parseInt(m.group(2)));
                    // Rows are recent — a "December 31" seen in January is last year's.
                    return (d.isAfter(today) ? d.minusYears(1) : d).toString();
                } catch (java.time.DateTimeException ignored) {
                    return null;
                }
            }
        }
        // "40 minutes ago" without the datetime attr — today's row.
        if (datePart.contains(" ago")) return today.toString();
        return null;
    }

    /** The byline's host token after the {@code |} separator, or null. */
    private static String timelinePublisher(String bylineHtml) {
        String text = clean(stripTags(bylineHtml));
        int bar = text.indexOf('|');
        if (bar < 0) return null;
        String pub = text.substring(bar + 1).trim();
        return pub.isEmpty() ? null : pub;
    }

    // ---- short-interest page parsing ----

    /**
     * The short-interest facts arrive as {@code <dt>Label</dt><dd>value</dd>}
     * pairs. Requires the "Current Short Interest" label as presence anchor;
     * a page without it (non-US listing, redirected miss) is null.
     */
    static UsShortStats parseShortInterest(String html) {
        Map<String, String> facts = dlFacts(html);
        if (!facts.containsKey("Current Short Interest")) return null;
        long current = parseShares(facts.get("Current Short Interest"));
        long prior = parseShares(facts.get("Previous Short Interest"));
        double dollarVolume = parseDollarAmount(facts.get("Dollar Volume Sold Short"));
        double daysToCover = leadingDouble(facts.get("Short Interest Ratio"));
        double pctFloat = leadingDouble(facts.get("Short Percent of Float"));
        String settlement = parseLongDate(facts.get("Last Record Date"));
        return new UsShortStats(current, prior, dollarVolume, daysToCover, pctFloat, settlement);
    }

    private static final Pattern DT_DD = Pattern.compile(
            "<dt[^>]*>([^<]+)</dt>\\s*<dd[^>]*>(.*?)</dd>", Pattern.DOTALL);

    private static Map<String, String> dlFacts(String html) {
        Map<String, String> facts = new java.util.HashMap<>();
        Matcher m = DT_DD.matcher(html);
        while (m.find()) {
            facts.putIfAbsent(clean(m.group(1)), clean(stripTags(m.group(2))));
        }
        return facts;
    }

    // ---- HTML micro-helpers (no HTML lib in the codebase — string scanning) ----

    /** The full {@code <table>…</table>} whose opening tag carries {@code marker}, or null. */
    private static String tableHtml(String html, String marker) {
        int i = html.indexOf(marker);
        if (i < 0) return null;
        int start = html.lastIndexOf("<table", i);
        int end = html.indexOf("</table>", i);
        if (start < 0 || end < 0) return null;
        return html.substring(start, end + 8);
    }

    /** The {@code <tr>…} chunks of a table's tbody (header rows excluded). */
    private static List<String> rows(String table) {
        int b = table.indexOf("<tbody");
        String body = b >= 0 ? table.substring(b) : table;
        List<String> rows = new ArrayList<>();
        int i = body.indexOf("<tr");
        while (i >= 0) {
            int end = body.indexOf("</tr>", i);
            if (end < 0) end = body.length();
            rows.add(body.substring(i, Math.min(end + 5, body.length())));
            i = body.indexOf("<tr", end);
        }
        return rows;
    }

    private record Cell(String attrs, String text) {

        /** Left half of the cell's {@code data-clean="Old|New"} attribute, or null. */
        String cleanLeft() {
            return cleanHalfAt(0);
        }

        /** Right half of the {@code data-clean} attribute, or null. */
        String cleanRight() {
            return cleanHalfAt(1);
        }

        private String cleanHalfAt(int index) {
            Matcher m = DATA_CLEAN.matcher(attrs);
            if (!m.find()) return null;
            String[] halves = m.group(1).split("\\|", -1);
            return index < halves.length ? decodeEntities(halves[index]).trim() : null;
        }
    }

    private static final Pattern DATA_CLEAN = Pattern.compile("data-clean=\"([^\"]*)\"");
    private static final Pattern TD_OPEN = Pattern.compile("<td\\b([^>]*)>");

    /** The row's cells: attribute string + tag-stripped text. tds never nest. */
    private static List<Cell> cells(String rowHtml) {
        List<Cell> cells = new ArrayList<>();
        Matcher m = TD_OPEN.matcher(rowHtml);
        while (m.find()) {
            int contentStart = m.end();
            int end = rowHtml.indexOf("</td>", contentStart);
            if (end < 0) end = rowHtml.length();
            cells.add(new Cell(m.group(1), clean(stripTags(rowHtml.substring(contentStart, end)))));
        }
        return cells;
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]*>", " ");
    }

    /** Entity-decode + whitespace-collapse + trim. */
    private static String clean(String s) {
        return decodeEntities(s).replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 10) {
                out.append(c);
                i++;
                continue;
            }
            String entity = s.substring(i + 1, semi);
            String decoded = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> numericEntity(entity);
            };
            if (decoded != null) {
                out.append(decoded);
                i = semi + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String numericEntity(String entity) {
        try {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return Character.toString(Integer.parseInt(entity.substring(2), 16));
            }
            if (entity.startsWith("#")) {
                return Character.toString(Integer.parseInt(entity.substring(1)));
            }
        } catch (RuntimeException ignored) {
            // fall through — not a decodable entity
        }
        return null;
    }

    /** Trims a data-clean half to a real value: blank / "N/A" / upsell copy → null. */
    private static String cleanHalf(String half) {
        if (half == null) return null;
        String s = half.trim();
        if (s.isEmpty() || "N/A".equalsIgnoreCase(s) || s.contains("All Access")) return null;
        return s;
    }

    // ---- US-format value parsing ----

    record Money(double value, String currency) {
        static final Money NONE = new Money(Double.NaN, null);
    }

    private static final Pattern MONEY = Pattern.compile(
            "(C\\$|A\\$|HK\\$|\\$|€|£|GBX|GBp|CHF|SEK|NOK|DKK|JPY|₹)?\\s*([\\d,]+(?:\\.\\d+)?)");

    /**
     * One price-target half: "$315.00" → 315 USD, "GBX 1,500" → 1500 GBX.
     * A zero value is the provider's "no prior target" marker → NaN. Blank,
     * "N/A" and bare "0" (rating-only rows) → NaN with null currency.
     */
    static Money parseMoney(String raw) {
        if (raw == null) return Money.NONE;
        String s = raw.trim();
        if (s.isEmpty() || "N/A".equalsIgnoreCase(s)) return Money.NONE;
        Matcher m = MONEY.matcher(s);
        if (!m.find()) return Money.NONE;
        double value;
        try {
            value = Double.parseDouble(m.group(2).replace(",", ""));
        } catch (NumberFormatException e) {
            return Money.NONE;
        }
        if (value == 0) return Money.NONE;
        return new Money(value, currencyOf(m.group(1)));
    }

    private static String currencyOf(String token) {
        if (token == null) return null;
        return switch (token) {
            case "$" -> "USD";
            case "C$" -> "CAD";
            case "A$" -> "AUD";
            case "HK$" -> "HKD";
            case "€" -> "EUR";
            case "£" -> "GBP";
            case "GBX", "GBp" -> "GBX";
            default -> token;
        };
    }

    /** "140,526,320 shares" → 140526320; null/unparseable → -1. */
    static long parseShares(String raw) {
        if (raw == null) return -1;
        Matcher m = Pattern.compile("([\\d,]+)").matcher(raw);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "$40.66 billion" → 4.066e10; also handles million/trillion and plain figures. */
    static double parseDollarAmount(String raw) {
        if (raw == null) return Double.NaN;
        Matcher m = Pattern.compile("\\$\\s*([\\d,]+(?:\\.\\d+)?)").matcher(raw);
        if (!m.find()) return Double.NaN;
        double value;
        try {
            value = Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("trillion")) return value * 1e12;
        if (lower.contains("billion")) return value * 1e9;
        if (lower.contains("million")) return value * 1e6;
        return value;
    }

    /** The leading decimal of "1.7 Days to Cover" / "0.96%"; NaN when absent. */
    static double leadingDouble(String raw) {
        if (raw == null) return Double.NaN;
        Matcher m = Pattern.compile("(-?[\\d,]+(?:\\.\\d+)?)").matcher(raw);
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static final Pattern SORT_DATE = Pattern.compile("data-sort-value=\"(\\d{8})\\d{6}\"");

    /** The date cell's {@code data-sort-value="yyyyMMddHHmmss"} → ISO date, or null. */
    static String dateFromSortValue(String attrs) {
        Matcher m = SORT_DATE.matcher(attrs);
        if (!m.find()) return null;
        String d = m.group(1);
        return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
    }

    private static final Map<String, String> MONTHS = Map.ofEntries(
            Map.entry("january", "01"), Map.entry("february", "02"), Map.entry("march", "03"),
            Map.entry("april", "04"), Map.entry("may", "05"), Map.entry("june", "06"),
            Map.entry("july", "07"), Map.entry("august", "08"), Map.entry("september", "09"),
            Map.entry("october", "10"), Map.entry("november", "11"), Map.entry("december", "12"));

    /** "June 30, 2026" → "2026-06-30"; also "7/13/2026" → "2026-07-13"; null unparseable. */
    static String parseLongDate(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("([A-Za-z]+)\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})").matcher(raw);
        if (m.find()) {
            String month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
            if (month != null) {
                return m.group(3) + "-" + month + "-" + String.format("%02d", Integer.parseInt(m.group(2)));
            }
        }
        Matcher slash = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(raw);
        if (slash.find()) {
            return slash.group(3) + "-"
                    + String.format("%02d", Integer.parseInt(slash.group(1))) + "-"
                    + String.format("%02d", Integer.parseInt(slash.group(2)));
        }
        return null;
    }
}
