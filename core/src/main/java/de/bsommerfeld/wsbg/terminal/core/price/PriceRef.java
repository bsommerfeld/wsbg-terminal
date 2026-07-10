package de.bsommerfeld.wsbg.terminal.core.price;

/**
 * What a {@link PriceSource} needs to look an instrument up. Different venues key
 * on different things, so all are carried and each source uses what it can:
 *
 * <ul>
 *   <li><b>name</b> — the subject's canonical name (e.g. "NVIDIA Corp."); Lang &amp;
 *       Schwarz searches by it. May be null for a related instrument we only know by ticker.</li>
 *   <li><b>ticker</b> — the validated Yahoo symbol (e.g. "NVDA"); Yahoo keys on it.</li>
 *   <li><b>isin</b> — the stamped identity when the identity desk (or L&amp;S itself)
 *       pinned it. A ref with an ISIN is priced by exact lookup, never by name fuzz.</li>
 *   <li><b>venueId</b> + <b>category</b> — the identity desk's full stamp: the venue's
 *       own instrument id (its quote endpoint keys on it — no search at all) and the
 *       venue category ({@code STK}/{@code ETF}/{@code CUR}/{@code RES}, L&amp;S
 *       vocabulary). {@code 0}/blank when unstamped.</li>
 *   <li><b>venueRuledOut</b> — the desk saw the venue's candidates and struck every
 *       one: a considered "this paper does not trade there". The price chain must
 *       not re-open the question with a fuzzy NAME search (that path re-finds the
 *       wrong twin the desk just rejected); exact-ISIN lookups stay allowed.</li>
 * </ul>
 *
 * <p>The stamp is the point: identity is decided ONCE (at the desk, with all venue
 * facts on the table) and carried here, so the price chain executes the verdict
 * instead of re-deciding identity from the name string.
 */
public record PriceRef(String name, String ticker, String isin, long venueId, String category,
        boolean venueRuledOut) {

    public PriceRef(String name, String ticker) {
        this(name, ticker, null, 0, null, false);
    }

    public PriceRef(String name, String ticker, String isin) {
        this(name, ticker, isin, 0, null, false);
    }

    public PriceRef(String name, String ticker, String isin, long venueId, String category) {
        this(name, ticker, isin, venueId, category, false);
    }

    public boolean hasName() {
        return name != null && !name.isBlank();
    }

    public boolean hasTicker() {
        return ticker != null && !ticker.isBlank();
    }

    public boolean hasIsin() {
        return isin != null && !isin.isBlank();
    }

    /** True when the identity desk stamped an exact venue instrument. */
    public boolean hasVenueId() {
        return venueId > 0;
    }

    /** True when the stamped venue category is the given one (L&amp;S vocabulary). */
    public boolean isCategory(String cat) {
        return category != null && category.equalsIgnoreCase(cat);
    }
}
