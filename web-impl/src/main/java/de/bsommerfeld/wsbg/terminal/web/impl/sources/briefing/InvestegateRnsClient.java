package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>A COLLECTOR in the new world: the index is a WIRE, not a search - it
 * carries what was announced, not what matches a query - so every pass pours
 * the whole current sweep into the pool and per-issuer questions are answered
 * THERE. De-duplication across passes is the pool's job; the cadence is the
 * scheduler's.
 */
@Singleton
public class InvestegateRnsClient extends AbstractWebSource implements CollectorSource {

    private static final Logger LOG = LoggerFactory.getLogger(InvestegateRnsClient.class);

    private static final String INDEX_URL = "https://www.investegate.co.uk/?page=%d";
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

    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final Duration requestTimeout = Duration.ofSeconds(20);

    @Inject
    public InvestegateRnsClient(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "investegate-rns";
    }

    @Override
    public FetchUtil[] mode() {
        return MODES;
    }

    /** The UK regulatory record - a carrier of its own, and a primary one. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("en", "RNS");
    }

    /** The old 10-minute sweep cache, expressed as the scheduler's cadence. */
    @Override
    public FetchInterval interval() {
        return FetchInterval.of(10, 15);
    }

    /**
     * One sweep over the index pages, newest first, de-duplicated by
     * permalink - the whole wire, UNFILTERED. An empty sweep is loud: the page
     * shape changed, or the request was refused.
     */
    @Override
    public List<Article> collect() {
        List<Article> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= PAGES; page++) {
            for (Article item : page(page)) {
                if (seen.add(item.uuid())) all.add(item);
            }
        }
        if (all.isEmpty()) {
            LOG.warn("[investegate] the index carried no announcement row - page shape "
                    + "changed, or the request was refused");
            return List.of();
        }
        LOG.info("[investegate] {} announcement(s) over {} page(s)", all.size(), PAGES);
        return all;
    }

    private List<Article> page(int page) {
        try {
            WebResponse resp = get(String.format(Locale.ROOT, INDEX_URL, page),
                    Map.of("Accept-Language", "en-GB,en;q=0.9"),
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
    static List<Article> parse(String html) {
        List<Article> out = new ArrayList<>();
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            String stamp = m.group(1).strip();
            String supplier = m.group(2).strip();
            String company = unescape(m.group(4)).strip();
            String link = m.group(5).strip();
            String headline = unescape(m.group(6)).strip();
            if (company.isEmpty() || headline.isEmpty() || link.isEmpty()) continue;
            out.add(new Article(link, headline,
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
            // The page prints a no-break space between date and time cell parts.
            return LocalDateTime.parse(raw.strip().replace(' ', ' '), STAMP)
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
