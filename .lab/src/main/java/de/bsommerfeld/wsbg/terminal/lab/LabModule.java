package de.bsommerfeld.wsbg.terminal.lab;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.jshepherd.core.ConfigurationLoader;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.FallbackRedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.JdkRedditTransport;
import de.bsommerfeld.wsbg.terminal.reddit.OAuthRedditTransport;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.RssRedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Minimal Guice wiring for the {@code editorial-lab} harness.
 *
 * <p>It deliberately stands up <em>only</em> what the editorial pipeline needs —
 * the real {@code AgentBrain}, {@code ClusterEngine}, {@code ClusterRegistry},
 * {@code EditorialAgent}, repositories, and a {@link RedditSource} — and binds
 * the same {@link GlobalConfig} the production terminal reads (from the OS-native
 * app data dir, so it shares the user's Ollama models and Reddit/Yahoo settings).
 *
 * <p>Crucially it does <b>not</b> bind {@code PassiveMonitorService},
 * {@code AgentCoordinator}, or {@code ClusterRebalancer}: there is no scan loop,
 * no debounce, no 30 s rebalance. The lab drives clustering and the editorial
 * tick by hand, one thread at a time, so every step is observable.
 */
public final class LabModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(LabModule.class);

    @Override
    protected void configure() {
        try {
            Path appDataDir = StorageUtils.getAppDataDir();
            if (!Files.exists(appDataDir)) {
                Files.createDirectories(appDataDir);
            }
            Path configPath = appDataDir.resolve("config.toml");
            LOG.info("Loading configuration from: {}", configPath.toAbsolutePath());

            GlobalConfig config = ConfigurationLoader
                    .from(configPath)
                    .withComments()
                    .load(GlobalConfig::new);

            bind(GlobalConfig.class).toInstance(config);
            bind(AgentConfig.class).toInstance(config.getAgent());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration for editorial-lab", e);
        }
    }

    /**
     * The same dynamic fallback chain the terminal uses (OAuth → anonymous .json
     * → RSS), assembled identically to {@code AppModule.provideRedditSource} so
     * the lab fetches threads through whichever path actually works on this box.
     */
    @Provides
    @Singleton
    RedditSource provideRedditSource(RedditRepository repository,
            ApplicationEventBus eventBus, GlobalConfig config) {
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
     * Lab-only Yahoo client with a LONG cache TTL (1 h instead of the prod ~120 s).
     * The lab re-searches the same subjects across many runs, and Yahoo's search
     * endpoint rate-limits aggressively — a long TTL means each subject is searched
     * once per session, far gentler on Yahoo and far fewer 429s. Live price drift is
     * irrelevant when iterating on the pipeline. Prod keeps its short TTL.
     */
    @Provides
    @Singleton
    YahooFinanceClient provideYahooFinanceClient(GlobalConfig config) {
        long oneHour = 3600L;
        int timeout = Math.max(1, config.getYahoo().getRequestTimeoutSeconds());
        LOG.info("Lab Yahoo cache TTL: {}s (vs prod {}s) — gentler on the rate limiter",
                oneHour, config.getYahoo().getCacheTtlSeconds());
        return new YahooFinanceClient(timeout, oneHour);
    }
}
