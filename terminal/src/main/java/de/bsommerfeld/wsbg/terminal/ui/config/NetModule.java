package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
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
import de.bsommerfeld.wsbg.terminal.source.net.TokenBucketRateLimiter;
import de.bsommerfeld.wsbg.terminal.source.net.CachingWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetchChain;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.net.CefWebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The network seam: the shared {@link WebFetcher} chain, the local instrument
 * corpus, and the auto-selecting {@link RedditSource}. Everything byte-fetching
 * rides the one shared transport, so new news wires opt in just by taking a
 * {@link WebFetcher} — no per-source plumbing.
 */
final class NetModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(NetModule.class);

    @Override
    protected void configure() {
        // Only @Provides factories live here.
    }

    /**
     * The generic fetch strategy any source can consume: a {@link WebFetchChain}
     * resolved in order. When {@code yahoo.browser-fetch-enabled} is on, the
     * browser "joker" ({@link CefWebFetcher} — real browser session + cookies,
     * served like an ordinary browser) leads, with plain {@link DirectWebFetcher} as the fallback;
     * toggled off, it's direct-only. New news wires opt in just by taking a
     * {@link WebFetcher} and choosing their chain order — no per-source plumbing.
     *
     * <p>When {@code net.conditional-requests} is on (default), the chain is
     * wrapped in a {@link CachingWebFetcher} — OUTSIDE the chain, so it sees the
     * final answer — which revalidates unchanged endpoints via
     * {@code If-None-Match}/{@code If-Modified-Since} and serves 304s from an
     * in-memory cache (traffic blending, Hebel 2). The browser transport sets
     * its own headers and drops the conditional ones; that path just keeps
     * returning full 200s — a clean fallback, not a bug.
     */
    @Provides
    @Singleton
    WebFetcher provideWebFetcher(GlobalConfig config, CefHost cefHost) {
        DirectWebFetcher direct = new DirectWebFetcher();
        WebFetcher chain;
        if (config.getYahoo().isBrowserFetchEnabled()) {
            // NOTE: the Yahoo origin browsers warm up lazily on first use. Eager
            // prewarming was tried and reverted — kicking it at injector time
            // forced CEF init off the EDT (before the window initializes it on the
            // EDT), which hangs on macOS. A correct prewarm must run AFTER the
            // window has brought CEF up; deferred until that hook exists.
            chain = new WebFetchChain(List.of(new CefWebFetcher(cefHost), direct));
        } else {
            chain = new WebFetchChain(List.of(direct));
        }
        WebFetcher fetcher = config.getNet().isConditionalRequests()
                ? new CachingWebFetcher(chain)
                : chain;
        LOG.info("WebFetcher: {}", fetcher.name());
        return fetcher;
    }

    /**
     * The tier-3 local instrument corpus: SEC US listings (official daily JSON) +
     * XETRA's All-Tradable-Instruments CSV (every German-listed stock/ETF, via the
     * stable instruments page → current blob link) + the learned wallstreet-online
     * ISIN memory. Persisted under {@code <app-data>/instruments/} and refreshed
     * asynchronously when stale. Grounds the resolver's identity judge in live
     * feed facts instead of training-time memory; consumed by
     * {@code EditorialAgent} via its optional setter. The direct fetcher suffices —
     * neither sec.gov nor xetra.com has a bot wall.
     */
    @Provides
    @Singleton
    de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus provideInstrumentCorpus() {
        java.nio.file.Path appData = StorageUtils.getAppDataDir();
        DirectWebFetcher direct = new DirectWebFetcher();
        var corpus = new de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus(
                appData.resolve("instruments").resolve("instruments.jsonl"),
                List.of(new de.bsommerfeld.wsbg.terminal.instruments.SecTickerSource(direct),
                        new de.bsommerfeld.wsbg.terminal.instruments.XetraSource(direct),
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
     * <p><b>The anonymous {@code .json} delegate rides the shared
     * {@link WebFetcher} chain</b> ({@code browser → direct}), so the old separate
     * "browser" and "JSON" delegates collapse into one: a request prefers the
     * embedded-Chromium transport (real browser session + cookies, served like an
     * ordinary browser on the {@code .json} path that 403s a bare client) and
     * falls back to plain HTTP <em>per request</em> — more resilient than the old
     * 600 s source-level re-probe. Browser is the de-facto primary on a normal
     * install; OAuth only probes true once a client ID is configured; RSS stays
     * the always-reachable floor.
     *
     * <p>Reddit's volume limit is per-IP regardless of TLS fingerprint, so the
     * browser and direct transports share one budget: the generous browser rate
     * when the joker leads the chain, else the conservative anonymous rate.
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
        RedditScraper anon = new RedditScraper(repository, null, webFetcher, anonLimiter);

        RssRedditScraper rss = new RssRedditScraper(repository, config, null);

        LOG.info("Reddit source: dynamic fallback chain [OAuth → {} → RSS]", webFetcher.name());
        return new FallbackRedditSource(List.of(oauth, anon, rss), probeSub, 600L, eventBus);
    }
}
