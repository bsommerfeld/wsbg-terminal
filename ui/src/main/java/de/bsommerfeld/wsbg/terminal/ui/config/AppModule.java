package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import de.bsommerfeld.jshepherd.core.ConfigurationLoader;

import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.ApplicationMode;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Guice Module for UI and Application wiring.
 */
public class AppModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(AppModule.class);

    @Override
    protected void configure() {
        // Load Configuration
        try {
            Path appDataDir = de.bsommerfeld.wsbg.terminal.core.util.StorageUtils.getAppDataDir("wsbg-terminal");
            if (!Files.exists(appDataDir)) {
                Files.createDirectories(appDataDir);
            }
            Path configPath = appDataDir.resolve("config.toml");
            LOG.info("Loading Configuration from: {}", configPath.toAbsolutePath());

            GlobalConfig config = ConfigurationLoader
                    .from(configPath)
                    .withComments()
                    .load(GlobalConfig::new);

            // Bind Configuration instance so it can be injected
            bind(GlobalConfig.class).toInstance(config);

            // Bind Sub-Configs for convenience

            bind(AgentConfig.class).toInstance(config.getAgent());

            // --- MODE SWITCHING (PROD vs TEST) ---
            ApplicationMode mode = ApplicationMode.get();
            LOG.info("Application Mode initialized: {}", mode);

            if (mode == ApplicationMode.TEST) {
                // TEST MODE: Stub Database & Scraper (No Data, No Save)
                bind(de.bsommerfeld.wsbg.terminal.db.DatabaseService.class)
                        .to(de.bsommerfeld.wsbg.terminal.db.TestDatabaseService.class);
                bind(de.bsommerfeld.wsbg.terminal.reddit.RedditScraper.class)
                        .to(de.bsommerfeld.wsbg.terminal.reddit.TestRedditScraper.class);
            } else {
                // PROD MODE: Real Database Logic
                bind(de.bsommerfeld.wsbg.terminal.db.DatabaseService.class)
                        .to(de.bsommerfeld.wsbg.terminal.db.SqlDatabaseService.class);
                // RedditScraper binds to itself via JIT (@Singleton on class)
            }

            // Start Passive Monitor
            bind(de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService.class).asEagerSingleton();

        } catch (Exception e) {
            // Check if it's a critical startup failure or we can proceed with defaults
            // For now, throw generic to fail fast as Config is vital
            throw new RuntimeException("Failed to load Application Configuration", e);
        }
    }
}
