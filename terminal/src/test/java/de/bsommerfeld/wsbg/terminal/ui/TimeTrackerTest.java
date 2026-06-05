package de.bsommerfeld.wsbg.terminal.ui;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

/**
 * Unit tests for {@link TimeTracker}. The timing-dependent scheduler is never
 * exercised directly: the crediting logic is driven through the package-private
 * {@link TimeTracker#creditAndPersist(long)} seam with explicit elapsed values,
 * and the pure helpers ({@code creditedMillis}, {@code thresholdReached}) are
 * tested in isolation. Persistence is stubbed so no real config file is touched.
 */
class TimeTrackerTest {

    private static final long HOUR_MS = 3_600_000L;
    private static final long FLUSH_CAP_MS = 120_000L; // 2 × 60 s interval

    private TimeTracker tracker;

    /** A GlobalConfig whose save() is a no-op, so no real config file is touched. */
    private static GlobalConfig stubConfig() {
        GlobalConfig config = spy(new GlobalConfig());
        doNothing().when(config).save();
        return config;
    }

    @AfterEach
    void tearDown() {
        if (tracker != null) tracker.shutdown();
    }

    // ---- pure helpers -------------------------------------------------------

    @Nested
    @DisplayName("creditedMillis")
    class CreditedMillis {

        @Test
        @DisplayName("credits the full delta when below the sleep cap")
        void normalDelta() {
            assertEquals(60_000L, TimeTracker.creditedMillis(60_000L, FLUSH_CAP_MS));
        }

        @Test
        @DisplayName("clamps a sleep-sized gap to the cap")
        void sleepGapClamped() {
            long overnight = 8 * HOUR_MS;
            assertEquals(FLUSH_CAP_MS, TimeTracker.creditedMillis(overnight, FLUSH_CAP_MS));
        }

        @Test
        @DisplayName("never credits a zero or negative delta")
        void zeroOrNegative() {
            assertEquals(0L, TimeTracker.creditedMillis(0L, FLUSH_CAP_MS));
            assertEquals(0L, TimeTracker.creditedMillis(-5_000L, FLUSH_CAP_MS));
        }
    }

    @Nested
    @DisplayName("thresholdReached")
    class ThresholdReached {

        @Test
        @DisplayName("false below, true at and above the 12h bar")
        void boundary() {
            assertFalse(TimeTracker.thresholdReached(12 * HOUR_MS - 1, 12.0));
            assertTrue(TimeTracker.thresholdReached(12 * HOUR_MS, 12.0));
            assertTrue(TimeTracker.thresholdReached(13 * HOUR_MS, 12.0));
        }

        @Test
        @DisplayName("hours <= 0 unlocks immediately, even at zero active time")
        void disabledGate() {
            assertTrue(TimeTracker.thresholdReached(0L, 0.0));
            assertTrue(TimeTracker.thresholdReached(0L, -1.0));
        }
    }

    // ---- construction-time session bookkeeping ------------------------------

    @Test
    @DisplayName("start bumps open-count and stamps the first-ever start once")
    void recordsSessionStart() {
        GlobalConfig config = stubConfig();
        UserConfig user = config.getUser();
        user.setOpenCount(4);
        tracker = new TimeTracker(config);

        assertEquals(5, user.getOpenCount(), "open-count increments each start");
        assertTrue(user.getFirstStartTimestamp() > 0, "first start gets stamped");
    }

    @Test
    @DisplayName("an existing first-start timestamp is preserved, not overwritten")
    void preservesFirstStart() {
        GlobalConfig config = stubConfig();
        config.getUser().setFirstStartTimestamp(1_700_000_000_000L);
        tracker = new TimeTracker(config);

        assertEquals(1_700_000_000_000L, config.getUser().getFirstStartTimestamp());
    }

    @Test
    @DisplayName("starts already unlocked when stored active time is past the bar")
    void unlockedFromStoredState() {
        GlobalConfig config = stubConfig();
        config.getUser().setActiveMillis(13 * HOUR_MS);
        tracker = new TimeTracker(config);

        assertTrue(tracker.isDonationUnlocked());
    }

