package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks the amount of time the user spends in the application,
 * increments the opening count, and records the initial start timestamp.
 */
@Singleton
public class UserSessionTracker {

    private static final Logger LOG = LoggerFactory.getLogger(UserSessionTracker.class);
    private final ScheduledExecutorService executor;

    @Inject
    public UserSessionTracker(GlobalConfig config) {
        UserConfig userConfig = config.getUser();
        userConfig.setOpenCount(userConfig.getOpenCount() + 1);
        if (userConfig.getFirstStartTimestamp() == 0) {
            userConfig.setFirstStartTimestamp(System.currentTimeMillis());
        }
        
        try {
            config.save();
        } catch (Exception e) {
            LOG.error("Failed to save initial session stats", e);
        }

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-tracker");
            t.setDaemon(true);
            return t;
        });
        
        this.executor.scheduleAtFixedRate(() -> {
            try {
                userConfig.setOpenMinutes(userConfig.getOpenMinutes() + 1);
                config.save();
            } catch (Exception e) {
                LOG.error("Failed to save open minutes", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
