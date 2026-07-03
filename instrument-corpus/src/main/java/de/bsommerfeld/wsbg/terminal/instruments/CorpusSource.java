package de.bsommerfeld.wsbg.terminal.instruments;

import java.util.List;

/**
 * One upstream feed of listed instruments. Sources are deliberately pluggable
 * (the same seam pattern as {@code NewsSource}): today SEC (US listings, daily
 * official JSON) and the locally-learned wallstreet-online ISIN memory; a
 * Deutsche Börse/XETRA instruments feed is the known open third leg (its CSV
 * hides behind unstable blob URLs — needs a devtools session to map, like the
 * XETRA news client).
 */
public interface CorpusSource {

    /** Short stable identifier stored on each entry (e.g. {@code "sec"}). */
    String name();

    /**
     * Fetches the source's full instrument list. May be slow (network); called
     * off the hot path by the corpus refresh. Throws on hard failure — the
     * corpus keeps its previous snapshot then.
     */
    List<InstrumentEntry> fetch() throws Exception;
}