    @Test
    @DisplayName("starts locked for a fresh user")
    void lockedFromFreshState() {
        tracker = new TimeTracker(stubConfig());
        assertFalse(tracker.isDonationUnlocked());
    }

    // ---- crediting + unlock crossing ----------------------------------------

    @Test
    @DisplayName("accumulates credited deltas into the persisted total")
    void accumulates() {
        tracker = new TimeTracker(stubConfig());

        tracker.creditAndPersist(60_000L);
        tracker.creditAndPersist(60_000L);

        assertEquals(120_000L, tracker.getActiveMillis());
    }

    @Test
    @DisplayName("crossing the bar flips unlocked and fires onUnlock exactly once")
    void unlockCrossingFiresOnce() {
        GlobalConfig config = stubConfig();
        config.getUser().setActiveMillis(12 * HOUR_MS - FLUSH_CAP_MS); // one cap shy
        tracker = new TimeTracker(config);
        assertFalse(tracker.isDonationUnlocked());

        AtomicInteger fired = new AtomicInteger();
        tracker.onUnlock(fired::incrementAndGet);

        tracker.creditAndPersist(FLUSH_CAP_MS);   // lands exactly on the bar
        assertTrue(tracker.isDonationUnlocked());
        assertEquals(1, fired.get());

        tracker.creditAndPersist(FLUSH_CAP_MS);   // already unlocked: no re-fire
        assertEquals(1, fired.get());
    }

    // ---- snooze / active layer ---------------------------------------------

    @Test
    @DisplayName("active layer follows the time gate when not snoozed")
    void activeWhenUnlockedAndNotSnoozed() {
        GlobalConfig config = stubConfig();
        config.getUser().setActiveMillis(13 * HOUR_MS);
        tracker = new TimeTracker(config);

        assertTrue(tracker.isDonationUnlocked());
        assertTrue(tracker.isDonationActive());
    }

    @Test
    @DisplayName("a future snooze suppresses the active layer even when time-unlocked")
    void snoozeSuppressesActiveLayer() {
        GlobalConfig config = stubConfig();
        config.getUser().setActiveMillis(13 * HOUR_MS);
        config.getUser().setDonationSnoozeUntil(System.currentTimeMillis() + HOUR_MS);
        tracker = new TimeTracker(config);

        assertTrue(tracker.isDonationUnlocked(), "time gate is still crossed");
        assertFalse(tracker.isDonationActive(), "but snooze suppresses the banner");
    }

    @Test
    @DisplayName("snoozeDonation() stamps a far-future suppression and persists it")
    void snoozeStampsFutureAndSuppresses() {
        GlobalConfig config = stubConfig();
        config.getUser().setActiveMillis(13 * HOUR_MS);
        tracker = new TimeTracker(config);
        assertTrue(tracker.isDonationActive());

        long before = System.currentTimeMillis();
        tracker.snoozeDonation();

        assertTrue(config.getUser().getDonationSnoozeUntil() > before + 6L * 24 * HOUR_MS,
                "snooze reaches days into the future");
        assertFalse(tracker.isDonationActive(), "active layer is suppressed after snooze");
    }

    @Test
    @DisplayName("an expired snooze no longer suppresses the active layer")
    void expiredSnoozeIsIgnored() {
        GlobalConfig config = stubConfig();
        config.getUser().setActiveMillis(13 * HOUR_MS);
        config.getUser().setDonationSnoozeUntil(System.currentTimeMillis() - HOUR_MS);
        tracker = new TimeTracker(config);

        assertTrue(tracker.isDonationActive());
    }

    @Test
    @DisplayName("a sleep-sized delta is clamped, so overnight suspend can't unlock")
    void sleepDoesNotUnlock() {
        GlobalConfig config = stubConfig();
        config.getUser().setActiveMillis(11 * HOUR_MS); // close, but > 1 cap to cross
        tracker = new TimeTracker(config);

        tracker.creditAndPersist(8 * HOUR_MS); // machine slept; clamped to the cap

        assertEquals(11 * HOUR_MS + FLUSH_CAP_MS, tracker.getActiveMillis());
        assertFalse(tracker.isDonationUnlocked());
    }
}
