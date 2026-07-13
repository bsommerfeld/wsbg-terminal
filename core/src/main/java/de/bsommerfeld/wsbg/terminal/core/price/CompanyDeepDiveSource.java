package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * Deep company lookup by ISIN (see {@link CompanyDeepDive}) — the heavyweight
 * one-shot companion to the slim {@link InstrumentFactsSource}: fetched on
 * demand when a full research report is generated, never on a cadence. Bound
 * in production (Consorsbank), optionally injected, absent in tests.
 */
public interface CompanyDeepDiveSource {

    /**
     * The deep company record for {@code isin}, or empty when the ISIN is
     * unknown, not a covered stock (ETF/fund), or the source failed. Partial
     * data is fine — a record with only some sub-blocks filled is present.
     */
    Optional<CompanyDeepDive> deepDiveByIsin(String isin);
}
