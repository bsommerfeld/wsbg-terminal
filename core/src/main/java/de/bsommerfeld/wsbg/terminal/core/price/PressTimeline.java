package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * The dated per-ticker press timeline — headline dates spanning MONTHS, the
 * "Was war" material a 30-day news window cannot carry (how did the name get
 * to −45 % over 52 weeks?). Entries arrive newest-first as the provider
 * delivers them; titles are context material, deliberately not sources.
 */
public record PressTimeline(String symbol, List<Entry> entries) {

    /**
     * One dated headline. {@code dateIso} is always a full ISO date;
     * {@code publisher} is the provider's host token ("finance.yahoo.com"),
     * nullable when the provider carried none.
     */
    public record Entry(String dateIso, String title, String publisher) {}
}
