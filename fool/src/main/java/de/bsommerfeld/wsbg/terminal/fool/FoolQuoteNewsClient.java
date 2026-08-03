package de.bsommerfeld.wsbg.terminal.fool;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import java.util.List;

/**
 * The Motley Fool's per-instrument QUOTE PAGE as its own source — the leg that
 * reaches weeks back where the chronological sitemap window is two days wide.
 *
 * <p>Why a second source instead of a second leg inside
 * {@link FoolNewsClient#newsFor}: reach is declared per SOURCE
 * ({@link #dossierOnly()}), and these two legs do not have the same reach. The
 * pool answers "what did Fool publish about this instrument TODAY" — a wire
 * question. The quote page answers "what has Fool ever written about it" —
 * archive depth a dossier weighs and a headline drowns in (2026-08-03).
 *
 * <p>No own fetching, no own cache: it asks the one {@link FoolNewsClient}
 * singleton, so venue resolution, the quote-page cache and the robots verdict
 * documented there all stay in one place. The aggregator dedupes what both
 * legs carry, by id and by story key.
 */
@Singleton
public class FoolQuoteNewsClient implements NewsSource {

    private final FoolNewsClient fool;

    @Inject
    public FoolQuoteNewsClient(FoolNewsClient fool) {
        this.fool = fool;
    }

    @Override
    public String sourceName() {
        return "fool-quote";
    }

    /** Archive depth per instrument - the deep dive's leg, never the wire's. */
    @Override
    public boolean dossierOnly() {
        return true;
    }

    /** Symbol-addressed only: the quote page IS a path segment per ticker. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) return List.of();
        return fool.quoteNews(symbol.trim(), limit);
    }
}
