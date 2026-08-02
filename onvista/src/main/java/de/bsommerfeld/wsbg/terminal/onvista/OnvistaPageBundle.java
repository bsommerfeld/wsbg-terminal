package de.bsommerfeld.wsbg.terminal.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cheap collective road: ONE fetch of the public instrument page
 * {@code www.onvista.de/aktien/<slug>-Aktie-<ISIN>} yields the whole desk in a
 * single {@code __NEXT_DATA__} island — snapshot, news, company, figures,
 * eodHistory, calendarEvents, peergroup (each peer with ISIN/WKN/symbol),
 * forumThreads, ratings, holdingsFunds/holdingsEtfs, savingsPlans, wikifolio.
 * Verified live 2026-08-02: the slug is irrelevant (onvista normalizes it by
 * redirect), the page answers ~550 KB.
 *
 * <p>The trade-off is explicit. A single 550 KB HTML read beats a dozen JSON
 * round-trips when MANY fields are needed at once and honours the
 * {@code Crawl-delay: 20} discipline far better; it is the wrong tool for one
 * field, where {@link OnvistaMarketClient} / {@link OnvistaFundamentalsClient}
 * fetch a few kilobytes instead.
 *
 * <p>The bundle deliberately hands back the same records the dedicated clients
 * produce — the section parsers are shared, so a caller can switch roads
 * without changing its own code.
 *
 * @see OnvistaApi for transport, rate discipline and the terms-of-service note
 */
@Singleton
public class OnvistaPageBundle extends OnvistaApi {

    private static final Logger LOG = LoggerFactory.getLogger(OnvistaPageBundle.class);

    private static final String PAGE_FMT = "https://www.onvista.de/aktien/x-Aktie-%s";

    /** Next.js embeds its whole page state in this one script tag. */
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    /** Test/default: plain direct transport. */
    public OnvistaPageBundle() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam. */
    @Inject
    public OnvistaPageBundle(@DirectFirst WebFetcher fetcher) {
        super(fetcher);
    }

    /**
     * One peer from the page's peer group. The peer carries its own
     * {@link OnvistaEntity} with ISIN, WKN and symbol, so the peer group
     * doubles as a keyless "comparable companies" resolver — feed the entity
     * straight back into the other clients.
     */
    public record Peer(OnvistaEntity instrument, double marketCap, double performance,
            int theScreenerChance, int theScreenerRisk) {}

    /** One news teaser from the page; {@code articleId} opens the full text. */
    public record NewsTeaser(String articleId, String headline, String publisher,
            String url, java.time.Instant published) {}

    /**
     * Everything the instrument page carries, in one object. Sections a page
     * happens not to have come back empty rather than null.
     */
    public record Bundle(OnvistaEntity entity, String isin,
            OnvistaFundamentalsClient.CompanySnapshot company,
            Optional<OnvistaFundamentalsClient.Figures> figures,
            List<OnvistaMarketClient.VenueQuote> quotes,
            Optional<OnvistaMarketClient.EodHistory> eodHistory,
            List<OnvistaMarketClient.CalendarEvent> calendarEvents,
            List<Peer> peers, List<NewsTeaser> news,
            Optional<OnvistaFundamentalsClient.ForumBoard> forum,
            List<OnvistaFundamentalsClient.FundHolder> holdingFunds,
            List<OnvistaFundamentalsClient.FundHolder> holdingEtfs) {}

