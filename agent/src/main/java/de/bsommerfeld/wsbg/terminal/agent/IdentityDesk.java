package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentCandidate;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentLookup;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance.YahooQuote;
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

    /**
     * The basin-evidence seam (the corpus check, as a VETO): the token sets of the
     * basin documents that NAME the subject. Backed by the tagger's in-RAM Lucene
     * index in production; null in tests (veto off). Sub-millisecond, no model, no
     * network — the empirical probe on the captured basin resolved 5/5 core failure
     * cases in this role while the same data as a SELECTOR managed 2-3 and invented
     * a new error (Rockstar → Celsius), which is why this is a veto and nothing more.
     */
    interface SubjectEvidence {
        List<java.util.Set<String>> docsNaming(String name);

        /** Corpus entity-DF of one name token (how many instruments carry it); 0 = unknown. */
        default int entityDf(String token) {
            return 0;
        }
    }

    /**
     * Minimum basin documents naming the subject before the evidence may strike
     * anything: below this the basin simply has not written about the subject, and
     * absence of coverage must never veto a small cap the room found first.
     */
    private static final int EVIDENCE_MIN_DOCS = 3;

    private final PickJudge judge;
    private final BooleanSupplier enabled;
    /** The venue candidate search; installed post-construction in production, null in tests. */
    private volatile InstrumentLookup lookup;
    /** Persistent verdict book; installed with the lookup in production, null in tests. */
    private volatile IdentityLedger ledger;
    /** The basin evidence for the corpus veto; installed post-construction, null in tests. */
    private volatile SubjectEvidence evidence;
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

    void installEvidence(SubjectEvidence evidence) {
        this.evidence = evidence;
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

        // The corpus veto (Stufe 2): a candidate that claims identity beyond the
        // subject's own words — extra name words, another symbol, another ISIN —
        // and finds ZERO support for that claim in the basin documents that name
        // the subject contradicts the house's own reading material and is struck
        // BEFORE the judge sees it. Applied to both venues' candidate lists.
        List<java.util.Set<String>> basinDocs = basinDocs(query);
        if (basinDocs.size() >= EVIDENCE_MIN_DOCS) {
            quotes = evidenceVetoQuotes(query, quotes, basinDocs);
            venue = evidenceVetoVenue(query, venue, basinDocs);
            if (quotes.isEmpty() && venue.isEmpty()) {
                // Every candidate contradicted the house's own coverage: a
                // considered news-only, not an abstain (the fuzzy tower below must
                // not re-find what the evidence just rejected). Session-only —
                // the basin drifts, so tomorrow may read differently.
                SubjectMatch verdict = SubjectMatch.newsOnly(query);
                session.put(key, verdict);
                LOG.info("[IDENTITY] '{}' → news-only (corpus veto struck every candidate)", query);
                return Optional.of(verdict);
            }
        }

        // Exact-paper fact: a candidate whose significant words ARE the subject's is
        // the subject — mechanically, before any model judgment can strike it.
        int exactYahoo = indexOfNameEquivalent(query, quotes);

        Gemma4Judge.DeskPick pick = judge == null ? null
                : judge.pick(query, judgeContext(ctx), yahooLines(quotes), lsLines(venue));
        // The kind gate (2026-07-13, "Trump kriegt einen Preis"): the judge declares
        // what the subject ITSELF is BEFORE any candidate counts. A person or theme
        // never claims an instrument, no matter how exactly a stock ("trump"→DJT),
        // a memecoin ("Donald Trump USD") or a theme-ETF ("Inflation"→IBCI.DE)
        // spells the name — those are naming parasites, and the wrong stamp ships
        // the right price for the wrong referent AND fans the news queries out on
        // the wrong canonical name. A considered person/theme verdict stops the
        // tower like any news-only claim; it is session-only (context-dependent),
        // never persisted.
        if (pick != null && pick.nonInstrument()) {
            SubjectMatch verdict = SubjectMatch.newsOnly(query);
            session.put(key, verdict);
            LOG.info("[IDENTITY] '{}' → news-only ({})", query, pick.kind());
            return Optional.of(verdict);
        }
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
        // Venue rerank (2026-08-13): the judge decides IDENTITY, never venue quality —
        // it happily confirmed Krispy Kreme as 9YM.F (a thin Frankfurt secondary) with
        // DNUT (the Nasdaq primary) sitting one line above. Among the candidates that
        // name the SAME paper, the {@link VenuePreference} order picks the listing:
        // primary/home beats Frankfurt beats an obscure foreign line. Mechanical, so
        // the prompt's new venue sentence is backstopped rather than trusted.
        yq = preferListedVenue(query, yq, quotes);
        InstrumentCandidate lc = pick.ls() >= 1 && pick.ls() <= venue.size()
                ? venue.get(pick.ls() - 1) : null;
        // Category gate (2026-08-13): the venue search returns EVERY category, and a
        // warrant/certificate carries the company's name too. A venue pick outside the
        // underlying categories (stock/ETF/fund/currency/commodity notation) is a
        // wrapper paper, never the subject — same doctrine as the prompt's
        // Hüllen-Papier rule and the crypto-wrapper veto below, enforced mechanically.
        if (lc != null && !isUnderlyingCategory(lc.category())) {
            LOG.info("[IDENTITY] wrapper veto '{}': venue pick '{}' ({}) is no underlying "
                    + "category — dropped", query, lc.displayName(), lc.category());
            lc = null;
        }
        // An index has NO venue paper: any L&S candidate for a ^-symbol is by
        // definition a wrapper product (ETF/certificate), never the subject itself.
        // Executing such a stamp priced ^DJI as the SPDR ETF in EUR instead of
        // index points (2026-07-10 live run) — indices price via Yahoo, in points.
        if (yq != null && yq.symbol() != null && yq.symbol().startsWith("^")) lc = null;
        // A crypto subject's only venue paper is the currency notation (CUR): any
        // ETF/STK candidate beside a CRYPTOCURRENCY pick is a wrapper (ETP/ETC,
        // treasury company) or a same-named stock. Executing such a stamp priced
        // BTC-USD as a 21Shares ETP at 18 EUR (2026-07-13 live run) — the coin
        // prices via Yahoo instead.
        if (yq != null && isCrypto(yq) && lc != null
                && !"CUR".equalsIgnoreCase(lc.category())) {
            LOG.info("[IDENTITY] crypto wrapper veto '{}': venue pick '{}' ({}) is not the "
                    + "currency notation — dropped", query, lc.displayName(), lc.category());
            lc = null;
        }
        boolean stampUnpriceable = false;
        if (lc != null) {
            switch (priceCheck(query, yq, lc)) {
                case UNPRICEABLE -> {
                    // A MISSING venue price is no acquittal (the IREN lesson): the
                    // stamp is dropped — but only session-deep, never persisted, so
                    // a transient venue outage cannot burn a 30-day stampless verdict.
                    lc = null;
                    stampUnpriceable = true;
                }
                case IMPLAUSIBLE -> lc = null;
                default -> { }
            }
        }

        // The judge SAW venue candidates and (or the price veto) struck them all: a
        // considered "does not trade there" — the price chain's fuzzy name search
        // must not re-open the question and re-find the twin the desk just rejected.
        // An UNPRICEABLE stamp is no such consideration: the question stays open.
        boolean venueRuledOut = yq != null && lc == null && !venue.isEmpty() && !stampUnpriceable;
        SubjectMatch verdict = toMatch(query, yq, lc, venueRuledOut);
        session.put(key, verdict);
        if (stampUnpriceable) {
            LOG.info("[IDENTITY] '{}' → {} decided session-only (venue stamp unpriceable)",
                    query, verdict.symbol());
            return Optional.of(verdict);
        }
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
     * The venue rerank behind the judge's Yahoo pick: among the search candidates
     * that denote the SAME paper as the pick (significant name words contain each
     * other, same quote type), the {@link VenuePreference}-best listing wins. An
     * exact-symbol query (the room typed the ticker) is never reranked away — that
     * spelling IS the venue choice. Live case: 'Krispy Kreme Inc' judged onto
     * {@code 9YM.F} (rank 100+) while {@code DNUT} (rank 0) sat in the same list;
     * Wirecard's {@code 0O8X.IL} (delisted London IOB, the worst rank in the
     * system) stays only when it is genuinely the lone candidate.
     */
    private static YahooQuote preferListedVenue(String query, YahooQuote picked,
            List<YahooQuote> quotes) {
        if (picked == null || quotes == null || quotes.size() < 2) return picked;
        if (picked.symbol() != null && picked.symbol().equalsIgnoreCase(query)) return picked;
        String type = picked.quoteType() == null ? "" : picked.quoteType().trim();
        YahooQuote best = picked;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < quotes.size(); i++) {
            YahooQuote q = quotes.get(i);
            if (q == null || q.symbol() == null) continue;
            String qType = q.quoteType() == null ? "" : q.quoteType().trim();
            if (!type.equalsIgnoreCase(qType)) continue;
            if (q != picked && !samePaper(picked, q)) {
                continue;
            }
            int rank = VenuePreference.preferenceRank(q, i, false);
            if (rank < bestRank) {
                bestRank = rank;
                best = q;
            }
        }
        if (best != picked) {
            LOG.info("[IDENTITY] venue rerank '{}': {} → {} (same paper, better listing)",
                    query, picked.symbol(), best.symbol());
        }
        return best;
    }

    /**
     * Same paper, spelled at another venue: the significant name words are EXACTLY
     * equal (legal forms stripped), OR the SYMBOL STEMS agree (suffix and leading
     * secondary-line digits stripped — {@code 1MUV2.MI} and {@code MUV2.DE} are the
     * same paper even though Yahoo names one "Munich Re" and the other "Münchener
     * Rückversicherungs-Gesellschaft…", whose word sets are disjoint across the two
     * languages). Deliberately NOT name containment
     * ({@link NameMatching#sameCompanyName}), which would fold "Siemens AG" and
     * "Siemens Energy AG" — two different papers — into one rerank pool; two
     * DIFFERENT companies sharing a symbol stem across venues are practically
     * nonexistent, and the caller additionally demands an equal quote type.
     */
    private static boolean samePaper(YahooQuote a, YahooQuote b) {
        java.util.Set<String> wa = NameMatching.significantNameWords(a.displayName());
        java.util.Set<String> wb = NameMatching.significantNameWords(b.displayName());
        if (!wa.isEmpty() && wa.equals(wb)) return true;
        String sa = symbolStem(a.symbol());
        return !sa.isEmpty() && sa.equals(symbolStem(b.symbol()));
    }

    /**
     * A symbol's venue-free stem: exchange suffix cut, leading digits (the
     * secondary-line prefix) stripped. Stems shorter than two characters carry no
     * identity and never match.
     */
    private static String symbolStem(String symbol) {
        if (symbol == null) return "";
        String s = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        s = s.substring(i);
        return s.length() >= 2 ? s : "";
    }

    /**
     * Venue categories that ARE an underlying: stock, ETF/fund, the currency and
     * commodity notations, index. Everything else the venue search returns
     * (warrants, knock-outs, certificates, bonds — the categories the corpus and
     * the ledger were being poisoned with) is a wrapper on an underlying, never
     * the subject itself. A blank category stays admissible — absence of a label
     * is not evidence of a wrapper.
     */
    private static boolean isUnderlyingCategory(String category) {
        if (category == null || category.isBlank()) return true;
        return switch (category.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "STK", "ETF", "FND", "FONDS", "FUND", "CUR", "RES", "IND", "INDEX" -> true;
            default -> false;
        };
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
    private enum PriceCheck { OK, UNPRICEABLE, IMPLAUSIBLE }

    private PriceCheck priceCheck(String query, YahooQuote yq, InstrumentCandidate lc) {
        if (yq == null) return PriceCheck.OK;
        double ref = yq.regularMarketPrice();
        if (!Double.isFinite(ref) || ref <= 0 || !eurComparable(yq.symbol())) return PriceCheck.OK;
        InstrumentLookup l = lookup;
        if (l == null) return PriceCheck.OK;
        Double venuePrice;
        try {
            venuePrice = l.lastPrice(lc).orElse(null);
        } catch (Exception e) {
            venuePrice = null;
        }
        if (venuePrice == null || venuePrice <= 0) {
            // A MISSING venue price is no acquittal (the IREN lesson): a stamp whose
            // paper the venue cannot price answers no price question — dropping it
            // costs only the stamp, never the subject (the Yahoo identity stands).
            LOG.info("[IDENTITY] price veto '{}': venue pick '{}' unpriceable — stamp dropped",
                    query, lc.displayName());
            return PriceCheck.UNPRICEABLE;
        }
        double ratio = venuePrice / ref;
        if (ratio >= 1.0 / PRICE_BAND && ratio <= PRICE_BAND) return PriceCheck.OK;
        LOG.info("[IDENTITY] price veto '{}': venue pick '{}' at {} EUR vs Yahoo {} at {} — dropped",
                query, lc.displayName(), venuePrice, yq.symbol(), ref);
        return PriceCheck.IMPLAUSIBLE;
    }

    /**
     * True when the Yahoo listing's native currency is near-EUR without an FX feed:
     * a bare US symbol / crypto pair (USD) or a euro-area/CHF exchange suffix. The
     * band is wide enough to absorb those spreads; everything else (KRW, JPY, GBp,
     * HKD, BRL …) skips the check rather than risk a false veto on the unit alone.
     */
    static boolean eurComparable(String symbol) {
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

    /**
     * The judge's context line: the room's thread title PLUS the thread's
     * already-resolved neighbour subjects. The neighbours were collected on every
     * resolve and never read by the desk — yet a thread that has demonstrably
     * settled on "Alphabet Inc., Microsoft Corp" is exactly the context that keeps
     * a same-named parasite from claiming the next name in it.
     */
    private static String judgeContext(MatchContext ctx) {
        String base = ctx.context() == null ? "" : ctx.context().strip();
        List<String> neighbours = ctx.neighbours();
        if (neighbours == null || neighbours.isEmpty()) return base;
        String joined = String.join(", ", neighbours.size() <= 4
                ? neighbours : neighbours.subList(0, 4));
        return base.isEmpty() ? "(" + joined + ")" : base + " (" + joined + ")";
    }

    // ---- the corpus veto ----

    /** The basin documents naming the subject (token sets), or empty when the seam is absent. */
    private List<java.util.Set<String>> basinDocs(String query) {
        SubjectEvidence ev = evidence;
        if (ev == null) return List.of();
        try {
            List<java.util.Set<String>> docs = ev.docsNaming(query);
            return docs == null ? List.of() : docs;
        } catch (Exception e) {
            LOG.debug("basin evidence failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private List<YahooQuote> evidenceVetoQuotes(String query, List<YahooQuote> quotes,
            List<java.util.Set<String>> docs) {
        if (quotes.isEmpty()) return quotes;
        java.util.Set<String> subject = NameMatching.tokenize(query);
        SubjectEvidence ev = evidence;
        java.util.function.ToIntFunction<String> df = ev == null ? t -> 0 : ev::entityDf;
        List<YahooQuote> out = new ArrayList<>(quotes.size());
        for (YahooQuote q : quotes) {
            if (q == null) continue;
            if (contradicted(subject, docs, q.displayName(), symbolStem(q.symbol()), null, df)) {
                LOG.info("[IDENTITY] corpus veto '{}': '{}' [{}] has zero co-occurrence in "
                        + "{} basin doc(s) — dropped", query, q.displayName(), q.symbol(), docs.size());
                continue;
            }
            out.add(q);
        }
        return out;
    }

    private List<InstrumentCandidate> evidenceVetoVenue(String query, List<InstrumentCandidate> venue,
            List<java.util.Set<String>> docs) {
        if (venue.isEmpty()) return venue;
        java.util.Set<String> subject = NameMatching.tokenize(query);
        SubjectEvidence ev = evidence;
        java.util.function.ToIntFunction<String> df = ev == null ? t -> 0 : ev::entityDf;
        List<InstrumentCandidate> out = new ArrayList<>(venue.size());
        for (InstrumentCandidate c : venue) {
            if (c == null) continue;
            if (contradicted(subject, docs, c.displayName(), null, c.isin(), df)) {
                LOG.info("[IDENTITY] corpus veto '{}': venue '{}' ({}) has zero co-occurrence in "
                        + "{} basin doc(s) — dropped", query, c.displayName(), c.isin(), docs.size());
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * Entity-DF at or below which a candidate's extra name token is a DISTINCTIVE
     * identity claim (mirrors the tagger's identifying-token bar). Above it the
     * token is corporate filler ("Systems" 73 names, "Group" 713) that no basin
     * article would spell out — measured 2026-08-13: without this bar the veto
     * struck Cisco ("Systems"), Interactive Brokers ("Group") and SpaceX
     * ("Exploration") on the live basin.
     */
    private static final int EVIDENCE_IDENTIFYING_DF_MAX = 4;

    /**
     * The veto core — scoped to NAME PARASITES: a candidate that RIDES the
     * subject's own surface form (all subject tokens appear in its name) yet
     * claims a DISTINCTIVE extra identity (rare name tokens, another symbol
     * stem, another ISIN) that finds zero echo in any basin document naming the
     * subject. A cross-name identity (subject "Google", candidate "Alphabet
     * Inc.") is deliberately OUT of scope — co-occurrence cannot verify the
     * nickname edge, and the measured basin would have false-struck it — as is
     * a candidate that claims nothing beyond the subject's words. Prefix-tolerant
     * on longer tokens ("muenchen" in a doc supports "muenchener").
     */
    static boolean contradicted(java.util.Set<String> subjectTokens,
            List<java.util.Set<String>> docs, String candidateName, String symbolStem, String isin,
            java.util.function.ToIntFunction<String> entityDf) {
        if (subjectTokens.isEmpty()) return false;
        java.util.Set<String> cand = NameMatching.tokenize(candidateName);
        // Scope gate: every subject token must ride in the candidate's name.
        for (String s : subjectTokens) {
            boolean present = false;
            for (String t : cand) {
                if (tokenKin(t, s)) {
                    present = true;
                    break;
                }
            }
            if (!present) return false;
        }
        java.util.Set<String> extra = new java.util.HashSet<>();
        for (String t : cand) {
            boolean covered = false;
            for (String s : subjectTokens) {
                if (tokenKin(t, s)) {
                    covered = true;
                    break;
                }
            }
            if (!covered && entityDf.applyAsInt(t) <= EVIDENCE_IDENTIFYING_DF_MAX) extra.add(t);
        }
        if (extra.isEmpty()) return false;
        java.util.Set<String> exact = new java.util.HashSet<>();
        if (symbolStem != null && symbolStem.length() >= 2) {
            exact.add(symbolStem.toLowerCase(java.util.Locale.ROOT));
        }
        if (isin != null && !isin.isBlank()) exact.add(isin.trim().toLowerCase(java.util.Locale.ROOT));
        for (java.util.Set<String> doc : docs) {
            for (String raw : doc) {
                String t = raw.toLowerCase(java.util.Locale.ROOT);
                if (exact.contains(t)) return false;
                for (String e : extra) {
                    if (tokenKin(t, e)) return false;
                }
            }
        }
        return true;
    }

    /**
     * Two tokens are kin when they are equal or one is a ≥5-char prefix of the
     * other — in the raw form OR with the German ue/oe/ae transliteration
     * collapsed. The collapse bridges the two normalisations in play: the name
     * side transliterates umlauts to ue-forms ("münchener" → "muenchener") while
     * the basin index ASCII-folds them ("münchen" → "munchen"); applied to BOTH
     * sides the comparison stays symmetric.
     */
    private static boolean tokenKin(String a, String b) {
        return prefixKin(a, b) || prefixKin(collapse(a), collapse(b));
    }

    private static boolean prefixKin(String a, String b) {
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= 5 && longer.startsWith(shorter);
    }

    private static String collapse(String t) {
        return t.replace("ae", "a").replace("oe", "o").replace("ue", "u").replace("ss", "s");
    }

    private static int indexOfNameEquivalent(String query, List<YahooQuote> quotes) {
        for (int i = 0; i < quotes.size(); i++) {
            YahooQuote q = quotes.get(i);
            // A crypto token is never an exact-name FACT: anyone mints a coin named
            // after any person or thing ("Donald Trump USD" stamped a memecoin onto
            // the person, 2026-07-13 live run). Legal company names prove identity;
            // token names are naming parasites — crypto stays the judge's call.
            if (isCrypto(q)) continue;
            if (NameMatching.nameEquivalent(query, q.displayName())) return i;
        }
        return -1;
    }

    /** Yahoo's crypto shape: the CRYPTOCURRENCY quote type, or the -USD pair suffix. */
    private static boolean isCrypto(YahooQuote q) {
        if (q == null) return false;
        if ("CRYPTOCURRENCY".equalsIgnoreCase(q.quoteType() == null ? "" : q.quoteType().trim())) {
            return true;
        }
        String sym = q.symbol() == null ? "" : q.symbol().trim().toUpperCase(java.util.Locale.ROOT);
        return sym.endsWith("-USD");
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
