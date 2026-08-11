package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.instruments.AliasCandidate;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance.YahooQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The stage that finally READS the learned name memory.
 *
 * <p>Until now {@link AliasStore} was written on every settled verdict and consulted
 * by nobody — the one consumer it had went out with the mention counter, so the app
 * kept a diary it never opened. This stage is the reader: a spelling the room has
 * used repeatedly, and that the ledger reports UNAMBIGUOUSLY, is answered from
 * memory instead of being re-derived through a Yahoo search, a judge call and a
 * corpus pass.
 *
 * <p><b>Placed after the curated catalogues, before the desk</b>: an index or a
 * commodity is settled identity and must keep winning, but everything below is work
 * this stage can save. That makes the memory an accelerator, not just an archive —
 * and it is what lets the hand-kept slang glossary go: „Rheiner" is answered here
 * once the room has taught it, exactly like every other short form.
 *
 * <h3>Why the trust threshold</h3>
 * A single posting is NOT enough to short-circuit the tower. One wrong judge call
 * would otherwise answer for its own spelling forever and re-confirm itself on every
 * mention — the live ledger holds exactly that shape (a lone verdict putting the key
 * figure „EPS" on a Japanese printer maker). {@link AliasCandidate#isTrusted()}
 * demands a reading that has been re-earned; anything thinner falls through to the
 * stages that actually investigate.
 */
final class AliasMemoryMatcher implements SubjectMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(AliasMemoryMatcher.class);

    private final Supplier<AliasStore> aliases;
    private final Supplier<InstrumentCorpus> corpus;

    AliasMemoryMatcher(Supplier<AliasStore> aliases, Supplier<InstrumentCorpus> corpus) {
        this.aliases = aliases;
        this.corpus = corpus;
    }

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        AliasStore store = aliases.get();
        if (store == null || ctx.query() == null || ctx.query().isBlank()) return Optional.empty();

        List<AliasCandidate> live = store.candidatesFor(ctx.query());
        if (live.isEmpty()) return Optional.empty();
        AliasCandidate best = live.get(0);
        // A remembered "this is no instrument" is not a claim — the tower must run on
        // and reach its own (identical, but freshly reasoned) empty verdict. The value
        // of that negative is realised in the coinage stage, which stops re-judging it.
        if (best.isNone() || !best.isTrusted()) return Optional.empty();
        // Contested memory is no memory: two readings of comparable strength mean the
        // question needs the context this file does not have.
        if (store.symbolFor(ctx.query()).isEmpty()) return Optional.empty();

        String symbol = best.symbol();
        InstrumentEntry entry = lookup(symbol);
        String name = entry != null ? entry.name() : symbol;
        LOG.info("[RESOLVE] memory: '{}' → {} (strength {}, {} posting(s), learned via {})",
                ctx.query(), symbol, String.format(Locale.ROOT, "%.2f", best.strength()),
                best.postings(), best.provenance().tier());
        return Optional.of(new SubjectMatch(symbol, name,
                entry == null ? null : quoteOf(entry),
                best.provenance().isin() != null ? best.provenance().isin()
                        : entry == null ? null : entry.isin(),
                best.provenance().venueId(), best.provenance().category()));
    }

    /** The corpus row behind a remembered symbol, so the match carries a real name. */
    private InstrumentEntry lookup(String symbol) {
        InstrumentCorpus c = corpus.get();
        if (c == null || symbol == null) return null;
        for (InstrumentEntry e : c.search(symbol, 4)) {
            if (symbol.equalsIgnoreCase(e.symbol())) return e;
        }
        return null;
    }

    private static YahooQuote quoteOf(InstrumentEntry e) {
        return new YahooQuote(e.symbol(), e.name(), e.name(),
                e.type() == null ? "EQUITY" : e.type(),
                e.exchange(), e.exchange(), null, null,
                Double.NaN, Double.NaN, 0.0);
    }
}
