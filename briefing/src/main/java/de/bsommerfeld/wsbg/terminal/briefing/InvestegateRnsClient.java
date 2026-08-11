package de.bsommerfeld.wsbg.terminal.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The UK regulatory wire - RNS and its sibling services, through Investegate's
 * announcement index (keyless, live-verified 2026-08-11).
 *
 * <p>This is the leg the house had for Germany (EQS) and the Nordics (MFN) and
 * for Britain not at all: a UK issuer's own mandatory disclosures - results,
 * directors' dealings, holdings notifications, trading updates - never reached
 * the dossier except where a newspaper happened to write about them.
 *
 * <p>The exchange's own door stayed shut: its news component answers a POST
 * with an empty list for every parameter shape tried, and every UK financial
 * portal probed sits behind a JavaScript challenge. Investegate serves the
 * same wire SERVER-RENDERED, which is why it is the door that opened - once
 * the transport started sending a browser's full header set (the same request
 * was refused before that, which is worth remembering the next time a source
 * is written off).
 *
 * <p>Announcements are the ISSUER's own words under liability, and the sphere
 * says so: a filing and a newspaper report about it are two independent
 * carriers, not one story twice.
 */
@Singleton
public class InvestegateRnsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(InvestegateRnsClient.class);

    private static final String INDEX_URL = "https://www.investegate.co.uk/?page=%d";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** How many index pages the sweep walks - each carries roughly a hundred rows. */
    private static final int PAGES = 3;

    /**
     * One announcement row of the index table: timestamp cell, supplier cell,
     * company cell (name plus its TIDM in brackets), headline cell with the
     * permalink. Read as one block so the four cells cannot drift apart.
     */
    private static final Pattern ROW = Pattern.compile(
            "<tr>\\s*<td>([^<]+)</td>.*?class=\"regulatory source-(\\w+)\".*?"
                    + "<a href=\"[^\"]*/company/([^\"]+)\">([^<]+)</a>.*?"
                    + "<a class=\"announcement-link\" href=\"([^\"]+)\">([^<]+)</a>",
            Pattern.DOTALL);

    /** {@code 10 Aug 2026 06:38 PM} - London local time, as the page prints it. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy hh:mm a", Locale.ENGLISH);
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);

    private volatile List<RawNewsItem> sweep = List.of();
    private volatile long sweptAtMs;
    private final Object sweepLock = new Object();

    /** Test/default: plain direct transport. */
    public InvestegateRnsClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public InvestegateRnsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "investegate-rns";
    }

    /** The UK regulatory record - a carrier of its own, and a primary one. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("en", "RNS");
    }

    /**
     * DOSSIER-ONLY, like the German and Nordic disclosure legs beside it. A
     * share buyback notice from a London investment trust is research material
     * for whoever is researching that trust; on the wire of a German room it
     * is a headline about nobody.
     */
    @Override
    public boolean dossierOnly() {
        return true;
    }

    /**
     * The index is a WIRE, not a search: it carries what was announced, not
     * what matches a query, so the fan is answered by FILTERING the sweep -
     * one fetch then serves every subject of a run.
     *
     * <p>The SYMBOL fan matches the TIDM in its brackets, not loose text: the
     * index prints "Company Name (TIDM)", and a three-letter code searched as
     * free text would hit any company whose name happens to contain it.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        String bare = symbol.strip().toUpperCase(Locale.ROOT);
        int dot = bare.indexOf('.');
        if (dot > 0) bare = bare.substring(0, dot);
        return matching("(" + bare + ")", limit);
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        return matching(companyName, limit);
    }

    /** Every announcement of the current sweep, newest first - the whole wire. */
    public List<RawNewsItem> latest(int limit) {
        List<RawNewsItem> all = currentSweep();
        return all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
    }

    /**
     * Rows whose company cell carries the needle - the name as the index
     * prints it, or the TIDM in its brackets. Deliberately strict: the index
     * cell is "Company Name (TIDM)" and nothing else, so a contains-match here
     * cannot drag in a body mention the way a full-text search would.
     */
    private List<RawNewsItem> matching(String needle, int limit) {
        if (needle == null || needle.isBlank() || limit <= 0) return List.of();
        String key = needle.strip().toLowerCase(Locale.ROOT);
        List<RawNewsItem> out = new ArrayList<>();
        for (RawNewsItem item : currentSweep()) {
            if (out.size() >= limit) break;
            String publisher = item.publisher() == null ? "" : item.publisher().toLowerCase(Locale.ROOT);
            if (publisher.contains(key)) out.add(item);
        }
        return out;
    }

    private List<RawNewsItem> currentSweep() {
        List<RawNewsItem> hit = sweep;
        if (!hit.isEmpty() && System.currentTimeMillis() - sweptAtMs < CACHE_TTL.toMillis()) {
            return hit;
        }
        synchronized (sweepLock) {
            if (!sweep.isEmpty() && System.currentTimeMillis() - sweptAtMs < CACHE_TTL.toMillis()) {
                return sweep;
            }
            List<RawNewsItem> all = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int page = 1; page <= PAGES; page++) {
                for (RawNewsItem item : page(page)) {
                    if (seen.add(item.uuid())) all.add(item);
                }
            }
            if (all.isEmpty()) {
                LOG.warn("[investegate] the index carried no announcement row - page shape "
                        + "changed, or the request was refused");
                return sweep;
            }
            sweep = List.copyOf(all);
            sweptAtMs = System.currentTimeMillis();
            LOG.info("[investegate] {} announcement(s) over {} page(s)", all.size(), PAGES);
            return sweep;
        }
    }

    private List<RawNewsItem> page(int page) {
        try {
            WebResponse resp = fetcher.fetch(String.format(Locale.ROOT, INDEX_URL, page),
                    Map.of("User-Agent", userAgent,
                            "Accept-Language", "en-GB,en;q=0.9"),
                    requestTimeout);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.debug("[investegate] page {} answered status {}", page,
                        resp == null ? "null" : resp.status());
                return List.of();
            }
            return parse(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[investegate] page {} failed: {}", page, e.getMessage());
            return List.of();
        }
    }

    /** One index page → its announcement rows. Package-private for tests. */
    static List<RawNewsItem> parse(String html) {
        List<RawNewsItem> out = new ArrayList<>();
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            String stamp = m.group(1).strip();
            String supplier = m.group(2).strip();
            String company = unescape(m.group(4)).strip();
            String link = m.group(5).strip();
            String headline = unescape(m.group(6)).strip();
            if (company.isEmpty() || headline.isEmpty() || link.isEmpty()) continue;
            out.add(new RawNewsItem(link, headline,
                    // The company cell IS the publisher here: an announcement
                    // is published BY the issuer, and the supplier is only the
                    // wire that carried it.
                    company + " (" + supplier + ")", link, stampOf(stamp), List.of()));
        }
        return out;
    }

    static Instant stampOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.strip().replace(" ", " "), STAMP)
                    .atZone(LONDON).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replaceAll("\\s+", " ");
    }
}
