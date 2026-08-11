package de.bsommerfeld.wsbg.terminal.boersede;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * boerse.de as a MARKET-DATA desk rather than a news wire - deliberately NOT a
 * {@code NewsSource}: none of these surfaces is an article, they are records
 * (a filing, a calendar entry, a rating, a balance sheet). Probed live
 * 2026-08-02; same wall-free, terms-free host as {@link BoerseDeNewsClient}.
 *
 * <p>Instrument-addressed (ISIN, slug irrelevant):
 * <ul>
 *   <li>{@link #analystCalls} - {@code /empfehlungen/x/<ISIN>}: the dpa-AFX
 *       recommendation ARCHIVE. SAP carries 17 pages ≈ 340 entries, so this is
 *       a multi-year rating history, not a snapshot.</li>
 *   <li>{@link #fundamentals} - {@code /fundamental-analyse/x/<ISIN>}: P&amp;L,
 *       balance sheet, per-share figures, profitability and headcount over
 *       eight fiscal years in one page.</li>
 * </ul>
 *
 * <p>Global lists, no instrument needed - all four currently UNUSED by the app
 * and wired so they can be mobilised without reopening this source:
 * <ul>
 *   <li>{@link #insiderDeals} - {@code /insider-trades/}: directors' dealings
 *       with the reporting person's REAL NAME, the role, the direction and the
 *       volume, small caps included.</li>
 *   <li>{@link #companyDates} - {@code /unternehmenstermine/}: the corporate
 *       calendar (results, dividends, AGMs), Austrian names included.</li>
 *   <li>{@link #ipos} - {@code /ipos/}: first-listing date, issue price,
 *       current price and performance since listing.</li>
 *   <li>{@link #stockSplits} - {@code /aktiensplits/}: ratio and effective date
 *       - the cheapest way to know a price series has a discontinuity.</li>
 * </ul>
 *
 * <p><b>Gotchas.</b> (1) Pagination must follow the page's own
 * {@code rel="next"}: appending {@code _seite,N} to the {@code /x/<ISIN>} form
 * 301s back to page 1 and drops the parameter. (2) The recommendation list's
 * LEAD teaser has no date cell - its date hides as a run-together
 * {@code yyyyMMdd} at the head of the dpa-AFX teaser text. (3) Table labels
 * carry {@code &shy;} INSIDE the word, so keys are only stable after
 * {@link BoerseDeHtml#text} strips it. (4) An unknown ISIN answers 410, not 404.
 * (5) Prices anywhere on this host are end-of-day (Stuttgart close) - never
 * treat them as realtime.
 */
@Singleton
public class BoerseDeMarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(BoerseDeMarketClient.class);

    static final String BASE = "https://www.boerse.de";

    static final String INSIDER_URL = BASE + "/insider-trades/";
    static final String COMPANY_DATES_URL = BASE + "/unternehmenstermine/";
    static final String IPO_URL = BASE + "/ipos/";
    static final String SPLIT_URL = BASE + "/aktiensplits/";
    /** Page 1 of the recommendation archive; deeper pages come from {@code rel="next"}. */
    static final String RECOMMENDATIONS_URL = BASE + "/empfehlungen/x/%s";
    /** The recommendation archive's page suffix, appended to the canonical URL. */
    static final String PAGE_SUFFIX = "_seite,%d,anzahl,20";
    static final String FUNDAMENTALS_URL = BASE + "/fundamental-analyse/x/%s";

    /** Recommendation pages to walk at most - SAP alone has 17. */
    /**
     * How far the {@code rel="next"} walk may reach. Twenty pages of twenty
     * rows spans the whole archive of a well-covered name (SAP: 17 pages back
     * to 2023, live-measured 2026-08-09). It is a RUNAWAY bound, not a working
     * limit: the walk stops the moment {@code limit} rows are in hand, so a
     * caller asking for ten still pays exactly one fetch.
     */
    static final int MAX_RECOMMENDATION_PAGES = 20;

    /**
     * One directors' dealing off the global list.
     *
     * @param date     notification date
     * @param company  issuer display name
     * @param isin     issuer ISIN from the row's instrument link, or {@code null}
     * @param person   the reporting person's real name, {@code "Surname, Given"}
     * @param tradeType German direction as printed ({@code Kauf} / {@code Verkauf})
     * @param role     the person's standing ({@code Vorstand}, {@code Aufsichtsrat},
     *                 {@code enge Beziehung} …)
     * @param volume   deal volume in {@link #currency}, or {@code null} when unparseable
     * @param currency the volume's currency as printed ({@code EUR}, {@code USD})
     */
    public record InsiderDeal(LocalDate date, String company, String isin, String person,
                              String tradeType, String role, Double volume, String currency) {

        /** True for a purchase - the direction a desk reads as an insider signal. */
        public boolean isBuy() {
            return tradeType != null && tradeType.toLowerCase(Locale.GERMAN).startsWith("kauf");
        }
    }

    /**
     * One corporate-calendar entry.
     *
     * @param date  the scheduled day
     * @param company issuer display name
     * @param isin  issuer ISIN from the row's instrument link, or {@code null}
     * @param event the German event label as printed ({@code Q3-Zahlen},
     *              {@code Dividendenzahlung}, {@code Halbjahresfinanzbericht 2026} …)
     */
    public record CompanyDate(LocalDate date, String company, String isin, String event) {}

    /**
     * One dpa-AFX analyst call.
     *
     * @param date   the day the call was wired
     * @param house  the analysing house as printed ({@code JPMORGAN},
     *               {@code GOLDMAN SACHS} …)
     * @param rating the rating in the house's own wording ({@code buy},
     *               {@code hold}, {@code Overweight} …), or {@code null}
     * @param headline the full wire headline, kept verbatim
     * @param link   permalink to the dpa-AFX piece (carries the price target in
     *               its body)
     */
    public record AnalystCall(LocalDate date, String house, String rating,
                              String headline, String link) {}

    /**
     * One fiscal year of the fundamental page, all its tables pivoted onto the
     * year. Keys are the German row labels exactly as the site prints them once
     * soft hyphens are gone - verified live 2026-08-02 across all six tables:
     * {@code Umsatz}, {@code Ergebnis vor Steuer (EBT)}, {@code Jahresüberschuss},
     * {@code Dividendenausschüttung}; {@code Umlaufvermögen},
     * {@code Anlagevermögen}, {@code Summe Aktiva}, {@code Gesamtverbindlichkeiten},
     * {@code Eigenkapital}, {@code Eigenkapitalquote}; {@code Gewinn je Aktie
     * (verwässert)}, {@code Buchwert je Aktie}, {@code Dividende je Aktie},
     * {@code Anzahl der Aktien}, {@code Marktkapitalisierung*};
     * {@code KGV (Kurs-Gewinn-Verhältnis)} and its KBV/KUV siblings;
     * {@code Umsatzrendite}, {@code Eigenkapitalrendite},
     * {@code Dividendenrendite}; {@code Personal am Jahresende},
     * {@code Umsatz je Mitarbeiter}. Absent figures - the running year reports
     * {@code -} on everything except share count and market cap - are simply not
     * in the map.
     *
     * <p>Units follow the page's own headings: P&amp;L and balance sheet in
     * millions of the reporting currency, per-share figures in currency units,
     * profitability in percent.
     *
     * @param year    the fiscal year
     * @param figures label → value, never {@code null}
     */
    public record FundamentalYear(int year, Map<String, Double> figures) {

        /** One figure by its German label, or {@code null} when not reported. */
        public Double get(String label) {
            return figures.get(label);
        }
    }

    /**
     * One recent first listing.
     *
     * @param company   issuer display name
     * @param isin      issuer ISIN, or {@code null}
     * @param wkn       German security number
     * @param firstQuote date of the first quotation
     * @param issuePrice the issue price, or {@code null}
     * @param currentPrice the current (end-of-day) price, or {@code null}
     * @param performancePercent performance since listing in percent, or {@code null}
     */
    public record IpoEntry(String company, String isin, String wkn, LocalDate firstQuote,
                           Double issuePrice, Double currentPrice, Double performancePercent) {}

    /**
     * One share split.
     *
     * @param company issuer display name
     * @param isin    issuer ISIN, or {@code null}
     * @param ratio   the ratio as printed ({@code 1-7} = old-to-new, {@code 5-1} a
     *                reverse split)
     * @param date    the effective day
     */
    public record StockSplit(String company, String isin, String ratio, LocalDate date) {}

    private static final Pattern ROW_LINK =
            Pattern.compile("(?is)<a\\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    /** {@code JPMORGAN: SAP "hold"} - house before the colon, rating in the quotes. */
    private static final Pattern CALL_HEADLINE =
            Pattern.compile("^\\s*([^:]{2,60}?)\\s*:\\s*.*?[\"„»']([^\"“«']{1,40})[\"“«']");
    private static final Pattern VOLUME =
            Pattern.compile("([0-9][0-9.]*(?:,[0-9]+)?)\\s*([A-Z]{3})");
    private static final Pattern YEAR = Pattern.compile("^(19|20)\\d{2}$");

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public BoerseDeMarketClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@link DirectFirst} seam. Same reasoning as the news
     * client - a wall-free keyless host answers plain HTTP in milliseconds, and
     * the browser joker stays behind it purely as the rescue if that changes.
     */
    @Inject
    public BoerseDeMarketClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    // -------------------------------------------------------------- global lists

    /**
     * Directors' dealings across ALL covered issuers, newest first. Currently
     * unused by the app; the whole point of the surface is that it is global -
     * a small-cap board member buying is visible here without knowing the name
     * to ask for.
     */
    public List<InsiderDeal> insiderDeals(int limit) {
        return cut(parseInsiderDeals(get(INSIDER_URL)), limit);
    }

    /** The global corporate calendar, soonest first. Currently unused by the app. */
    public List<CompanyDate> companyDates(int limit) {
        return cut(parseCompanyDates(get(COMPANY_DATES_URL)), limit);
    }

    /** Recent first listings with their performance since issue. Currently unused. */
    public List<IpoEntry> ipos(int limit) {
        return cut(parseIpos(get(IPO_URL)), limit);
    }

    /** Recent share splits with ratio and effective date. Currently unused. */
    public List<StockSplit> stockSplits(int limit) {
        return cut(parseStockSplits(get(SPLIT_URL)), limit);
    }

    // --------------------------------------------------------- instrument surfaces

    /**
     * The dpa-AFX recommendation archive for one ISIN, newest first, walked up
     * to {@link #MAX_RECOMMENDATION_PAGES} pages via {@code rel="next"}.
     */
    public List<AnalystCall> analystCalls(String isin, int limit) {
        if (isin == null || isin.isBlank() || limit <= 0) return List.of();
        String erste = String.format(RECOMMENDATIONS_URL, isin.trim().toUpperCase(Locale.ROOT));
        String html = get(erste);
        if (html == null) return List.of();
        List<AnalystCall> out = new ArrayList<>(parseAnalystCalls(html));
        if (out.isEmpty() || out.size() >= limit) {
            return dedupe(out).stream().limit(limit).toList();
        }
        // Paging runs on the PAGE SCHEME, not on rel="next": only the first
        // page carries that link (from page two on the site serves a windowed
        // pager: 2, 4, 5, 6, … 17), so a next-walk dead-ends at forty rows -
        // and the ISIN form ignores the page number entirely, answering page
        // one for every N. Both live-measured 2026-08-09. The canonical URL
        // the first page names is the one that pages.
        String basis = BoerseDeHtml.canonical(html);
        if (basis == null) return dedupe(out).stream().limit(limit).toList();
        Set<String> gesehen = new LinkedHashSet<>();
        for (AnalystCall c : out) gesehen.add(c.headline() + "|" + c.date());
        for (int page = 2; page <= MAX_RECOMMENDATION_PAGES; page++) {
            String seite = get(basis + String.format(PAGE_SUFFIX, page));
            if (seite == null) break;
            int neu = 0;
            for (AnalystCall c : parseAnalystCalls(seite)) {
                if (gesehen.add(c.headline() + "|" + c.date())) {
                    out.add(c);
                    neu++;
                }
            }
            // Past the last page the site repeats itself rather than 404ing -
            // a page that adds nothing new IS the end of the archive.
            if (neu == 0 || out.size() >= limit) break;
        }
        return dedupe(out).stream().limit(limit).toList();
    }

    /**
     * The eight-fiscal-year fundamental picture for one ISIN, oldest year first.
     * Every table on the page (P&amp;L, balance sheet, per-share, profitability,
     * headcount) is folded into the same per-year map.
     */
    public List<FundamentalYear> fundamentals(String isin) {
        if (isin == null || isin.isBlank()) return List.of();
        return parseFundamentals(
                get(String.format(FUNDAMENTALS_URL, isin.trim().toUpperCase(Locale.ROOT))));
    }

    // ------------------------------------------------------------------- parsers

    /**
     * Parses {@code /insider-trades/}: a {@code standardTable} of six cells -
     * date, issuer (linked, so the ISIN comes free), reporting person,
     * direction, role, volume. Package-private for tests.
     */
    static List<InsiderDeal> parseInsiderDeals(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<InsiderDeal> out = new ArrayList<>();
        try {
            for (String row : rows(html, "row-bordered")) {
                List<String> raw = BoerseDeHtml.rawCells(row);
                if (raw.size() < 6) continue;
                LocalDate date = BoerseDeHtml.parseGermanDate(BoerseDeHtml.text(raw.get(0)));
                if (date == null) continue; // header row
                String volumeCell = BoerseDeHtml.text(raw.get(5));
                Matcher v = VOLUME.matcher(volumeCell);
                Double volume = null;
                String currency = null;
                if (v.find()) {
                    volume = BoerseDeHtml.parseGermanNumber(v.group(1));
                    currency = v.group(2);
                }
                out.add(new InsiderDeal(date,
                        BoerseDeHtml.text(raw.get(1)),
                        BoerseDeHtml.instrumentIsin(raw.get(1)),
                        BoerseDeHtml.text(raw.get(2)),
                        BoerseDeHtml.text(raw.get(3)),
                        BoerseDeHtml.text(raw.get(4)),
                        volume, currency));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable boerse.de insider table: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * Parses {@code /unternehmenstermine/}: three cells - date, linked issuer,
     * event label. Package-private for tests.
     */
    static List<CompanyDate> parseCompanyDates(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<CompanyDate> out = new ArrayList<>();
        try {
            for (String row : rows(html, "row-bordered")) {
                List<String> raw = BoerseDeHtml.rawCells(row);
                if (raw.size() < 3) continue;
                LocalDate date = BoerseDeHtml.parseGermanDate(BoerseDeHtml.text(raw.get(0)));
                if (date == null) continue;
                String event = BoerseDeHtml.text(raw.get(2));
                if (event.isEmpty()) continue;
                out.add(new CompanyDate(date, BoerseDeHtml.text(raw.get(1)),
                        BoerseDeHtml.instrumentIsin(raw.get(1)), event));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable boerse.de calendar: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * Parses one recommendation page. The list rows are the same
     * {@code row row-bordered} markup as the news lists; the illustrated LEAD is
     * the newest call and carries its date only inside the teaser text (gotcha 2).
     * Package-private for tests.
     */
    static List<AnalystCall> parseAnalystCalls(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<AnalystCall> out = new ArrayList<>();
        try {
            Matcher lead = Pattern.compile(
                    "(?is)<h3>\\s*<a\\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>\\s*</h3>\\s*<p>(.*?)</p>")
                    .matcher(html);
            if (lead.find()) {
                AnalystCall call = call(BoerseDeHtml.text(lead.group(2)),
                        BoerseDeHtml.decodeEntities(lead.group(1)),
                        BoerseDeHtml.parseCompactDate(BoerseDeHtml.text(lead.group(3))));
                if (call != null) out.add(call);
            }
            for (String row : BoerseDeHtml.chunksAt(html, "<div class=\"row row-bordered")) {
                LocalDate date = BoerseDeHtml.parseGermanDate(BoerseDeHtml.text(row));
                Matcher m = ROW_LINK.matcher(row);
                if (!m.find()) continue;
                AnalystCall call = call(BoerseDeHtml.text(m.group(2)),
                        BoerseDeHtml.decodeEntities(m.group(1)), date);
                if (call != null) out.add(call);
            }
        } catch (Exception e) {
            LOG.warn("Unparseable boerse.de recommendation page: {}", e.getMessage());
            return List.of();
        }
        return dedupe(out);
    }

    /**
     * Parses {@code /fundamental-analyse/x/<ISIN>}: every table whose first
     * header cell reads {@code Geschäftsjahr} contributes its rows, pivoted onto
     * the year columns. One parser for all five tables - they share the exact
     * same shape, which is why no table is addressed by its anchor id.
     * Package-private for tests.
     */
    static List<FundamentalYear> parseFundamentals(String html) {
        if (html == null || html.isBlank()) return List.of();
        Map<Integer, Map<String, Double>> byYear = new TreeMap<>();
        try {
            Matcher tables = Pattern.compile("(?is)<table\\b[^>]*>(.*?)</table>").matcher(html);
            while (tables.find()) {
                String table = tables.group(1);
                List<Integer> years = headerYears(table);
                if (years.isEmpty()) continue;
                for (String row : rows(table, null)) {
                    List<String> cells = BoerseDeHtml.cells(row);
                    if (cells.size() < 2) continue;
                    String label = cells.get(0);
                    if (label.isEmpty()) continue;
                    for (int i = 1; i < cells.size() && i - 1 < years.size(); i++) {
                        Double value = BoerseDeHtml.parseGermanNumber(cells.get(i));
                        if (value == null) continue;
                        byYear.computeIfAbsent(years.get(i - 1), y -> new LinkedHashMap<>())
                                .put(label, value);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Unparseable boerse.de fundamentals: {}", e.getMessage());
            return List.of();
        }
        List<FundamentalYear> out = new ArrayList<>();
        byYear.forEach((year, figures) -> out.add(new FundamentalYear(year, Map.copyOf(figures))));
        return List.copyOf(out);
    }

    /** Parses {@code /ipos/}: name, WKN, first-quote date, issue/current price, performance. */
    static List<IpoEntry> parseIpos(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<IpoEntry> out = new ArrayList<>();
        try {
            for (String row : rows(html, "row-bordered")) {
                List<String> raw = BoerseDeHtml.rawCells(row);
                if (raw.size() < 6) continue;
                String company = BoerseDeHtml.text(raw.get(0));
                LocalDate first = BoerseDeHtml.parseGermanDate(BoerseDeHtml.text(raw.get(2)));
                if (company.isEmpty() || first == null) continue;
                out.add(new IpoEntry(company, BoerseDeHtml.instrumentIsin(raw.get(0)),
                        BoerseDeHtml.text(raw.get(1)), first,
                        BoerseDeHtml.parseGermanNumber(BoerseDeHtml.text(raw.get(3))),
                        BoerseDeHtml.parseGermanNumber(BoerseDeHtml.text(raw.get(4))),
                        BoerseDeHtml.parseGermanNumber(BoerseDeHtml.text(raw.get(5)))));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable boerse.de IPO table: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * Parses {@code /aktiensplits/}: company, ratio, effective date. The markup
     * emits stray unclosed {@code <tr>} placeholders between real rows, so rows
     * without three cells are dropped rather than trusted.
     */
    static List<StockSplit> parseStockSplits(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<StockSplit> out = new ArrayList<>();
        try {
            for (String row : rows(html, "bordered-row")) {
                List<String> raw = BoerseDeHtml.rawCells(row);
                if (raw.size() < 3) continue;
                LocalDate date = BoerseDeHtml.parseGermanDate(BoerseDeHtml.text(raw.get(2)));
                String company = BoerseDeHtml.text(raw.get(0));
                if (date == null || company.isEmpty()) continue;
                out.add(new StockSplit(company, BoerseDeHtml.instrumentIsin(raw.get(0)),
                        BoerseDeHtml.text(raw.get(1)), date));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable boerse.de split table: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ helpers

    /** The fiscal years of a header row, or empty when this is not such a table. */
    static List<Integer> headerYears(String table) {
        Matcher th = Pattern.compile("(?is)<th\\b[^>]*>(.*?)</th>").matcher(table);
        List<String> heads = new ArrayList<>();
        while (th.find()) heads.add(BoerseDeHtml.text(th.group(1)));
        if (heads.isEmpty() || !heads.get(0).startsWith("Geschäftsjahr")) return List.of();
        List<Integer> years = new ArrayList<>();
        for (int i = 1; i < heads.size(); i++) {
            if (YEAR.matcher(heads.get(i)).matches()) years.add(Integer.valueOf(heads.get(i)));
        }
        return years;
    }

    /**
     * The {@code <tr>} chunks of a document. When {@code rowClass} is given only
     * rows carrying it are returned. Chunk-splitting rather than balanced
     * matching, because boerse.de leaves {@code <tr>} elements unclosed.
     */
    static List<String> rows(String html, String rowClass) {
        List<String> out = new ArrayList<>();
        for (String chunk : BoerseDeHtml.chunksAt(html, "<tr")) {
            if (rowClass == null) {
                out.add(chunk);
                continue;
            }
            int gt = chunk.indexOf('>');
            String openingTag = gt > 0 ? chunk.substring(0, gt) : chunk;
            if (openingTag.contains(rowClass)) out.add(chunk);
        }
        return out;
    }

    /** {@code JPMORGAN: SAP "hold"} → a call; anything unshaped → {@code null}. */
    static AnalystCall call(String headline, String link, LocalDate date) {
        if (headline == null || headline.isBlank() || link == null || link.isBlank()) return null;
        Matcher m = CALL_HEADLINE.matcher(headline);
        String house = m.find() ? m.group(1).trim() : null;
        String rating = house != null ? m.group(2).trim() : null;
        if (house == null) {
            int colon = headline.indexOf(':');
            if (colon <= 0) return null; // not a recommendation headline at all
            house = headline.substring(0, colon).trim();
        }
        return new AnalystCall(date, house, rating, headline, link);
    }

    private static <T> List<T> dedupe(List<T> items) {
        return List.copyOf(new LinkedHashSet<>(items));
    }

    private static <T> List<T> cut(List<T> items, int limit) {
        return limit <= 0 ? List.of() : items.stream().limit(limit).toList();
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "text/html,application/xhtml+xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[boerse.de] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[boerse.de] fetch failed for {}: {}", url, e.getMessage());
        }
        return null;
    }
}
