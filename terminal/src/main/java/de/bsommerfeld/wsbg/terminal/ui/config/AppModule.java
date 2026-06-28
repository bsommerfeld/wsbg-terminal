package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.jshepherd.core.ConfigurationLoader;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.agent.EditorialPipeline;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;
import de.bsommerfeld.wsbg.terminal.embedding.OllamaEmbeddingService;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.core.price.PriceSource;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdMonitorService;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedMonitorService;
import de.bsommerfeld.wsbg.terminal.price.FallbackPriceSource;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.FallbackRedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.OAuthRedditFetcher;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.RssRedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.TokenBucketRateLimiter;
import de.bsommerfeld.wsbg.terminal.nasdaq.NasdaqNewsClient;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetchChain;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.TimeTracker;
import de.bsommerfeld.wsbg.terminal.ui.net.CefWebFetcher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.ArchiveQueryBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.CommandBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.DonationStatsPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.EurUsdPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.FearGreedPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.FjNewsPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.HeadlinePublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.MarketHoursPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.OsAppearancePublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.RedditHealthPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.SettingsBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.UpdateService;
import de.bsommerfeld.wsbg.terminal.ui.bridge.WatchlistBridge;
import de.bsommerfeld.wsbg.terminal.ui.scroll.PixelScaledWheelScrollPolicy;
import de.bsommerfeld.wsbg.terminal.ui.scroll.WheelScrollPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Guice Module for UI and Application wiring.
 */
