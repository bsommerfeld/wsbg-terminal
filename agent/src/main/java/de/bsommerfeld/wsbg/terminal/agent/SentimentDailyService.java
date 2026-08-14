package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BackgroundThreads;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.SentimentDailyFolder;
import de.bsommerfeld.wsbg.terminal.db.SubjectSentimentDailyArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the daily sentiment sheet current: folds the permanent headline
 * archive into per-subject day-sheets ({@link SentimentDailyFolder}) once at
 * startup (catching up every complete day the archive is missing) and then
 * every few hours (which folds yesterday shortly after midnight). Purely
 * deterministic — no model call, no network; the fold's idempotent identity
 * makes every re-run free.
 */
@Singleton
public class SentimentDailyService {

    private static final Logger LOG = LoggerFactory.getLogger(SentimentDailyService.class);

    /** The app's home zone — where a trading day (and the room's day) ends. */
    static final ZoneId HOME_ZONE = ZoneId.of("Europe/Berlin");

    /** Re-fold cadence — coarse on purpose; a day only completes once per day. */
    private static final long FOLD_INTERVAL_HOURS = 6;

    private final HeadlineArchive headlineArchive;
    private final SubjectSentimentDailyArchive archive;
    private ScheduledExecutorService scheduler;

    @Inject
    public SentimentDailyService(HeadlineArchive headlineArchive,
            SubjectSentimentDailyArchive archive) {
        this.headlineArchive = headlineArchive;
        this.archive = archive;
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                BackgroundThreads.single("sentiment-daily"));
        scheduler.scheduleAtFixedRate(this::foldSafely, 0, FOLD_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    public synchronized void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
    }

    private void foldSafely() {
        try {
            int appended = SentimentDailyFolder.foldMissing(headlineArchive.all(), archive,
                    HOME_ZONE, LocalDate.now(HOME_ZONE));
            if (appended > 0) {
                LOG.info("[SENTIMENT] daily fold: {} new day-sheet(s), archive now {}.",
                        appended, archive.size());
            }
        } catch (Exception e) {
            LOG.warn("[SENTIMENT] daily fold failed: {}", e.getMessage());
        }
    }
}
