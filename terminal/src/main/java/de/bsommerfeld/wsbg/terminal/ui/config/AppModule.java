package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import de.bsommerfeld.jshepherd.core.ConfigurationLoader;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.ApplicationMode;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService;
import de.bsommerfeld.wsbg.terminal.db.SqlDatabaseService;
import de.bsommerfeld.wsbg.terminal.db.TestDatabaseService;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.TestRedditScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guice Module for UI and Application wiring.
 */
public class AppModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(AppModule.class);

    @Override
    protected void configure() {
        try {
            Path appDataDir = StorageUtils.getAppDataDir("wsbg-terminal");
            if (!Files.exists(appDataDir)) {
                Files.createDirectories(appDataDir);
            }
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

            if (mode == ApplicationMode.TEST) {
                bind(DatabaseService.class).to(TestDatabaseService.class);
                bind(RedditScraper.class).to(TestRedditScraper.class);
            } else {
                bind(DatabaseService.class).to(SqlDatabaseService.class);
            }

            bind(PassiveMonitorService.class).asEagerSingleton();

        } catch (Exception e) {
            throw new RuntimeException("Failed to load Application Configuration", e);
        }
    }
}