public class AppModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(AppModule.class);

    @Override
    protected void configure() {
        try {
            Path appDataDir = StorageUtils.getAppDataDir();
            if (!Files.exists(appDataDir)) {
                Files.createDirectories(appDataDir);
            }
            removeLegacyDatabase(appDataDir);

            Path configPath = appDataDir.resolve("config.toml");
            LOG.info("Loading Configuration from: {}", configPath.toAbsolutePath());

            GlobalConfig config = ConfigurationLoader
                    .from(configPath)
                    .withComments()
                    .load(GlobalConfig::new);

            bind(GlobalConfig.class).toInstance(config);
            bind(AgentConfig.class).toInstance(config.getAgent());
            // Shared embedding seam (clustering, collation, …) → Ollama-backed impl.
            bind(EmbeddingService.class).to(OllamaEmbeddingService.class);

            // News sources are collected into a Set<NewsSource> (Guice
            // multibindings) so NewsAggregator can fan a query across all of
            // them; adding/dropping a source is a binding change here, never a
            // change in the aggregator. The resolver consults the aggregator
            // (forwarded via EditorialAgent), so the wire triangulates news
            // across providers instead of depending on Yahoo alone. The RSS feed
            // index joins once the finanznachrichten/FJ streams feed it.
            Multibinder<NewsSource> newsSources =
                    Multibinder.newSetBinder(binder(), NewsSource.class);
            newsSources.addBinding().to(YahooFinanceClient.class);
            newsSources.addBinding().to(NasdaqNewsClient.class);

            // RedditSource is assembled in provideRedditSource() below — it
            // auto-selects a working path (OAuth → .json → RSS) at runtime, so
            // there is no configured source and every install finds its own way.

            // EditorialPipeline (#3) owns the prep/compose/merge pools that turn
            // changed clusters into headlines; eager so its pools are up before any
            // cluster change is submitted. AgentCoordinator must be eager so it
            // subscribes to ClusterRegistry changes before PassiveMonitorService
            // starts emitting them, and routes them into the pipeline.
            bind(EditorialPipeline.class).asEagerSingleton();
            bind(AgentCoordinator.class).asEagerSingleton();
            bind(PassiveMonitorService.class).asEagerSingleton();
            // TimeTracker must be eager so it starts its start/interval/stop
            // checkpointing at boot; DonationStatsPublisher reads it for the
            // footer banner's reciprocity copy.
            bind(TimeTracker.class).asEagerSingleton();

            // Publishers must be eager so they subscribe to the event bus
            // / hub before any data flows. CommandBridge wires inbound
            // window-control messages.
            bind(HeadlinePublisher.class).asEagerSingleton();
            bind(FjNewsPublisher.class).asEagerSingleton();
            bind(MarketHoursPublisher.class).asEagerSingleton();
            bind(RedditHealthPublisher.class).asEagerSingleton();
            bind(DonationStatsPublisher.class).asEagerSingleton();
            // EurUsdMonitorService must come before EurUsdPublisher so the
            // publisher can register its listener against a running poll loop.
            bind(EurUsdMonitorService.class).asEagerSingleton();
            bind(EurUsdPublisher.class).asEagerSingleton();
            // Same ordering: the monitor's poll loop must exist before the publisher subscribes.
            bind(FearGreedMonitorService.class).asEagerSingleton();
            bind(FearGreedPublisher.class).asEagerSingleton();
            // The live price chain (L&S → Tradegate → NASDAQ → Yahoo, EUR). Optionally
            // injected into TickerResolver; Yahoo stays the search + news source.
            bind(PriceSource.class).to(FallbackPriceSource.class).in(com.google.inject.Singleton.class);
            bind(CommandBridge.class).asEagerSingleton();
            // Archive layer: search/byTicker queries + scroll-back pagination, and
            // the persisted watchlist — reading the permanent HeadlineArchive / the
            // archive-seeded wire window. Eager so their hub.on(...) handlers exist
            // before the first page loads.
            bind(ArchiveQueryBridge.class).asEagerSingleton();
            bind(WatchlistBridge.class).asEagerSingleton();
            // Settings view backend: persists the config-backed preferences
            // (headline mode, language, auto-update) and
            // pushes the current snapshot on client open.
            bind(SettingsBridge.class).asEagerSingleton();
            // Pushes the host OS dark/light appearance to the page. Needed because the
            // OSR Chromium can't see the real macOS theme, so the page's matchMedia
            // can't drive "follow system". Eager so it polls + pushes from boot.
            bind(OsAppearancePublisher.class).asEagerSingleton();
            // In-app update indicator (titlebar green button) + relaunch, the
            // counterpart to the launcher's auto-update opt-out. Eager so its
            // hub handlers + the periodic check are live from boot.
            bind(UpdateService.class).asEagerSingleton();

            // OSR wheel-scroll seam (see ui/scroll/). The off-screen browser gets
            // no native OS wheel message, so we re-scale the AWT delta: precise
            // rotation × OS lines-per-notch × scroll-speed inherits both the OS
            // speed setting and (via the sign) the OS 'natural scrolling' setting.
            // Speed + invert come from UserConfig. Block-mode (Windows page-scroll)
            // is rare; derive it from the line speed. Wrap in LoggingWheelScrollPolicy
            // to re-enable per-event diagnostics.
            double scrollSpeed = config.getUser().getScrollSpeed();
            boolean scrollInvert = config.getUser().isScrollInvert();
            bind(WheelScrollPolicy.class).toInstance(new PixelScaledWheelScrollPolicy(
                    scrollSpeed, scrollSpeed * 10.0, scrollInvert, scrollInvert));

        } catch (Exception e) {
            throw new RuntimeException("Failed to load Application Configuration", e);
        }
    }

    /**
     * The generic fetch strategy any source can consume: a {@link WebFetchChain}
     * resolved in order. When {@code yahoo.browser-fetch-enabled} is on, the
     * browser "joker" ({@link CefWebFetcher} — real browser session + cookies,
     * served like an ordinary browser) leads, with plain {@link DirectWebFetcher} as the fallback;
     * toggled off, it's direct-only. New news wires opt in just by taking a
     * {@link WebFetcher} and choosing their chain order — no per-source plumbing.
     */
    @Provides
    @Singleton
    WebFetcher provideWebFetcher(GlobalConfig config, CefHost cefHost) {
        DirectWebFetcher direct = new DirectWebFetcher();
        if (config.getYahoo().isBrowserFetchEnabled()) {
            LOG.info("WebFetcher: browser → direct (browser joker enabled)");
            // NOTE: the Yahoo origin browsers warm up lazily on first use. Eager
            // prewarming was tried and reverted — kicking it at injector time
            // forced CEF init off the EDT (before the window initializes it on the
            // EDT), which hangs on macOS. A correct prewarm must run AFTER the
            // window has brought CEF up; deferred until that hook exists.
            return new WebFetchChain(List.of(new CefWebFetcher(cefHost), direct));
        }
        LOG.info("WebFetcher: direct only (browser joker disabled)");
        return new WebFetchChain(List.of(direct));
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

    /**
     * One-time cleanup of the SQLite file left behind by previous versions.
     * The app is fully in-memory now; the file would otherwise sit unused on
     * disk and could be mistaken for an active store.
     */
    private void removeLegacyDatabase(Path appDataDir) {
        Path db = appDataDir.resolve("wsbg-terminal.db");
        if (!Files.exists(db))
            return;
        try {
            Files.delete(db);
            LOG.info("Removed legacy SQLite database at {}", db);
        } catch (Exception e) {
            LOG.warn("Could not remove legacy SQLite database at {}: {}", db, e.getMessage());
        }
    }
}
