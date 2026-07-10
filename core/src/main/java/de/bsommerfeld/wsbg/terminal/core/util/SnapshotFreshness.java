package de.bsommerfeld.wsbg.terminal.core.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The one freshness rule for the on-disk session snapshots (Reddit + agent):
 * a snapshot is restored when it is from TODAY (same local calendar day) OR
 * still inside the short TTL. The day window keeps the day's context alive
 * across any restart — the evening Wetterbericht covers the whole day, so the
 * in-memory session state must be holdable for a day ("alles was nicht von
 * heute ist, weg"). The TTL stays as the cross-midnight grace (a quick restart
 * at 00:05 keeps yesterday-23:50 state) and as the disable switch ({@code <= 0}).
 * Ghost-risk from day-old posts is bounded by the normal thread retention,
 * which ages restored threads out just like live ones.
 */
public final class SnapshotFreshness {

    private SnapshotFreshness() {
    }

    /** True when the snapshot may be restored under the day-or-TTL rule. */
    public static boolean isFresh(long savedAtEpochSeconds, long ttlMinutes) {
        if (ttlMinutes <= 0) return false;
        long nowSeconds = Instant.now().getEpochSecond();
        long ageMinutes = (nowSeconds - savedAtEpochSeconds) / 60;
        if (ageMinutes <= ttlMinutes) return true;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate savedDay = LocalDate.ofInstant(Instant.ofEpochSecond(savedAtEpochSeconds), zone);
        return savedDay.equals(LocalDate.now(zone));
    }
}
