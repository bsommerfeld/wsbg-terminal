package de.bsommerfeld.wsbg.terminal.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The exchanges behind a browser wall - Bangkok and Riyadh - read through the
 * BROWSER JOKER.
 *
 * <p>Both refuse a plain client and serve a real browser session, which is
 * what {@code @DirectFirst} is in production. The refusal is not automation
 * detection, as it first appeared: a cold profile meets a first-visit
 * challenge, and a session that has passed it once is served normally
 * (measured 2026-08-11 - the same page that answered a challenge to a fresh
 * profile answered in full to a warm one, with and without every
 * anti-automation flag). That is precisely the shape the joker has: it anchors
 * ONE session per origin and keeps it.
 *
 * <p>Read from the RENDERED page, not from an API: both exchanges print their
 * headline index into the document, while their JSON endpoints answer a
 * top-level request with a challenge of their own. The patterns below were
 * written against captured real pages, never guessed - what the page prints
 * is {@code TASI 10,845.58 28.57 (0.26%)} and {@code Last 1,624.36 +12.36
 * (+0.77%)}, and that is what is read.
 *
 * <p>Korea is deliberately ABSENT. Its pages carry navigation and no level;
 * the numbers live behind a POST API whose request shape cannot be established
 * from outside a running session. A client written on a guess about that shape
 * would be a silent failure wearing a source's name.
 */
@Singleton
public class WalledExchangeClient {

    private static final Logger LOG = LoggerFactory.getLogger(WalledExchangeClient.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    /**
     * One walled venue: where its headline index is printed, and the pattern
     * that reads it out of the rendered document.
     *
     * @param venue  the exchange, as a reader knows it
     * @param index  the index this venue leads with
     * @param url    the page the joker anchors on
     * @param muster level, change and percent, in the page's own order
     */
    record Platz(String venue, String index, String url, Pattern muster) {}

    static final List<Platz> PLAETZE = List.of(
            new Platz("Stock Exchange of Thailand", "SET",
                    "https://www.set.or.th/en/market/index/set/overview",
                    Pattern.compile("Last\\s+([\\d,]+\\.\\d+)\\s+([+-]?[\\d,]+\\.\\d+)\\s+"
                            + "\\(([+-]?[\\d.]+)%\\)")),
            new Platz("Saudi Exchange", "TASI",
                    "https://www.saudiexchange.sa/wps/portal/saudiexchange/newindex",
                    Pattern.compile("TASI\\s+([\\d,]+\\.\\d+)\\s+([+-]?[\\d,]+\\.\\d+)\\s+"
                            + "\\(([+-]?[\\d.]+)%\\)")));

    /** One venue's headline index. */
    public record Indexstand(String venue, String index, double stand, double veraenderung,
            double prozent) {}

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(30);

    private volatile List<Indexstand> cached = List.of();
    private volatile long cachedAtMs;

    /**
     * Test/default: plain direct transport, which these hosts refuse. Kept so
     * the class is constructible without wiring - and so a smoke run says
     * "walled", which is the truth about this transport, not about the source.
     */
    public WalledExchangeClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public WalledExchangeClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The headline index of every reachable walled venue. A venue that answers
     * a challenge instead of a page is absent from the result and logged -
     * never a zero, and never a stale number wearing today's date.
     */
    public List<Indexstand> staende() {
        List<Indexstand> hit = cached;
        if (!hit.isEmpty() && System.currentTimeMillis() - cachedAtMs < CACHE_TTL.toMillis()) {
            return hit;
        }
        List<Indexstand> out = new ArrayList<>(PLAETZE.size());
        for (Platz platz : PLAETZE) {
            Indexstand stand = lies(platz);
            if (stand != null) out.add(stand);
        }
        if (out.isEmpty()) {
            LOG.debug("[walled-venues] no walled venue answered - plain transport, or the "
                    + "session was challenged");
            return cached;
        }
        cached = List.copyOf(out);
        cachedAtMs = System.currentTimeMillis();
        LOG.info("[walled-venues] {} of {} venue(s) read", out.size(), PLAETZE.size());
        return cached;
    }

    private Indexstand lies(Platz platz) {
        try {
            WebResponse resp = fetcher.fetch(platz.url(),
                    Map.of("User-Agent", userAgent), requestTimeout);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.debug("[walled-venues] {} answered status {}", platz.venue(),
                        resp == null ? "null" : resp.status());
                return null;
            }
            return parse(platz, resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[walled-venues] {} failed: {}", platz.venue(), e.getMessage());
            return null;
        }
    }

    /** One rendered page → its headline index. Package-private for tests. */
    static Indexstand parse(Platz platz, String html) {
        String text = plainText(html);
        Matcher m = platz.muster().matcher(text);
        if (!m.find()) return null;
        try {
            return new Indexstand(platz.venue(), platz.index(),
                    zahl(m.group(1)), zahl(m.group(2)), zahl(m.group(3)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Markup out, one space between words - the pattern reads what a reader reads. */
    static String plainText(String html) {
        String t = html.replaceAll("(?s)<script.*?</script>|<style.*?</style>", " ");
        t = t.replaceAll("<[^>]+>", " ");
        return t.replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ");
    }

    private static double zahl(String raw) {
        return Double.parseDouble(raw.replace(",", "").replace("+", ""));
    }
}
