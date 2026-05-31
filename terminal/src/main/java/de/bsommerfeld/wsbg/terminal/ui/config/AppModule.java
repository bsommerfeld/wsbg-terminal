package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import de.bsommerfeld.jshepherd.core.ConfigurationLoader;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.agent.ClusterRebalancer;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.ApplicationMode;
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
import de.bsommerfeld.wsbg.terminal.reddit.TestRedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.TokenBucketRateLimiter;
import de.bsommerfeld.wsbg.terminal.ui.UserSessionTracker;
import de.bsommerfeld.wsbg.terminal.ui.bridge.CommandBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.EurUsdPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.FjNewsPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.HeadlinePublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.MarketHoursPublisher;
import de.bsommerfeld.wsbg.terminal.ui.bridge.RedditHealthPublisher;
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

            ApplicationMode mode = ApplicationMode.get();
            LOG.info("Application Mode initialized: {}", mode);

            // RedditSource is assembled in provideRedditSource() below — it
            // auto-selects a working path (OAuth → .json → RSS) at runtime, so
            // there is no configured source and every install finds its own way.

            // AgentCoordinator must be eager so it subscribes to ClusterRegistry
            // changes before PassiveMonitorService starts emitting them.
            bind(AgentCoordinator.class).asEagerSingleton();
            bind(ClusterRebalancer.class).asEagerSingleton();
            bind(PassiveMonitorService.class).asEagerSingleton();
            bind(UserSessionTracker.class).asEagerSingleton();

            // Publishers must be eager so they subscribe to the event bus
            // / hub before any data flows. CommandBridge wires inbound
            // window-control messages.
            bind(HeadlinePublisher.class).asEagerSingleton();
            bind(FjNewsPublisher.class).asEagerSingleton();
            bind(MarketHoursPublisher.class).asEagerSingleton();
            bind(RedditHealthPublisher.class).asEagerSingleton();
            // EurUsdMonitorService must come before EurUsdPublisher so the
            // publisher can register its listener against a running poll loop.
            bind(EurUsdMonitorService.class).asEagerSingleton();
            bind(EurUsdPublisher.class).asEagerSingleton();
            bind(CommandBridge.class).asEagerSingleton();

        } catch (Exception e) {
            throw new RuntimeException("Failed to load Application Configuration", e);
        }
    }

    /**
     * Builds the {@link RedditSource}. In TEST mode it's the synthetic scraper;
     * otherwise it's a {@link FallbackRedditSource} that auto-selects a working
     * path at runtime (OAuth → anonymous .json → RSS). The two JSON delegates
     * share one repository but carry their own transport + rate limiter — OAuth
     * gets the fast budget, anonymous .json the throttled one; RSS owns its own
     * internally. Consumers only see {@link RedditSource} and never learn which
     * path answered.
     */
    @Provides
    @Singleton
    RedditSource provideRedditSource(RedditRepository repository,
            ApplicationEventBus eventBus, GlobalConfig config) {
        if (ApplicationMode.get() == ApplicationMode.TEST) {
            LOG.info("Reddit source: TEST (synthetic data)");
            return new TestRedditScraper(repository, config);
        }
        RedditConfig rc = config.getReddit();
        String probeSub = rc.getSubreddits().isEmpty()
                ? "wallstreetbetsGER" : rc.getSubreddits().get(0);

        RedditScraper oauth = new RedditScraper(repository, eventBus,
                new OAuthRedditTransport(config),
                new TokenBucketRateLimiter(rc.getOauthRateLimitBurst(),
                        rc.getOauthRateLimitRequestsPerSecond()));
        RedditScraper json = new RedditScraper(repository, eventBus,
                new JdkRedditTransport(),
                new TokenBucketRateLimiter(rc.getRateLimitBurst(),
                        rc.getRateLimitRequestsPerSecond()));
        RssRedditScraper rss = new RssRedditScraper(repository, config, eventBus);

        LOG.info("Reddit source: dynamic fallback chain [OAuth → JSON → RSS]");
        return new FallbackRedditSource(List.of(oauth, json, rss), probeSub, 600L);
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
