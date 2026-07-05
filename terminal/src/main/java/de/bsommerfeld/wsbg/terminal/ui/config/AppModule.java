package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import de.bsommerfeld.jshepherd.core.ConfigurationLoader;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Root Guice module. Loads the configuration, then delegates every feature's
 * wiring to a focused {@code install(...)} module so each concern lives next to
 * itself:
 * <ul>
 *   <li>{@link ConfigModule} — config instance binds + the wheel-scroll seam;</li>
 *   <li>{@link NetModule} — the shared WebFetcher, instrument corpus, RedditSource;</li>
 *   <li>{@link NewsSourceModule} — the {@code Set<NewsSource>} multibinding;</li>
 *   <li>{@link AgentPipelineModule} — the editorial quartet + price chain (ordered);</li>
 *   <li>{@link PublisherModule} — the Java→page publishers;</li>
 *   <li>{@link BridgeModule} — the inbound page→Java bridges.</li>
 * </ul>
 *
 * <p><b>Install order matters:</b> the eager singletons across
 * {@link AgentPipelineModule} → {@link PublisherModule} → {@link BridgeModule} are
 * instantiated in that sequence, preserving the boot ordering the pipeline relies
 * on (see {@link AgentPipelineModule}).
 */
public class AppModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(AppModule.class);

    @Override
    protected void configure() {
        GlobalConfig config = loadConfig();

        install(new ConfigModule(config));
        install(new NetModule());
        install(new NewsSourceModule());
        // RedditSource is assembled in NetModule.provideRedditSource() — it
        // auto-selects a working path (OAuth → .json → RSS) at runtime, so there
        // is no configured source and every install finds its own way.
        install(new AgentPipelineModule());
        install(new PublisherModule());
        install(new BridgeModule());
    }

    /** Bootstraps the app-data dir, drops the legacy DB, and loads {@code config.toml}. */
    private GlobalConfig loadConfig() {
        try {
            Path appDataDir = StorageUtils.getAppDataDir();
            if (!Files.exists(appDataDir)) {
                Files.createDirectories(appDataDir);
            }
            removeLegacyDatabase(appDataDir);

            Path configPath = appDataDir.resolve("config.toml");
            LOG.info("Loading Configuration from: {}", configPath.toAbsolutePath());

            return ConfigurationLoader
                    .from(configPath)
                    .withComments()
                    .load(GlobalConfig::new);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Application Configuration", e);
        }
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
