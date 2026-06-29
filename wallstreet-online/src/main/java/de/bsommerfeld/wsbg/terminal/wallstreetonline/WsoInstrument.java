package de.bsommerfeld.wsbg.terminal.wallstreetonline;

/**
 * A wallstreet-online search hit: the ISIN + WKN of the German listing and its
 * display name. The ISIN is the join key the Deutsche Börse price source uses and
 * the cross-check value for the Lang &amp; Schwarz name-pick.
 */
public record WsoInstrument(String isin, String wkn, String name) {}
