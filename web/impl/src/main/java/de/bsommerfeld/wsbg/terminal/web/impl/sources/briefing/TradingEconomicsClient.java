package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TradingEconomics - the macro desk of the house, read off the server-rendered
 * pages (live-probed 2026-08-02).
 *
 * <p><b>The API is dead, do not try it.</b> {@code api.tradingeconomics.com}
 * with the once-famous {@code ?c=guest:guest} answers {@code 410 "the guest
 * account has been discontinued"}, keyless requests answer {@code 401} and the
 * websocket stream answers {@code 426} on the same dead key. Every tutorial
 * still describing that route is stale. What IS open is the plain HTML: the
 * robots.txt is 49 bytes with not a single {@code Disallow}, and the terms
 * grant a "limited, personal, nontransferable, revocable license" - which is
 * exactly what a local single-seat terminal is. No key, no cookie, no XHR
 * anywhere in the pages this client reads.
 *
 * <p>What the pages carry (all counts live-probed 2026-08-02):
 * <ul>
 *   <li>{@code /calendar} - 1.66 MB of SSR, 430 events across 26 countries in a
 *       rolling ten-day window. Every {@code <tr>} carries
 *       {@code data-url/-id/-country/-category/-event/-symbol}, the values sit
 *       in {@code <span id='actual'>} / {@code <span id='previous'>} and the
 *       importance rides as the {@code calendar-date-N} class on the time span.
 *       {@code ?importance=3} is honoured server-side and saves 76 % of the
 *       traffic (21 rows instead of 430).</li>
 *   <li>{@code /earnings} - 654 tickers in TE notation ({@code SATS:US},
 *       {@code 1COV:GR}, {@code 1332:JP}) with EPS and revenue against
 *       consensus and the year-ago print.</li>
 *   <li>{@code /currencies} (169 quote rows), {@code /commodities} (104),
 *       {@code /stocks}, {@code /bonds} - one shared quote-table shape.</li>
 *   <li>An indicator page such as {@code /united-states/inflation-cpi} - the
 *       release history WITH actuals, a components table, a related table and
 *       the upcoming dates, without a single XHR.</li>
 *   <li>{@code /ws/stream.ashx?start=0} - a real JSON array of 10 news items,
 *       {@code start=} pages further back. Live ticker, no archive.</li>
 * </ul>
 *
 * <p><b>Time series are not in the HTML</b> - the charts are PNGs. What is
 * usable is last/previous/forecast plus the per-release rows of an indicator
 * page; the covered span is disclosed by the JSON-LD
 * {@code Dataset.temporalCoverage} ({@code 1914-12-31/2026-06-30} for US CPI)
 * and comes along as metadata.
 *
 * <p>Deviations from the 2026-08-02 research note, re-probed while building:
 * the {@code /earnings} page has been rebuilt in Tailwind markup and no longer
 * carries {@code data-symbol} - the ticker sits in a {@code span.earnings-symbol}
 * instead; and {@code /calendar} renders a forward-only window (all 430 rows
 * dated today or later), so {@code actual} is empty until a print lands during
 * the day - the released numbers live on the indicator pages.
 *
 * <p>Per the owner mandate of 2026-08-02 every stream is wired even where no
 * caller exists yet; those methods are marked "currently unused, stands ready".
 */
@Singleton
public class TradingEconomicsClient {

    private static final Logger LOG = LoggerFactory.getLogger(TradingEconomicsClient.class);

    private static final String BASE = "https://tradingeconomics.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The calendar rolls every few minutes at most; the page is 1.66 MB. */
    private static final Duration CALENDAR_TTL = Duration.ofMinutes(15);
    /** Earnings dockets move once a day. */
    private static final Duration EARNINGS_TTL = Duration.ofHours(2);
    /** Quote tables are the only genuinely fast-moving pages here. */
    private static final Duration QUOTES_TTL = Duration.ofMinutes(5);
    /** An indicator page changes on release day only. */
    private static final Duration INDICATOR_TTL = Duration.ofHours(6);
    /** The stream ticks every couple of minutes. */
    private static final Duration STREAM_TTL = Duration.ofMinutes(5);

    // ---- records -----------------------------------------------------------

    /**
     * One scheduled macro print. {@code date} is ISO ({@code 2026-08-03}),
     * {@code time} the page's local clock string ("01:45 AM", empty for
     * all-day items), {@code importance} 1..3 with 0 for the unrated all-day
     * entries (OPEC meetings, holidays). {@code country}/{@code category}/
     * {@code event}/{@code symbol} are TE's own machine tokens off the row
     * attributes; {@code title} is the rendered headline. Values stay verbatim
     * strings - units vary wildly and an empty string is an honest "not yet".
     */
    public record CalendarEvent(String date, String time, String country, String category,
            String event, String title, String symbol, String url, int importance,
            String actual, String previous, String consensus, String forecast) {
    }

    /**
     * One earnings docket line. Values verbatim as printed ("96.52B",
     * "$638.29B"); an empty string means TE prints a dash there.
     * {@code impact} is 1..3 off the star colour, 0 when unrated. {@code date}
     * is empty for the handful of rows the page prints above its first date
     * header (today's already-reported names) - an honest blank, not a guess.
     */
    public record EarningsEntry(String date, String symbol, String company, String country,
            String epsActual, String epsConsensus, String epsPrevious,
            String revenueActual, String revenueConsensus, String revenuePrevious,
            String marketCap, String fiscalPeriod, String time, int impact) {
    }

    /**
     * One row of a quote table ({@code /currencies}, {@code /commodities},
     * {@code /stocks}, {@code /bonds}). {@code symbol} is TE notation
     * ({@code EURUSD:CUR}, {@code CL1:COM}), {@code unit} the sub-label where
     * the page prints one ("USD/Bbl"), empty otherwise. {@code asOf} is the
     * page's own stamp ("Aug/02").
     */
    public record Quote(String symbol, String name, String unit, double last,
            double dayChange, double percentChange, String asOf) {
    }

    /** One item off the news stream; {@code html} already carries linked symbol anchors. */
    public record StreamItem(long id, String title, String description, String url,
            String author, String country, String category, int importance,
            String date, String html) {
    }

    /** One historical or upcoming release of an indicator; empty strings while unreleased. */
    public record Release(String date, String time, String reference, String actual,
            String previous, String consensus, String teForecast) {
    }

    /** One line of an indicator page's Components or Related table. */
    public record IndicatorRow(String name, String url, String last, String previous,
            String unit, String reference) {
    }

    /**
     * A whole indicator page. {@code temporalCoverage} is the JSON-LD span the
     * dataset covers ({@code 1914-12-31/2026-06-30}) - metadata only, the
     * series itself is not in the HTML.
     */
    public record IndicatorPage(String country, String indicator, String temporalCoverage,
            List<Release> releases, List<IndicatorRow> components, List<IndicatorRow> related) {
    }

    // ---- patterns ----------------------------------------------------------

    private static final Pattern CAL_ROW = Pattern.compile("<tr\\s+data-url=", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAL_DATE = Pattern.compile("class=['\"]\\s*(\\d{4}-\\d{2}-\\d{2})\\s*['\"]");
    private static final Pattern CAL_TIME =
            Pattern.compile("<span class=\"event-\\d+\\s*(?:calendar-date-(\\d))?\\s*\">(.*?)</span>", Pattern.DOTALL);
    private static final Pattern CAL_TITLE =
            Pattern.compile("<a class=['\"]calendar-event['\"][^>]*>(.*?)</a>", Pattern.DOTALL);
    /** All-day entries (OPEC meetings, holidays) print their title in a bare span instead. */
    private static final Pattern CAL_TITLE_SPAN =
            Pattern.compile("<span>(.*?)</span>\\s*<span class=\"calendar-reference\"", Pattern.DOTALL);
    private static final Pattern CAL_ACTUAL = Pattern.compile("<span id=['\"]actual['\"][^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern CAL_PREVIOUS = Pattern.compile("<span id=['\"]previous['\"][^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern CAL_CONSENSUS = Pattern.compile("<a id=['\"]consensus['\"][^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern CAL_FORECAST = Pattern.compile("<a id=['\"]forecast['\"][^>]*>(.*?)</a>", Pattern.DOTALL);

    private static final Pattern EARN_ROW = Pattern.compile("<tr\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern EARN_DATE_HEADER = Pattern.compile("data-date-header=\"(\\d{4}-\\d{2}-\\d{2})\"");
    private static final Pattern EARN_SYMBOL = Pattern.compile("<span class=\"earnings-symbol\">([^<]+)</span>");
    private static final Pattern EARN_LINK =
            Pattern.compile("<a class=\"calendar-event-link[^\"]*\"[^>]*>([^<]*)</a>");
    private static final Pattern EARN_FLAG = Pattern.compile("<div title=\"([^\"]+)\" class=\"flag");
    private static final Pattern EARN_IMPACT = Pattern.compile("title=\"(High|Medium|Low) Market Impact\"");

    private static final Pattern QUOTE_ROW = Pattern.compile("<tr\\s+data-symbol=", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTE_NAME = Pattern.compile("<b>(.*?)</b>", Pattern.DOTALL);
    private static final Pattern QUOTE_UNIT = Pattern.compile("<div style=['\"]font-size: 10px;['\"]>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern QUOTE_LAST = Pattern.compile("<td id=\"p\"[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern QUOTE_NCH = Pattern.compile("<td id=\"nch\"[^>]*data-value=\"([^\"]*)\"");
    private static final Pattern QUOTE_PCH = Pattern.compile("<td id=\"pch\"[^>]*data-value=\"([^\"]*)\"");
    private static final Pattern QUOTE_DATE = Pattern.compile("<td id=\"date\"[^>]*>(.*?)</td>", Pattern.DOTALL);

    private static final Pattern REL_ROW = Pattern.compile("<tr data-id=\"\\d+\"[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern IND_ROW = Pattern.compile("<tr class='datatable-row[^']*'>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern TD = Pattern.compile("<td\\b[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern SPAN = Pattern.compile("<span[^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile("href=['\"]([^'\"]+)['\"]");
    private static final Pattern TEMPORAL = Pattern.compile("\"temporalCoverage\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);

    private record Cached(Instant at, Object value) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(25);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public TradingEconomicsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    // ---- public streams ----------------------------------------------------

    /** The whole rolling ten-day docket (430 events on a normal day). */
    public List<CalendarEvent> calendar() {
        return calendar(0);
    }

    /**
     * The docket filtered server-side by importance: 3 = the prints that move a
     * tape, 2 = medium, 0/1 = everything. TE honours {@code ?importance=} itself,
     * so a filtered call is also 76 % less traffic.
     */
    public List<CalendarEvent> calendar(int minImportance) {
        String url = minImportance >= 2
                ? BASE + "/calendar?importance=" + minImportance
                : BASE + "/calendar";
        return list(url, CALENDAR_TTL, TradingEconomicsClient::parseCalendar);
    }

    /** The earnings docket, 654 tickers worldwide in TE notation. */
    public List<EarningsEntry> earnings() {
        return list(BASE + "/earnings", EARNINGS_TTL, TradingEconomicsClient::parseEarnings);
    }

    /** FX board, 169 pairs. Currently unused, stands ready. */
    public List<Quote> currencies() {
        return quotes("/currencies");
    }

    /** Commodity board, 104 symbols across energy, metals, agri. Currently unused, stands ready. */
    public List<Quote> commodities() {
        return quotes("/commodities");
    }

    /** World index board. Currently unused, stands ready. */
    public List<Quote> stocks() {
        return quotes("/stocks");
    }

    /** Government bond yields. Currently unused, stands ready. */
    public List<Quote> bonds() {
        return quotes("/bonds");
    }

    /** Any quote table on the site by path ({@code "/commodities"}); the shape is shared. */
    public List<Quote> quotes(String path) {
        return list(BASE + normalise(path), QUOTES_TTL, TradingEconomicsClient::parseQuotes);
    }

    /**
     * One indicator page, e.g. {@code indicator("united-states", "inflation-cpi")}:
     * the release history with actuals, the components and related tables and
     * the covered span. Empty when the page is gone.
     */
    public Optional<IndicatorPage> indicator(String country, String indicator) {
        String slugCountry = slug(country);
        String slugIndicator = slug(indicator);
        String url = BASE + "/" + slugCountry + "/" + slugIndicator;
        List<IndicatorPage> hit = list(url, INDICATOR_TTL,
                body -> {
                    IndicatorPage page = parseIndicator(slugCountry, slugIndicator, body);
                    return page == null ? List.of() : List.of(page);
                });
        return hit.isEmpty() ? Optional.empty() : Optional.of(hit.get(0));
    }

    /** The newest ten stream items. */
    public List<StreamItem> stream() {
        return stream(0);
    }

    /**
     * The stream paged backwards - {@code start=10} is the next ten items.
     * Live ticker only, TE keeps no archive behind it.
     * Currently unused beyond {@link #stream()}, stands ready.
     */
    public List<StreamItem> stream(int start) {
        return list(BASE + "/ws/stream.ashx?start=" + Math.max(0, start),
                STREAM_TTL, TradingEconomicsClient::parseStream);
    }

    // ---- transport ---------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> List<T> list(String url, Duration ttl, Function<String, List<T>> parser) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(ttl))) {
            return (List<T>) hit.value();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "text/html,application/json,*/*"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                List<T> parsed = parser.apply(resp.body());
                if (!parsed.isEmpty()) {
                    cache.put(url, new Cached(Instant.now(), parsed));
                    return parsed;
                }
            } else {
                LOG.debug("[TradingEconomics] {} answered status {}", url,
                        resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[TradingEconomics] fetch {} failed: {}", url, e.getMessage());
        }
        return hit == null ? List.of() : (List<T>) hit.value();
    }

    // ---- parsers (package-visible for the fixture tests) -------------------

    /** {@code /calendar} SSR → events. Network-free, garbage-tolerant. */
    static List<CalendarEvent> parseCalendar(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<CalendarEvent> out = new ArrayList<>();
        for (String row : rows(html, CAL_ROW)) {
            String head = row.substring(0, Math.max(row.indexOf('>') + 1, 1));
            String url = attr(head, "data-url");
            if (url.isEmpty()) continue;
            int importance = 0;
            String time = "";
            Matcher t = CAL_TIME.matcher(row);
            if (t.find()) {
                importance = t.group(1) == null ? 0 : Integer.parseInt(t.group(1));
                time = strip(t.group(2));
            }
            String event = attr(head, "data-event");
            String title = strip(group(CAL_TITLE, row));
            if (title.isEmpty()) title = strip(group(CAL_TITLE_SPAN, row));
            out.add(new CalendarEvent(
                    group(CAL_DATE, row), time,
                    attr(head, "data-country"), attr(head, "data-category"),
                    event, title.isEmpty() ? event : title,
                    attr(head, "data-symbol"), url, importance,
                    strip(group(CAL_ACTUAL, row)), strip(group(CAL_PREVIOUS, row)),
                    strip(group(CAL_CONSENSUS, row)), strip(group(CAL_FORECAST, row))));
        }
        return List.copyOf(out);
    }

    /**
     * {@code /earnings} SSR → docket lines. The date rides on separator rows
     * ({@code data-date-header}) above the block they head, so the parser walks
     * the rows in order and carries the last date seen.
     */
    static List<EarningsEntry> parseEarnings(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<EarningsEntry> out = new ArrayList<>();
        String date = "";
        for (String row : rows(html, EARN_ROW)) {
            Matcher header = EARN_DATE_HEADER.matcher(row);
            if (header.find()) {
                date = header.group(1);
                continue;
            }
            Matcher sym = EARN_SYMBOL.matcher(row);
            if (!sym.find()) continue;
            List<String> cells = new ArrayList<>();
            Matcher td = TD.matcher(row);
            while (td.find()) cells.add(td.group(1));
            if (cells.size() < 8) continue;
            List<String> eps = pair(cells.get(1));
            List<String> revenue = pair(cells.get(3));
            Matcher impact = EARN_IMPACT.matcher(row);
            String symbol = sym.group(1).trim();
            out.add(new EarningsEntry(date, symbol,
                    company(cells.get(0), symbol), group(EARN_FLAG, row),
                    eps.get(0), eps.get(1), strip(cells.get(2)),
                    revenue.get(0), revenue.get(1), strip(cells.get(4)),
                    strip(cells.get(5)), strip(cells.get(6)), strip(cells.get(7)),
                    impact.find() ? impactOf(impact.group(1)) : 0));
        }
        return List.copyOf(out);
    }

    /** Any quote table ({@code /currencies}, {@code /commodities}, …) → rows. */
    static List<Quote> parseQuotes(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<Quote> out = new ArrayList<>();
        for (String row : rows(html, QUOTE_ROW)) {
            String head = row.substring(0, Math.max(row.indexOf('>') + 1, 1));
            String symbol = attr(head, "data-symbol");
            if (symbol.isEmpty()) continue;
            out.add(new Quote(symbol, strip(group(QUOTE_NAME, row)), strip(group(QUOTE_UNIT, row)),
                    number(strip(group(QUOTE_LAST, row))),
                    number(group(QUOTE_NCH, row)), number(group(QUOTE_PCH, row)),
                    strip(group(QUOTE_DATE, row))));
        }
        return List.copyOf(out);
    }

    /** {@code /ws/stream.ashx} → items. Real JSON, no key, no websocket. */
    static List<StreamItem> parseStream(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<StreamItem> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            for (JsonNode n : root) {
                String title = text(n, "title");
                if (title.isEmpty()) continue;
                out.add(new StreamItem(n.path("ID").asLong(0L), title, text(n, "description"),
                        text(n, "url"), text(n, "author"), text(n, "country"), text(n, "category"),
                        n.path("importance").asInt(0), text(n, "date"), text(n, "html")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return List.copyOf(out);
    }

    /** An indicator page → releases + components + related + the covered span. */
    static IndicatorPage parseIndicator(String country, String indicator, String html) {
        if (html == null || html.isBlank()) return null;
        List<Release> releases = new ArrayList<>();
        Matcher rel = REL_ROW.matcher(html);
        while (rel.find()) {
            List<String> cells = new ArrayList<>();
            Matcher td = TD.matcher(rel.group(1));
            while (td.find()) cells.add(strip(td.group(1)));
            if (cells.size() < 8) continue;
            releases.add(new Release(cells.get(0), cells.get(1), cells.get(3),
                    cells.get(4), cells.get(5), cells.get(6), cells.get(7)));
        }
        List<IndicatorRow> components = table(html, "Components");
        List<IndicatorRow> related = table(html, "Related");
        if (releases.isEmpty() && components.isEmpty() && related.isEmpty()) return null;
        return new IndicatorPage(country, indicator, group(TEMPORAL, html),
                List.copyOf(releases), components, related);
    }

    /** The Components / Related table under the given header word. */
    private static List<IndicatorRow> table(String html, String header) {
        int marker = html.indexOf(">" + header + "</th>");
        if (marker < 0) return List.of();
        int start = html.lastIndexOf("<table", marker);
        int end = html.indexOf("</table>", marker);
        if (start < 0 || end < 0) return List.of();
        List<IndicatorRow> out = new ArrayList<>();
        Matcher row = IND_ROW.matcher(html.substring(start, end));
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher td = TD.matcher(row.group(1));
            while (td.find()) cells.add(td.group(1));
            if (cells.size() < 5) continue;
            out.add(new IndicatorRow(strip(cells.get(0)), group(HREF, cells.get(0)),
                    strip(cells.get(1)), strip(cells.get(2)),
                    strip(cells.get(3)), strip(cells.get(4))));
        }
        return List.copyOf(out);
    }

    // ---- helpers -----------------------------------------------------------

    /** Splits the page on a row-opening pattern; each chunk ends at its own {@code </tr>}. */
    private static List<String> rows(String html, Pattern opener) {
        List<String> out = new ArrayList<>();
        Matcher m = opener.matcher(html);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) starts.add(m.start());
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int limit = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            String seg = html.substring(from, limit);
            int close = seg.lastIndexOf("</tr>");
            out.add(close < 0 ? seg : seg.substring(0, close + 5));
        }
        return out;
    }

    /** An attribute off a row's opening tag; TE mixes single and double quotes in the same tag. */
    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile(name + "=[\"']([^\"']*)[\"']").matcher(tag);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * The "actual / consensus" cell of the earnings board: two spans with a
     * literal slash span between them, a dash where the number is missing.
     * Always answers exactly two entries, both possibly empty.
     */
    private static List<String> pair(String cell) {
        List<String> values = new ArrayList<>();
        Matcher m = SPAN.matcher(cell);
        while (m.find()) {
            String v = strip(m.group(1));
            if (v.isEmpty() || "/".equals(v)) continue;
            values.add("-".equals(v) ? "" : v);
        }
        if (values.isEmpty()) return List.of("", "");
        if (values.size() == 1) return List.of(values.get(0), "");
        return List.of(values.get(0), values.get(values.size() - 1));
    }

    /**
     * The company name out of the first cell: it holds TWO anchors of the same
     * class - the mobile one spells the ticker, the desktop one the name - and
     * only the high-impact rows carry the {@code font-bold} that would have
     * told them apart. So the name is simply the anchor that is not the symbol.
     */
    private static String company(String cell, String symbol) {
        Matcher m = EARN_LINK.matcher(cell);
        while (m.find()) {
            String text = strip(m.group(1));
            if (!text.isEmpty() && !text.equalsIgnoreCase(symbol)) return text;
        }
        return "";
    }

    private static int impactOf(String word) {
        return switch (word) {
            case "High" -> 3;
            case "Medium" -> 2;
            case "Low" -> 1;
            default -> 0;
        };
    }

    private static String group(Pattern p, String body) {
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /** Tags out, entities decoded, whitespace collapsed - what a human would read. */
    private static String strip(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = TAG.matcher(raw).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return text.replaceAll("\\s+", " ").trim();
    }

    /** A number off a TE cell; NaN when the page prints nothing parseable. */
    private static double number(String raw) {
        if (raw == null) return Double.NaN;
        String cleaned = raw.replace("%", "").replace(",", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static String normalise(String path) {
        String p = path == null ? "" : path.trim();
        return p.startsWith("/") ? p : "/" + p;
    }

    /** "United States" and "united-states" both address the same page. */
    private static String slug(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
