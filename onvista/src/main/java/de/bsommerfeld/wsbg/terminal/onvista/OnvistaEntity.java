package de.bsommerfeld.wsbg.terminal.onvista;

/**
 * One resolved onvista instrument address — the single key every {@code /v1}
 * and {@code /v2} path is built from.
 *
 * <p>onvista addresses an instrument by the PAIR {@code entityType} +
 * {@code entityValue} ({@code STOCK}/{@code FUND}/{@code INDEX}/…), never by
 * ISIN. The same {@code entityValue} serves the {@code /v1/instruments/*} and
 * the {@code /v1/stocks/*} families — probed 2026-08-02: SAP (DE0007164600)
 * is {@code STOCK/82849} in BOTH, and {@code 86627} — which an earlier note
 * suspected to be a second SAP id — is Apple's. There is no per-family id
 * split; there is only the danger of carrying a hand-copied id from the wrong
 * instrument, which is exactly why {@link OnvistaEntityResolver} is the only
 * sanctioned way to obtain one.
 *
 * @param entityType   onvista entity type, e.g. {@code STOCK}
 * @param entityValue  the numeric id as a string (never parsed to a long —
 *                     onvista hands it out as text and uses it in paths)
 * @param name         short display name, may be null
 * @param isin         the ISIN onvista matched, may be null
 * @param wkn          German WKN, may be null
 * @param symbol       trading symbol, may be null
 */
public record OnvistaEntity(String entityType, String entityValue,
        String name, String isin, String wkn, String symbol) {

    /** Path segment pair, e.g. {@code "STOCK/82849"}. */
    public String pathPair() {
        return entityType + "/" + entityValue;
    }
}
