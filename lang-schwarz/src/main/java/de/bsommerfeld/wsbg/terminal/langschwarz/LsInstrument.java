package de.bsommerfeld.wsbg.terminal.langschwarz;

/**
 * One Lang &amp; Schwarz instrument resolved from a name search: the numeric
 * {@code instrumentId} the chart endpoint needs, plus the {@code isin} (which
 * the Deutsche Börse fallback is keyed on) and the venue's display name.
 */
public record LsInstrument(long instrumentId, String isin, String displayName) {}
