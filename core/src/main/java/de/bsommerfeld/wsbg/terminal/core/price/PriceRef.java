package de.bsommerfeld.wsbg.terminal.core.price;

/**
 * What a {@link PriceSource} needs to look an instrument up. Different venues key
 * on different things, so all three are carried and each source uses what it can:
 *
 * <ul>
 *   <li><b>name</b> — the subject's canonical name (e.g. "NVIDIA Corp."); Lang &amp;
 *       Schwarz searches by it. May be null for a related instrument we only know by ticker.</li>
 *   <li><b>ticker</b> — the validated Yahoo symbol (e.g. "NVDA"); NASDAQ + Yahoo key on it.</li>
 *   <li><b>isin</b> — populated once L&amp;S resolves the instrument; Tradegate keys on it.
 *       Usually null on the way in.</li>
 * </ul>
 */
public record PriceRef(String name, String ticker, String isin) {

    public PriceRef(String name, String ticker) {
        this(name, ticker, null);
    }

    /** A copy with the ISIN filled in (L&S hands it down so Tradegate can use it). */
    public PriceRef withIsin(String resolvedIsin) {
        return new PriceRef(name, ticker, resolvedIsin);
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
}