    /**
     * Fetches the instrument page and parses every section it carries.
     * Empty when the page is unreachable or carries no {@code __NEXT_DATA__}.
     */
    public Optional<Bundle> byIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        try {
            WebResponse resp = fetcher.fetch(String.format(PAGE_FMT, isin.trim()),
                    Map.of("User-Agent", userAgent, "Accept", "text/html"), requestTimeout);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[onvista] page for {} → HTTP {}", isin,
                        resp == null ? "null" : resp.status());
                return Optional.empty();
            }
            Optional<Bundle> b = parse(resp.body());
            b.ifPresent(x -> LOG.debug("[onvista] bundle {} → {} peer(s), {} event(s)",
                    isin, x.peers().size(), x.calendarEvents().size()));
            return b;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[onvista] page bundle for {} failed: {}", isin, e.getMessage());
            return Optional.empty();
        }
    }

    /** Package-private, network-free: page HTML → the whole bundle. */
    static Optional<Bundle> parse(String html) {
        JsonNode next = nextData(html);
        if (next == null) return Optional.empty();
        JsonNode pageProps = next.path("props").path("pageProps");
        JsonNode data = pageProps.path("data");
        if (!data.isObject()) return Optional.empty();

        OnvistaEntity entity = entityOf(data.path("snapshot").path("instrument"));
        String isin = text(pageProps, "isin");

        var company = OnvistaFundamentalsClient.parseCompanySnapshot(data.path("company"))
                .orElse(null);
        var figures = OnvistaFundamentalsClient.parseFigures(data.path("figures"));
        // The snapshot section already carries the full venue list, so the
        // bundle answers the RLT/DLY question without a second request.
        var quotes = OnvistaMarketClient.parseQuotes(data.path("snapshot").path("quoteList"));
        // The page serves EOD row-shaped, the API columnar — different parser,
        // same record. Note the page ships only the recent window, but it
        // declares the same full availability start, so a caller that wants the
        // 30 years still has to go to OnvistaMarketClient#eodHistory.
        var eod = OnvistaMarketClient.parseEodRows(data.path("eodHistory"));
        var events = OnvistaMarketClient.parseCalendarEvents(data.path("calendarEvents"));
        var forum = OnvistaFundamentalsClient.parseForum(data.path("forumThreads"));
        var funds = OnvistaFundamentalsClient.parseFundHolders(data.path("holdingsFunds"));
        var etfs = OnvistaFundamentalsClient.parseFundHolders(data.path("holdingsEtfs"));

        if (entity == null && company == null && figures.isEmpty()) return Optional.empty();
        return Optional.of(new Bundle(entity, isin, company, figures, quotes, eod,
                events, parsePeers(data.path("peergroup")), parseNews(data.path("news")),
                forum, funds, etfs));
    }

    /** Package-private, network-free: the peer group with each peer's identifiers. */
    static List<Peer> parsePeers(JsonNode node) {
        List<Peer> out = new ArrayList<>();
        for (JsonNode p : listOf(node)) {
            OnvistaEntity e = entityOf(p.path("instrument"));
            if (e == null) continue;
            out.add(new Peer(e, num(p, "marketCap"), num(p, "performance"),
                    intOr(p, "chance", -1), intOr(p, "risk", -1)));
        }
        return List.copyOf(out);
    }

    /**
     * Package-private, network-free: the news section, which the page groups
     * into buckets ("Aktuelle News", "Analysen", …), each with its own
     * {@code list} of teasers. The buckets are flattened; duplicates across
     * buckets are dropped by article id.
     */
    static List<NewsTeaser> parseNews(JsonNode node) {
        List<NewsTeaser> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode bucket : listOf(node)) {
            for (JsonNode t : listOf(bucket, "list")) {
                String id = text(t, "entityValue");
                String headline = text(t, "headline");
                if (headline == null || (id != null && !seen.add(id))) continue;
                JsonNode pub = t.path("publisher");
                out.add(new NewsTeaser(id, headline,
                        pub.isTextual() ? pub.asText() : text(pub, "name"),
                        text(t.path("urls"), "WEBSITE"),
                        instant(t, "datetimePublication")));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Package-private, network-free: extracts and parses the {@code __NEXT_DATA__}
     * island. Null when the page has none (an error page, a redirect stub, or a
     * layout onvista changed).
     */
    static JsonNode nextData(String html) {
        if (html == null || html.isBlank()) return null;
        Matcher m = NEXT_DATA.matcher(html);
        if (!m.find()) return null;
        try {
            return JSON.readTree(m.group(1));
        } catch (Exception e) {
            LOG.debug("[onvista] __NEXT_DATA__ unparseable: {}", e.getMessage());
            return null;
        }
    }
}
