package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.jshepherd.core.ConfigurationLoader;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;
import de.bsommerfeld.wsbg.terminal.embedding.OllamaEmbeddingService;
import de.bsommerfeld.wsbg.terminal.agent.ClusterRebalancer;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdMonitorService;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.FallbackRedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.JdkRedditTransport;
import de.bsommerfeld.wsbg.terminal.reddit.OAuthRedditTransport;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.RssRedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.TokenBucketRateLimiter;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetchChain;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.TimeTracker;
import de.bsommerfeld.wsbg.terminal.ui.net.CefRedditTransport;
import de.bsommerfeld.wsbg.terminal.ui.net.CefWebFetcher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.ArchiveQueryBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.CommandBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.DonationGatePublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.EurUsdPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.FjNewsPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.HeadlinePublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.MarketHoursPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.MarketMoodPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.RedditHealthPublisher;
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
            // change in the aggregator. Yahoo is the first (pull-by-symbol)
            // source; the RSS feed index joins later. Architectural wiring only
            // — nothing consumes the aggregator yet, so behaviour is unchanged.
            Multibinder<NewsSource> newsSources =
                    Multibinder.newSetBinder(binder(), NewsSource.class);
            newsSources.addBinding().to(YahooFinanceClient.class);

            // RedditSource is assembled in provideRedditSource() below — it
            // auto-selects a working path (OAuth → .json → RSS) at runtime, so
            // there is no configured source and every install finds its own way.

            // AgentCoordinator must be eager so it subscribes to ClusterRegistry
            // changes before PassiveMonitorService starts emitting them.
            bind(AgentCoordinator.class).asEagerSingleton();
            bind(ClusterRebalancer.class).asEagerSingleton();
            bind(PassiveMonitorService.class).asEagerSingleton();
            // TimeTracker must be eager so it starts its start/interval/stop
            // checkpointing at boot; DonationGatePublisher reads it to gate the
            // footer donation banner.
            bind(TimeTracker.class).asEagerSingleton();

            // Publishers must be eager so they subscribe to the event bus
            // / hub before any data flows. CommandBridge wires inbound
            // window-control messages.
            bind(HeadlinePublisher.class).asEagerSingleton();
            bind(FjNewsPublisher.class).asEagerSingleton();
            bind(MarketHoursPublisher.class).asEagerSingleton();
            bind(RedditHealthPublisher.class).asEagerSingleton();
            bind(DonationGatePublisher.class).asEagerSingleton();
            // EurUsdMonitorService must come before EurUsdPublisher so the
            // publisher can register its listener against a running poll loop.
            bind(EurUsdMonitorService.class).asEagerSingleton();
            bind(EurUsdPublisher.class).asEagerSingleton();
            bind(CommandBridge.class).asEagerSingleton();
            // Archive layer: search/byTicker queries, the persisted watchlist,
            // and the daily market-mood badge ("54% BEARISH") — all reading the
            // permanent HeadlineArchive / the archive-seeded wire window. Eager
            // so their hub.on(...) handlers exist before the first page loads.
            bind(ArchiveQueryBridge.class).asEagerSingleton();
            bind(WatchlistBridge.class).asEagerSingleton();
            bind(MarketMoodPublisher.class).asEagerSingleton();

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
     * Builds the {@link RedditSource}: a {@link FallbackRedditSource} that
     * auto-selects a working path at runtime (OAuth → browser → anonymous .json
     * → RSS). Every JSON delegate shares one repository but carries its own
     * transport + rate limiter — OAuth gets the fast budget, the browser and
     * anonymous {@code .json} the throttled one; RSS owns its own internally.
     * Consumers only see {@link RedditSource} and never learn which path
     * answered.
     *
     * <p>The <b>browser</b> delegate ({@link CefRedditTransport}) fetches the
     * full-fidelity {@code .json} through the embedded Chromium runtime, so it
     * carries a real browser TLS fingerprint + cookies and clears the bot
     * detection that 403s the plain {@code .json} path. It sits just under OAuth
     * (which only probes true once a client ID is configured) and above the
     * legacy anonymous {@code .json}, making it the de-facto primary on a normal
     * install while RSS stays the always-reachable floor.
     */
    /**
     * The generic fetch strategy any source can consume: a {@link WebFetchChain}
     * resolved in order. When {@code yahoo.browser-fetch-enabled} is on, the
     * browser "joker" ({@link CefWebFetcher} — real fingerprint + cookies, clears
     * bot-walls) leads, with plain {@link DirectWebFetcher} as the fallback;
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

    @Provides
    @Singleton
    RedditSource provideRedditSource(RedditRepository repository,
            ApplicationEventBus eventBus, GlobalConfig config, CefHost cefHost) {
        RedditConfig rc = config.getReddit();
        String probeSub = rc.getSubreddits().isEmpty()
                ? "wallstreetbetsGER" : rc.getSubreddits().get(0);

        RedditScraper oauth = new RedditScraper(repository, eventBus,
                new OAuthRedditTransport(config),
                new TokenBucketRateLimiter(rc.getOauthRateLimitBurst(),
                        rc.getOauthRateLimitRequestsPerSecond()));
        // Browser-driven .json: a real browser session (cookies + fingerprint,
        // challenge solved), so it runs on its OWN generous rate limit, NOT the
        // anonymous 0.15/s bot-detection budget — full-fidelity deep fetches stay
        // intact but stream in fast instead of one every ~6.6s.
        RedditScraper browser = new RedditScraper(repository, eventBus,
                new CefRedditTransport(cefHost, probeSub),
                new TokenBucketRateLimiter(rc.getBrowserRateLimitBurst(),
                        rc.getBrowserRateLimitRequestsPerSecond()));
        RedditScraper json = new RedditScraper(repository, eventBus,
                new JdkRedditTransport(),
                new TokenBucketRateLimiter(rc.getRateLimitBurst(),
                        rc.getRateLimitRequestsPerSecond()));
        RssRedditScraper rss = new RssRedditScraper(repository, config, eventBus);

        LOG.info("Reddit source: dynamic fallback chain [OAuth → browser → JSON → RSS]");
        return new FallbackRedditSource(List.of(oauth, browser, json, rss), probeSub, 600L);
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
