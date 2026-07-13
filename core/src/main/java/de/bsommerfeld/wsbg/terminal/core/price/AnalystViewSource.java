package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * Analyst-opinion lookup by ISIN — ratings, consensus price target, upcoming
 * corporate events (see {@link AnalystView}). Same seam pattern as
 * {@link VenueStatsSource} / {@link InstrumentFactsSource}: bound in production
 * (Consorsbank), optionally injected, absent in tests.
 */
public interface AnalystViewSource {

    /**
     * The street's current view on the stock behind {@code isin}, or empty when
     * the ISIN is unknown, not a covered stock (ETF/fund), or the source failed.
     * May serve a session cache.
     */
    Optional<AnalystView> viewByIsin(String isin);

    /**
     * Fetches a fresh view past any session cache — the caller's re-poll cadence
     * (ratings and targets move daily). Defaults to {@link #viewByIsin}.
     */
    default Optional<AnalystView> refresh(String isin) {
        return viewByIsin(isin);
    }
}
