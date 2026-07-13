package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * A provider of slow-moving instrument profile facts ({@link InstrumentFacts})
 * by ISIN. Third leg beside {@link PriceSource} (what does it cost) and
 * {@link VenueStatsSource} (what is trading): this answers "what IS this
 * company". Consumers inject it optionally; a non-stock instrument or a
 * provider miss is an {@link Optional#empty()}, never an exception. Facts
 * change on a quarters scale — poll accordingly (hours, not minutes).
 */
public interface InstrumentFactsSource {

    /** Profile facts for the stock with this ISIN, or empty (non-stock, unknown, outage). */
    Optional<InstrumentFacts> factsByIsin(String isin);

    /**
     * Profile facts for the FUND/ETF with this ISIN (see {@link FundFacts}), or
     * empty (not a fund, unknown, outage, or the provider doesn't cover funds).
     * Default empty so stock-only providers need not care.
     */
    default Optional<FundFacts> fundFactsByIsin(String isin) {
        return Optional.empty();
    }
}
