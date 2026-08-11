package de.bsommerfeld.wsbg.terminal.web.source;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import java.util.List;

/**
 * An INSTRUMENT SOURCE: answers for one resolved instrument, live. The
 * gateway resolves the caller's query against the register first
 * ({@code searchInstrument}), so every implementation receives ALL keys that
 * exist — ISIN, home symbol, canonical name — and takes the ones its backend
 * can address. A source whose key is missing on the instrument returns empty
 * instead of guessing.
 */
public interface InstrumentSource extends WebSource {

    /**
     * Articles referencing the instrument, most-relevant first.
     *
     * @return matching articles, or an empty list — never {@code null}
     */
    List<Article> newsFor(ResolvedInstrument instrument, int limit) throws Exception;
}
