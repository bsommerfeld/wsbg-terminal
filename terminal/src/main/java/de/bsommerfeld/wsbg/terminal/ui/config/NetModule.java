package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.ProvidesIntoSet;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.FallbackRedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.OAuthRedditFetcher;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.RssRedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.net.RedditFetch;
import de.bsommerfeld.wsbg.terminal.reddit.support.TokenBucketRateLimiter;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.net.CefTransport;
import de.bsommerfeld.wsbg.terminal.ui.net.CefWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.Transport;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The terminal's contribution to the web world: the BROWSER transport (only
 * the terminal owns the embedded Chromium), the local instrument corpus, and
 * the auto-selecting {@link RedditSource}. The fetch seam itself — mode
 * resolution, cooldowns, conditional caching — lives in web-impl's
 * {@code HouseFetcher}; nothing here builds chains anymore.
 */
final class NetModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(NetModule.class);

    @Override
    protected void configure() {
        // Only @Provides factories live here (the BROWSER transport joins the
        // multibinder via @ProvidesIntoSet below).
    }

    /**
     * The ONE browser-joker transport — one hidden tab per origin (2026-07-13
     * audit C4), shared by everything that rides a BROWSER mode.
     */
    @Provides
    @Singleton
    CefWebFetcher provideCefWebFetcher(CefHost cefHost) {
        return new CefWebFetcher(cefHost);
    }

    /**
     * The BROWSER slot of the house fetcher. Contributed here because only
     * the terminal owns CEF. Master switch: the historical
     * {@code yahoo.browser-fetch-enabled} key gates the browser transport
     * globally (it always did — the key name is the historical accident, not
     * the reach). Disabled → a transport that always reports transport
     * failure; the fetcher's silence memory mutes it per host and every mode
     * array degrades to its DIRECT legs — the old degraded behaviour, decided
     * in ONE place.
     */
    @ProvidesIntoSet
    @Singleton
    Transport provideBrowserTransport(GlobalConfig config, CefWebFetcher joker) {
        if (!config.getYahoo().isBrowserFetchEnabled()) {
            LOG.info("browser transport disabled — BROWSER modes degrade to DIRECT");
            return new Transport() {
                @Override
                public FetchUtil util() {
                    return FetchUtil.BROWSER;
                }

                @Override
                public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetch(
                        String url, java.util.Map<String, String> headers,
                        java.time.Duration timeout) {
                    return de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse.failure();
                }
            };
        }
        return new CefTransport(joker);
    }

    /**
     * The OpenWeb door (Yahoo's per-ticker comment boards): page-context POSTs
     * from a hidden tab anchored on finance.yahoo.com — NOT the standard
     * browser transport, so it rides a dedicated named fetcher only the
     * yahoo-conversations source injects. Plain HTTP answers 403 on both
     * handshake legs (probed 2026-07-16); with the browser disabled the
     * binding degrades to transport-failure answers and the source yields
     * empty instead of breaking.
     *
     * <p>The transport is its own, but the HOST MEMORY is not: cooldowns are
     * an IP budget, and this leg talks to the same yahoo hosts the instrument
     * fan hammers. It therefore borrows the house fetcher's memory — asking it
     * before knocking and reporting its own blocks back — instead of the old
     * {@code hostCoolingDown → false}, which made this the one source in the
     * process with no restraint at all.
     */
    @Provides
    @Singleton
    @com.google.inject.name.Named("openweb")
    WebFetcher provideOpenWebFetcher(GlobalConfig config, CefHost cefHost, WebFetcher house) {
        final de.bsommerfeld.wsbg.terminal.ui.net.OpenWebConversationFetcher openWeb =
                config.getYahoo().isBrowserFetchEnabled()
                        ? new de.bsommerfeld.wsbg.terminal.ui.net.OpenWebConversationFetcher(cefHost)
                        : null;
        if (openWeb == null) {
            LOG.info("WebFetcher (openweb): browser transport disabled — degraded stub");
        }
        return new WebFetcher() {
            @Override
            public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetch(String url,
                    java.util.Map<String, String> headers, java.time.Duration timeout,
                    FetchUtil... modes) throws Exception {
                if (openWeb == null) {
                    return de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse.failure();
                }
                if (house.hostCoolingDown(url)) {
                    return de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse.text(429, "",
                            java.util.Map.of());
                }
                de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse r =
                        openWeb.fetch(url, headers, timeout);
                if (r.status() == 429 || r.status() == 403) house.reportWall(url);
                return r;
            }

            @Override
            public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetchBinary(String url,
                    java.util.Map<String, String> headers, java.time.Duration timeout,
                    FetchUtil... modes) {
                throw new UnsupportedOperationException("openweb is text-only");
            }

            @Override
            public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse post(String url,
                    java.util.Map<String, String> headers, String body, String contentType,
                    java.time.Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException(
                        "openweb POSTs run page-side inside the fetcher itself");
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return house.hostCoolingDown(url);
            }

            @Override
            public void reportWall(String url) {
                house.reportWall(url);
            }
        };
    }

    /**
     * The tier-3 local instrument corpus: SEC US listings + XETRA's
     * All-Tradable-Instruments CSV + the learned wallstreet-online ISIN
     * memory. Persisted under {@code <app-data>/instruments/} and refreshed
     * asynchronously when stale.
     *
     * <p>The old pre-CEF EDT carve-out (a private direct fetcher) is retired:
     * the corpus sources declare {@code MODES = {DIRECT}}, so their refresh
     * can fire at injector time without ever waking the browser.
     */
    @Provides
    @Singleton
    de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus provideInstrumentCorpus(
            WebFetcher fetcher) {
        java.nio.file.Path appData = StorageUtils.getAppDataDir();
        var corpus = new de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus(
                appData.resolve("instruments").resolve("instruments.jsonl"),
                List.of(new de.bsommerfeld.wsbg.terminal.instruments.SecTickerSource(fetcher),
                        new de.bsommerfeld.wsbg.terminal.instruments.XetraSource(fetcher),
                        new de.bsommerfeld.wsbg.terminal.instruments.WsoIsinSource(appData.resolve("wso-isin.jsonl"))));
        corpus.start();
        return corpus;
    }

    /**
     * Builds the {@link RedditSource}: a {@link FallbackRedditSource} that
     * auto-selects a working path at runtime (OAuth → anonymous {@code .json} →
     * RSS). Every delegate shares one repository but carries its own fetcher +
     * rate limiter; consumers only see {@link RedditSource} and never learn which
     * path answered.
     *
     * <p>The anonymous delegate rides the house fetcher with
     * {@code {BROWSER, DIRECT}} — the {@code .json} path 403s a bare client, so
     * the joker leads and plain HTTP rescues per request. Reddit's volume limit
     * is per-IP regardless of TLS fingerprint, so both transports share one
     * budget: the generous browser rate when the joker is available, else the
     * conservative anonymous rate.
     */
    @Provides
    @Singleton
    RedditSource provideRedditSource(RedditRepository repository,
            ApplicationEventBus eventBus, GlobalConfig config, WebFetcher webFetcher) {
        RedditConfig rc = config.getReddit();
        String probeSub = rc.getSubreddits().isEmpty()
                ? "wallstreetbetsGER" : rc.getSubreddits().get(0);

        // The delegates are wired with a null event bus on purpose: health is
        // owned by FallbackRedditSource (the only layer that sees the aggregate
        // — degraded only when the WHOLE chain is down, healthy as soon as any
        // delegate answers), so an individual scraper's failure no longer flips
        // the UI to "Defekt" while a fallback still works.
        RedditScraper oauth = new RedditScraper(repository, null,
                new OAuthRedditFetcher(config),
                new TokenBucketRateLimiter(rc.getOauthRateLimitBurst(),
                        rc.getOauthRateLimitRequestsPerSecond()));

        boolean browserJoker = config.getYahoo().isBrowserFetchEnabled();
        TokenBucketRateLimiter anonLimiter = browserJoker
                ? new TokenBucketRateLimiter(rc.getBrowserRateLimitBurst(),
                        rc.getBrowserRateLimitRequestsPerSecond())
                : new TokenBucketRateLimiter(rc.getRateLimitBurst(),
                        rc.getRateLimitRequestsPerSecond());
        RedditFetch anon = new RedditFetch() {
            @Override
            public String name() {
                return "house[browser→direct]";
            }

            @Override
            public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetch(
                    String url, java.util.Map<String, String> headers,
                    java.time.Duration timeout) throws Exception {
                return webFetcher.fetch(url, headers, timeout,
                        FetchUtil.BROWSER, FetchUtil.DIRECT);
            }
        };
        RedditScraper anonScraper = new RedditScraper(repository, null, anon, anonLimiter);

        // The RSS floor stays plain HTTP on purpose — it is the path that must
        // answer when everything else is walled, and it never was browser-borne.
        RedditFetch rssFetch = new RedditFetch() {
            @Override
            public String name() {
                return "house[direct]";
            }

            @Override
            public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetch(
                    String url, java.util.Map<String, String> headers,
                    java.time.Duration timeout) throws Exception {
                return webFetcher.fetch(url, headers, timeout, FetchUtil.DIRECT);
            }
        };
        RssRedditScraper rss = new RssRedditScraper(repository, config, null, rssFetch);

        LOG.info("Reddit source: dynamic fallback chain [OAuth → {} → RSS]", anon.name());
        return new FallbackRedditSource(List.of(oauth, anonScraper, rss), probeSub, 600L, eventBus);
    }
}
