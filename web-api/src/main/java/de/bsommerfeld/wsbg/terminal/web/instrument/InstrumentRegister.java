package de.bsommerfeld.wsbg.terminal.web.instrument;

/**
 * The house register: turns whatever a caller typed ("Apple", "AAPL",
 * "US0378331005") into every key it can vouch for. Implementations sit on the
 * instrument corpus / resolver machinery; the contract stays blind to how.
 * Resolution must never invent — a key the register cannot vouch for stays
 * empty, and a query it cannot place at all comes back as
 * {@link ResolvedInstrument#ofName(String)} of the raw query.
 */
public interface InstrumentRegister {

    ResolvedInstrument resolve(String query);
}
