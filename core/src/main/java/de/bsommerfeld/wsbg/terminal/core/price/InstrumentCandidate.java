package de.bsommerfeld.wsbg.terminal.core.price;

/**
 * One typed instrument candidate from a venue's name search — the raw facts the
 * identity desk lays on the table for the judge. Deliberately venue-shaped, not
 * Yahoo-shaped: the category vocabulary is the venue's own ({@code STK}, {@code ETF},
 * {@code CUR}, {@code RES}, …) because the venue that carries the candidate is also
 * the venue that will price it.
 *
 * @param venue        the venue's short name (e.g. "L&amp;S")
 * @param venueId      the venue-internal instrument id (what its quote endpoint keys on)
 * @param isin         the ISIN, or the venue's pseudo-ISIN for unlisted notations
 *                     (e.g. an L&amp;S crypto notation), never null but may be blank
 * @param wkn          the WKN when the venue carries one, may be blank
 * @param displayName  the venue's display name for the listing
 * @param category     the venue's category symbol (L&amp;S: {@code STK}, {@code ETF},
 *                     {@code CUR}, {@code RES}, {@code FONDS}, …), may be blank
 * @param categoryName the human-readable category (L&amp;S: "Aktie", "Währung", …), may be blank
 */
public record InstrumentCandidate(String venue, long venueId, String isin, String wkn,
        String displayName, String category, String categoryName) {}
