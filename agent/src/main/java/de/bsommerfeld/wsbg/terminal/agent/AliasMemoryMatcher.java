package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.instruments.AliasCandidate;
import de.bsommerfeld.wsbg.terminal.instruments.AliasProvenance;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import de.bsommerfeld.wsbg.terminal.instruments.SymbolShapes;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentCandidate;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentLookup;
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

    /**
     * Replay price band — same constant, same doctrine as the desk's
     * {@code IdentityDesk#PRICE_BAND}: a remembered venue stamp whose live venue
     * price sits outside [1/5, 5] of the Yahoo reference is the wrong paper.
     */
    private static final double PRICE_BAND = 5.0;

    private final Supplier<AliasStore> aliases;
    private final Supplier<InstrumentCorpus> corpus;
    private final Supplier<InstrumentLookup> venueLookup;

    AliasMemoryMatcher(Supplier<AliasStore> aliases, Supplier<InstrumentCorpus> corpus) {
        this(aliases, corpus, () -> null);
    }

    AliasMemoryMatcher(Supplier<AliasStore> aliases, Supplier<InstrumentCorpus> corpus,
            Supplier<InstrumentLookup> venueLookup) {
        this.aliases = aliases;
        this.corpus = corpus;
        this.venueLookup = venueLookup;
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
        // Formal-shape hygiene (belt to the store's own braces): a reading whose
        // symbol is a junk coin, a numeric-prefixed western secondary, a 0P… fund
        // id or a bare WKN is never replayed — the tower investigates fresh.
        if (SymbolShapes.isSuspectAliasSymbol(symbol)) {
            LOG.info("[RESOLVE] memory: '{}' → {} suppressed (formally suspect symbol shape)",
                    ctx.query(), symbol);
            return Optional.empty();
        }
        // The replay price veto (the IREN lesson): the memory used to stamp
        // ISIN/venueId past every desk safeguard — a wrong stamp then re-earned its
        // own trust on each mention, invisible to any shape heuristic. Where the
        // stamp is verifiable (venue price + a Yahoo reference from THIS search),
        // verify it; on contradiction abstain, so the desk re-decides with all its
        // vetoes instead of the ledger answering for itself.
        if (stampImplausible(ctx, symbol, best.provenance())) {
            return Optional.empty();
        }
        InstrumentEntry entry = lookup(symbol);
        // Without a corpus row the room's own spelling is the display name — NEVER the
        // symbol string. The old `name = symbol` fallback shipped "675054 (675054)" and
        // "BE (BE)" units whose canonical name no sentence can carry (2026-08-13).
        String name = entry != null && entry.name() != null && !entry.name().isBlank()
                ? entry.name() : ctx.query().trim();
        LOG.info("[RESOLVE] memory: '{}' → {} (strength {}, {} posting(s), learned via {})",
                ctx.query(), symbol, String.format(Locale.ROOT, "%.2f", best.strength()),
                best.postings(), best.provenance().tier());
        return Optional.of(new SubjectMatch(symbol, name,
                entry == null ? null : quoteOf(entry),
                best.provenance().isin() != null ? best.provenance().isin()
                        : entry == null ? null : entry.isin(),
                best.provenance().venueId(), best.provenance().category()));
    }

    /**
     * True when the remembered venue stamp CONTRADICTS the live prices — or when the
     * stamped paper cannot be priced at all (a stamp that answers no price question
     * is worthless; "ein fehlender Venue-Preis ist kein Freispruch"). Only judged
     * when a judgment is possible: a stamp without venueId, an absent venue lookup
     * (tests), a missing/non-EUR-comparable Yahoo reference in THIS search's
     * candidates — all mean "cannot verify", and an unverifiable stamp replays as
     * before rather than punishing every venue-only listing.
     */
    private boolean stampImplausible(MatchContext ctx, String symbol, AliasProvenance p) {
        if (p == null || p.venueId() <= 0) return false;
        InstrumentLookup l = venueLookup.get();
        if (l == null) return false;
        YahooQuote ref = null;
        if (ctx.quotes() != null) {
            for (YahooQuote q : ctx.quotes()) {
                if (q != null && q.symbol() != null && q.symbol().equalsIgnoreCase(symbol)) {
                    ref = q;
                    break;
                }
            }
        }
        if (ref == null || !Double.isFinite(ref.regularMarketPrice())
                || ref.regularMarketPrice() <= 0
                || !IdentityDesk.eurComparable(ref.symbol())) {
            return false;
        }
        Double venuePrice;
        try {
            venuePrice = l.lastPrice(new InstrumentCandidate("L&S", p.venueId(),
                    p.isin() == null ? "" : p.isin(), "", ctx.query(),
                    p.category() == null ? "" : p.category(), "")).orElse(null);
        } catch (Exception e) {
            return false; // a transient venue failure must not unlearn a memory
        }
        if (venuePrice == null || venuePrice <= 0) {
            LOG.info("[RESOLVE] memory: '{}' → {} stamp unpriceable (venue {}) — abstain, desk re-decides",
                    ctx.query(), symbol, p.venueId());
            return true;
        }
        double ratio = venuePrice / ref.regularMarketPrice();
        if (ratio >= 1.0 / PRICE_BAND && ratio <= PRICE_BAND) return false;
        LOG.info("[RESOLVE] memory: '{}' → {} stamp implausible (venue {} at {} EUR vs Yahoo {} at {})"
                        + " — abstain, desk re-decides", ctx.query(), symbol, p.venueId(),
                venuePrice, ref.symbol(), ref.regularMarketPrice());
        return true;
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
