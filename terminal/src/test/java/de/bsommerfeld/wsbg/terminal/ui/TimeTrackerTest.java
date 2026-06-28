package de.bsommerfeld.wsbg.terminal.ui;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

/**
 * Unit tests for {@link TimeTracker}. The timing-dependent scheduler is never
 * exercised directly: the crediting logic is driven through the package-private
 * {@link TimeTracker#creditAndPersist(long)} seam with explicit elapsed values,
 * and the pure helper ({@code creditedMillis}) is tested in isolation.
 * Persistence is stubbed so no real config file is touched.
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

    // ---- pure helper --------------------------------------------------------

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

    // ---- crediting ----------------------------------------------------------

    @Test
    @DisplayName("accumulates credited deltas into the persisted total")
    void accumulates() {
        tracker = new TimeTracker(stubConfig());

        tracker.creditAndPersist(60_000L);
        tracker.creditAndPersist(60_000L);

        assertEquals(120_000L, tracker.getActiveMillis());
    }

    @Test
    @DisplayName("a sleep-sized delta is clamped to the cap, not credited in full")
    void sleepDeltaClamped() {
        GlobalConfig config = stubConfig();
        config.getUser().setActiveMillis(11 * HOUR_MS);
        tracker = new TimeTracker(config);

        tracker.creditAndPersist(8 * HOUR_MS); // machine slept; clamped to the cap

        assertEquals(11 * HOUR_MS + FLUSH_CAP_MS, tracker.getActiveMillis());
    }
}
