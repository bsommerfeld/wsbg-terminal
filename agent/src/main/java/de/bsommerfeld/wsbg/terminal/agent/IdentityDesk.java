package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.price.InstrumentCandidate;
import de.bsommerfeld.wsbg.terminal.core.price.InstrumentLookup;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The identity desk — the ONE border-control checkpoint where a subject's identity
 * is decided. All the facts are laid on the table at once (the Yahoo search
 * candidates with type + exchange, the L&amp;S venue candidates with category + ISIN),
 * ONE gemma4 judge call picks per venue which candidate — if any — IS the subject,
 * and the verdict is stamped onto the match ({@code isin} + {@code venueId} +
 * {@code category}) and recorded in the {@link IdentityLedger}. Downstream nobody
 * re-decides identity: the price chain executes the stamp, the registry keys on the
 * verdict's symbol, later resolves replay the ledger.
 *
 * <p>This replaces the layered guard tower (token thresholds, venue-preference
 * mali, coverage gates, popularity dominance) for every subject it decides — those
 * stages remain wired BELOW the desk purely as the outage fallback.
 *
 * <p><b>Decision semantics</b> (the tower contract of {@link SubjectMatcher}):
 * <ul>
 *   <li><b>Claim with instrument</b> — the judge (or the exact-name bypass) picked a
 *       candidate. Symbol from the Yahoo pick; when only the venue matched (a German
 *       listing Yahoo doesn't carry) the venue WKN stands in as the symbol — the
 *       identifier this audience actually uses.</li>
 *   <li><b>Claim news-only</b> — the judge saw ALL the facts and said none is the
 *       subject. This STOPS the tower (a considered verdict must not be second-guessed
 *       by the fuzzier stages below).</li>
 *   <li><b>Abstain</b> ({@code empty}) — desk disabled, no facts at all, or the model
 *       call failed: fall through to the legacy tower. Errors never destroy data.</li>
 * </ul>
 *
 * <p><b>Exact-name bypass</b> (recalibrated 2026-07-09 after live false vetoes on
 * exact names): a Yahoo candidate that is {@link NameMatching#nameEquivalent} to the
 * subject is confirmed mechanically — a fact needs no judgment. The judge is still
 * asked for the venue side (twins are exactly where names lie).
 */
final class IdentityDesk {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityDesk.class);

    /** Cap on venue candidates shown to the judge — the search returns them ranked by the venue. */
    private static final int MAX_VENUE_CANDIDATES = 8;

    /**
     * Price-plausibility band for a double pick (Yahoo AND venue): the venue price
     * divided by the Yahoo reference must fall inside [1/{@value #PRICE_BAND},
     * {@value #PRICE_BAND}]. Deliberately generous — it absorbs EUR/USD/CHF spreads
     * and an intraday move, but catches what the 2026-07-09 live run shipped: a CDR
     * at 1/16 of the share, a Bitcoin ETP at 1/3500 of the coin, an A-share at
     * 1500× the B. Same-magnitude wrong papers slip through — those are the
     * prompt's wrapper rule and the ISIN paths' job.
     */
    private static final double PRICE_BAND = 5.0;

    /** The judge seam: a per-venue pick over the numbered fact lines, or null on model failure. */
    interface PickJudge {
        Gemma4Judge.DeskPick pick(String subject, String context,
                List<String> yahooLines, List<String> lsLines);
    }

    private final PickJudge judge;
    private final BooleanSupplier enabled;
    /** The venue candidate search; installed post-construction in production, null in tests. */
    private volatile InstrumentLookup lookup;
    /** Persistent verdict book; installed with the lookup in production, null in tests. */
    private volatile IdentityLedger ledger;
    /** Session verdicts (including news-only, which is context-dependent and never persisted). */
    private final Map<String, SubjectMatch> session = new ConcurrentHashMap<>();

    IdentityDesk(PickJudge judge, BooleanSupplier enabled) {
        this.judge = judge;
        this.enabled = enabled;
    }

    void installLookup(InstrumentLookup lookup) {
        this.lookup = lookup;
    }

    void installLedger(IdentityLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * The checkpoint. Present = a considered verdict (claim, possibly news-only);
     * empty = abstain, the legacy tower decides.
     */
    Optional<SubjectMatch> decide(MatchContext ctx) {
        if (!enabled.getAsBoolean()) return Optional.empty();
        String query = ctx.query();
        if (query == null || query.isBlank()) return Optional.empty();
        String key = IdentityLedger.normalize(query);

        SubjectMatch remembered = session.get(key);
        if (remembered != null) return Optional.of(remembered);

        IdentityLedger book = ledger;
        IdentityLedger.Entry replay = book == null ? null : book.get(key);
        if (replay != null) {
            SubjectMatch m = fromLedger(replay);
            session.put(key, m);
            return Optional.of(m);
        }

        List<YahooQuote> quotes = ctx.quotes() == null ? List.of() : ctx.quotes();
        List<InstrumentCandidate> venue = venueCandidates(query);
        if (quotes.isEmpty() && venue.isEmpty()) return Optional.empty();

        // Exact-paper fact: a candidate whose significant words ARE the subject's is
        // the subject — mechanically, before any model judgment can strike it.
        int exactYahoo = indexOfNameEquivalent(query, quotes);

        Gemma4Judge.DeskPick pick = judge == null ? null
                : judge.pick(query, ctx.context(), yahooLines(quotes), lsLines(venue));
        if (pick == null) {
            // Model failure/absence: no considered verdict. Only the mechanical exact
            // fact may still claim; otherwise abstain to the legacy tower.
            if (exactYahoo < 0) return Optional.empty();
            pick = new Gemma4Judge.DeskPick(exactYahoo + 1, 0);
        } else if (exactYahoo >= 0) {
            pick = new Gemma4Judge.DeskPick(exactYahoo + 1, pick.ls());
        }

        YahooQuote yq = pick.yahoo() >= 1 && pick.yahoo() <= quotes.size()
                ? quotes.get(pick.yahoo() - 1) : null;
        InstrumentCandidate lc = pick.ls() >= 1 && pick.ls() <= venue.size()
                ? venue.get(pick.ls() - 1) : null;
        // An index has NO venue paper: any L&S candidate for a ^-symbol is by
        // definition a wrapper product (ETF/certificate), never the subject itself.
        // Executing such a stamp priced ^DJI as the SPDR ETF in EUR instead of
        // index points (2026-07-10 live run) — indices price via Yahoo, in points.
        if (yq != null && yq.symbol() != null && yq.symbol().startsWith("^")) lc = null;
        if (lc != null && priceImplausible(query, yq, lc)) lc = null;

        // The judge SAW venue candidates and (or the price veto) struck them all: a
        // considered "does not trade there" — the price chain's fuzzy name search
        // must not re-open the question and re-find the twin the desk just rejected.
        boolean venueRuledOut = yq != null && lc == null && !venue.isEmpty();
        SubjectMatch verdict = toMatch(query, yq, lc, venueRuledOut);
        session.put(key, verdict);
        if (book != null && verdict.symbol() != null) {
            book.put(key, new IdentityLedger.Entry(key, verdict.symbol(), verdict.canonicalName(),
                    verdict.isin(), verdict.venueId(), verdict.category(),
                    verdict.venueRuledOut(), Instant.now().getEpochSecond()));
        }
        LOG.info("[IDENTITY] '{}' → {}{}", query,
                verdict.symbol() == null ? "news-only" : verdict.symbol(),
                verdict.isStamped() ? " [" + verdict.category() + " " + verdict.isin() + "]" : "");
        return Optional.of(verdict);
    }

    /**
     * The double pick's mechanical plausibility fact: when BOTH venues priced the
     * pick and the two prices sit outside the generous {@link #PRICE_BAND}, the
     * venue candidate is a different paper (a depositary receipt, a bond, a
     * share-class or same-named twin) no matter how well its name matched — the
     * L&amp;S pick is dropped, the Yahoo identity stands. Only run where the Yahoo
     * listing's currency is comparable to EUR without an FX feed (USD/EUR/CHF
     * venues); a KRW/JPY/GBp listing would false-positive on the unit alone.
     */
    private boolean priceImplausible(String query, YahooQuote yq, InstrumentCandidate lc) {
        if (yq == null) return false;
        double ref = yq.regularMarketPrice();
        if (!Double.isFinite(ref) || ref <= 0 || !eurComparable(yq.symbol())) return false;
        InstrumentLookup l = lookup;
        if (l == null) return false;
        Double venuePrice;
        try {
            venuePrice = l.lastPrice(lc).orElse(null);
        } catch (Exception e) {
            return false;
        }
        if (venuePrice == null || venuePrice <= 0) return false;
        double ratio = venuePrice / ref;
        if (ratio >= 1.0 / PRICE_BAND && ratio <= PRICE_BAND) return false;
        LOG.info("[IDENTITY] price veto '{}': venue pick '{}' at {} EUR vs Yahoo {} at {} — dropped",
                query, lc.displayName(), venuePrice, yq.symbol(), ref);
        return true;
    }

    /**
     * True when the Yahoo listing's native currency is near-EUR without an FX feed:
     * a bare US symbol / crypto pair (USD) or a euro-area/CHF exchange suffix. The
     * band is wide enough to absorb those spreads; everything else (KRW, JPY, GBp,
     * HKD, BRL …) skips the check rather than risk a false veto on the unit alone.
     */
    private static boolean eurComparable(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String s = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        int dot = s.lastIndexOf('.');
        if (dot < 0) return true; // US listing or -USD pair
        return switch (s.substring(dot + 1)) {
            case "DE", "F", "SG", "MU", "DU", "HM", "BE", "PA", "AS", "MI", "MC",
                 "BR", "VI", "HE", "LS", "IR", "SW" -> true;
            default -> false;
        };
    }

    /** Builds the verdict match from the per-venue picks; both null = considered news-only. */
    private static SubjectMatch toMatch(String query, YahooQuote yq, InstrumentCandidate lc,
            boolean venueRuledOut) {
        if (yq == null && lc == null) return SubjectMatch.newsOnly(query);
        // The extraction's canonical (newswire) name beats the venue's SHOUTY
        // abbreviation when only the venue matched.
        String canonical = yq != null ? yq.displayName() : query;
        String symbol = yq != null ? yq.symbol() : venueSymbol(lc);
        String isin = lc == null || lc.isin().isBlank() ? null : lc.isin();
        long venueId = lc == null ? 0 : lc.venueId();
        String category = lc == null || lc.category().isBlank() ? null : lc.category();
        return new SubjectMatch(symbol, canonical, yq, isin, venueId, category, venueRuledOut);
    }

    /** WKN (the identifier this audience uses), else ISIN — for a venue-only listing. */
    private static String venueSymbol(InstrumentCandidate lc) {
        if (lc.wkn() != null && !lc.wkn().isBlank()) return lc.wkn();
        return lc.isin().isBlank() ? null : lc.isin();
    }

    private static SubjectMatch fromLedger(IdentityLedger.Entry e) {
        if (e.symbol() == null || e.symbol().isBlank()) return SubjectMatch.newsOnly(e.q());
        return new SubjectMatch(e.symbol(), e.canonical(), null, e.isin(), e.venueId(), e.category(),
                e.venueRuledOut());
    }

    private List<InstrumentCandidate> venueCandidates(String query) {
        InstrumentLookup l = lookup;
        if (l == null) return List.of();
        try {
            List<InstrumentCandidate> all = l.search(query);
            if (all == null) return List.of();
            return all.size() <= MAX_VENUE_CANDIDATES ? all : all.subList(0, MAX_VENUE_CANDIDATES);
        } catch (Exception e) {
            LOG.debug("venue candidate search failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private static int indexOfNameEquivalent(String query, List<YahooQuote> quotes) {
        for (int i = 0; i < quotes.size(); i++) {
            if (NameMatching.nameEquivalent(query, quotes.get(i).displayName())) return i;
        }
        return -1;
    }

    /** Yahoo fact lines: name — TYPE, exchange [symbol]. */
    private static List<String> yahooLines(List<YahooQuote> quotes) {
        List<String> base = JudgeCandidates.candidateLines(quotes);
        List<String> out = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            String sym = quotes.get(i).symbol();
            out.add(sym == null || sym.isBlank() ? base.get(i) : base.get(i) + " [" + sym + "]");
        }
        return out;
    }

    /** Venue fact lines: name — category, ISIN. */
    private static List<String> lsLines(List<InstrumentCandidate> venue) {
        List<String> out = new ArrayList<>(venue.size());
        for (InstrumentCandidate c : venue) {
            StringBuilder b = new StringBuilder(c.displayName());
            String cat = c.categoryName().isBlank() ? c.category() : c.categoryName();
            if (!cat.isBlank()) b.append(" — ").append(cat);
            if (!c.isin().isBlank()) b.append(", ").append(c.isin());
            out.add(b.toString());
        }
        return out;
    }
}
