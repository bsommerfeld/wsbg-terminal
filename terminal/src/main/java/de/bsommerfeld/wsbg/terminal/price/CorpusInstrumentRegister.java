package de.bsommerfeld.wsbg.terminal.price;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import de.bsommerfeld.wsbg.terminal.web.impl.text.TextMatch;
import de.bsommerfeld.wsbg.terminal.web.instrument.InstrumentRegister;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The register door over the local instrument corpus (SEC listings + XETRA
 * full list + learned WSO ISINs). Resolution never invents: a key the corpus
 * cannot vouch for stays empty, and a query it cannot place at all echoes
 * back as name-only — partial resolution is a valid answer the pipelines
 * work with.
 *
 * <p>Vouching rules, deliberately conservative:
 * <ul>
 *   <li>an ISIN-shaped query resolves by exact ISIN scan;</li>
 *   <li>otherwise the corpus's ranked search answers, but the top hit only
 *       counts when it EARNS it — exact symbol match, or every significant
 *       word of the query appearing in the entry's name (the "Apple" →
 *       "Apple Inc." case, never the fuzzy same-named twin).</li>
 * </ul>
 */
@Singleton
public final class CorpusInstrumentRegister implements InstrumentRegister {

    private static final int SEARCH_K = 5;

    private final InstrumentCorpus corpus;

    @Inject
    public CorpusInstrumentRegister(InstrumentCorpus corpus) {
        this.corpus = corpus;
    }

    @Override
    public ResolvedInstrument resolve(String query) {
        if (query == null || query.isBlank()) return ResolvedInstrument.ofName("");
        String q = query.strip();

        Optional<Isin> asIsin = Isin.parse(q);
        if (asIsin.isPresent()) {
            String isin = asIsin.get().value();
            for (InstrumentEntry e : corpus.entries()) {
                if (isin.equalsIgnoreCase(e.isin())) return toResolved(e, q);
            }
            // The ISIN is valid even when the corpus doesn't list it — the
            // hard key is the caller's fact, only the name stays the echo.
            return new ResolvedInstrument(asIsin, Optional.empty(), q);
        }

        InstrumentEntry vouched = vouchedHit(q);
        if (vouched != null) return toResolved(vouched, q);
        return ResolvedInstrument.ofName(q);
    }

    private InstrumentEntry vouchedHit(String q) {
        String upper = q.toUpperCase(Locale.ROOT);
        Set<String> queryWords = TextMatch.significantWords(q);
        for (InstrumentEntry e : corpus.search(q, SEARCH_K)) {
            if (e.symbol() != null && e.symbol().equalsIgnoreCase(upper)) return e;
            if (!queryWords.isEmpty() && TextMatch.matchesAll(e.name(), queryWords)) return e;
        }
        return null;
    }

    private static ResolvedInstrument toResolved(InstrumentEntry e, String query) {
        String name = e.name() == null || e.name().isBlank() ? query : e.name();
        return new ResolvedInstrument(Isin.parse(e.isin()), Ticker.parse(e.symbol()), name);
    }
}
