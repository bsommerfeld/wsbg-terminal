package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collector;

/**
 * Persists one finished headline draft and broadcasts it to the UI. The
 * deterministic-pipeline replacement for {@code PublishHeadlineTool.execute}:
 * the editorial model produces a {@link Draft}, the {@link TickerResolver} has
 * already resolved the instruments, and this writer applies the
 * <em>quality</em> checks and saves.
 *
 * <h3>What it keeps vs. drops (vs. the old tool)</h3>
 * Kept — pure QA that sanitises or enriches without dropping a headline: HTML
 * stripping, source-id hygiene, ticker-shape sanitising, the
 * position-P&amp;L/price-move sanity cap, subject validation, sector/asset
 * normalisation, market-snapshot attach. Dropped — the reject/throttle gates
 * that suppressed the 1:1 mirror: the hard ≤20-word reject (now prompt guidance
 * only), the 10-minute per-(cluster,ticker) cooldown, and the cross-cluster
 * ticker reject. The former IMPORTANT→NORMAL anti-spam downgrade (same ticker
 * flagged recently) was also removed — a subject CAN re-flag IMPORTANT.
 * A short near-duplicate guard prevents an accidental double-publish.
 */
@Singleton
public final class HeadlineWriter {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineWriter.class);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    /** Skip an identical headline text for the same cluster within this window. */
    private static final long DUP_TEXT_GUARD_SECS = 120;
    /** Skip a NEAR-duplicate of a unit's recent headline within this window. Much longer than
     *  the rapid-double guard: with the compose settle/cooldown, a unit's re-composes are
     *  spaced minutes apart, so a near-identical „-Update:" of a line from a few minutes ago
     *  must still be caught. */
    private static final long NEAR_DUP_GUARD_SECS = 1800;

    private final AgentRepository agentRepository;
    private final ApplicationEventBus eventBus;

    @Inject
    public HeadlineWriter(AgentRepository agentRepository, ApplicationEventBus eventBus) {
        this.agentRepository = agentRepository;
        this.eventBus = eventBus;
    }

    /**
     * One headline as drafted by the editorial model. {@code subjects} carry
     * the ticker the model copied from the resolved data we showed it; the
     * writer re-checks it against {@code resolved} so a hallucinated symbol is
     * dropped rather than rendered.
     */
    public record Draft(
            String headline,
            String sentiment,
            String highlight,
            String trigger,
            String tickerSymbol,
            List<DraftSubject> subjects,
            Double priceMovePercent,
            List<String> sectors,
            String assetClass,
            List<String> sourceThreadIds,
            List<String> sourceCommentIds) {

        /** Trigger-less variant for the legacy cluster/theme path and tests ({@code trigger = ""}). */
        public Draft(String headline, String sentiment, String highlight, String tickerSymbol,
                List<DraftSubject> subjects, Double priceMovePercent, List<String> sectors,
                String assetClass, List<String> sourceThreadIds, List<String> sourceCommentIds) {
            this(headline, sentiment, highlight, "", tickerSymbol, subjects, priceMovePercent,
                    sectors, assetClass, sourceThreadIds, sourceCommentIds);
        }
    }

    public record DraftSubject(String name, String ticker) {
    }

    /**
     * Publishes the draft for the given cluster. Returns {@code true} when a
     * headline was saved + broadcast, {@code false} when it was skipped (blank
     * text or identical-text guard). Never throws on bad model output —
     * malformed fields are sanitised away, not rejected.
     */
    public boolean publish(InvestigationCluster cluster, Draft draft,
            List<ResolvedSubject> resolved) {
        if (cluster == null || draft == null) return false;
        String headline = stripHtml(draft.headline()).trim();
        if (headline.isEmpty()) return false;

        // Identical-text guard: don't double-publish the exact same line for
        // the same cluster within a short window (accidental re-fire).
        long now = System.currentTimeMillis() / 1000;
        boolean dup = agentRepository.getHeadlinesByClusterId(cluster.id).stream()
                .anyMatch(h -> headline.equalsIgnoreCase(h.headline())
                        && (now - h.createdAt()) < DUP_TEXT_GUARD_SECS);
        if (dup) {
            LOG.debug("[WRITE] skip duplicate headline text for {}", cluster.id);
            return false;
        }

        // Validated tickers + their market snapshots come from the resolver,
        // not the model — Yahoo is the single source of truth.
        Set<String> validTickers = new HashSet<>();
        Map<String, MarketSnapshot> snapshotByTicker = new HashMap<>();
        if (resolved != null) {
            for (ResolvedSubject rs : resolved) {
                if (rs.isInstrument()) {
                    validTickers.add(rs.ticker().toUpperCase(Locale.ROOT));
                    if (rs.snapshot() != null) {
                        snapshotByTicker.put(rs.ticker().toUpperCase(Locale.ROOT), rs.snapshot());
                    }
                }
            }
        }

        // Source-id hygiene: thread ids must be cluster members; comment ids
        // must look like Reddit comment fullnames.
        List<String> threadIds = clean(draft.sourceThreadIds());
        threadIds.removeIf(id -> !cluster.activeThreadIds.contains(id));
        List<String> commentIds = clean(draft.sourceCommentIds());
        commentIds.removeIf(id -> !id.startsWith("t1_"));

        // Subjects: name must appear verbatim in the headline (UI glow) and the
        // ticker must be one the resolver validated. Dropped otherwise.
        List<HeadlineSubject> subjects = new ArrayList<>();
        if (draft.subjects() != null) {
            for (DraftSubject ds : draft.subjects()) {
                String name = ds.name() == null ? "" : ds.name().trim();
                String ticker = sanitizeTicker(ds.ticker());
                if (name.isEmpty() || ticker == null) continue;
                if (!validTickers.contains(ticker.toUpperCase(Locale.ROOT))) continue;
                if (!headline.contains(name)) continue;
                boolean exists = subjects.stream()
                        .anyMatch(s -> s.name().equals(name) && s.ticker().equals(ticker));
                if (!exists) subjects.add(new HeadlineSubject(name, ticker));
            }
        }

        String tickerSymbol = sanitizeTicker(draft.tickerSymbol());
        if (tickerSymbol != null && !validTickers.contains(tickerSymbol.toUpperCase(Locale.ROOT))) {
            tickerSymbol = null;
        }
        if (tickerSymbol == null && !subjects.isEmpty()) {
            tickerSymbol = subjects.get(0).ticker();
        }

        HeadlineHighlight highlight = HeadlineHighlight.fromString(draft.highlight());

        Double priceMove = sanePriceMove(draft.priceMovePercent(), headline);

        List<String> sectors = clean(draft.sectors()).stream()
                .collect(distinctByLower()).stream().limit(2).toList();
        String assetClass = normalizeAssetClass(draft.assetClass());

        MarketSnapshot snapshot = tickerSymbol == null ? null
                : snapshotByTicker.get(tickerSymbol.toUpperCase(Locale.ROOT));
        HeadlineSentiment sentiment = reconcileSentiment(
                HeadlineSentiment.fromString(draft.sentiment()), priceMove);

        agentRepository.saveHeadline(cluster.id, headline, "",
                threadIds, commentIds, highlight, tickerSymbol, subjects, priceMove,
                sectors, assetClass, sentiment, snapshot, false);

        LOG.info("[WRITE] {} [{}{} {}{}]: {}", cluster.id, highlight,
                tickerSymbol == null ? "" : " " + tickerSymbol, sentiment,
                priceMove == null ? "" : String.format(Locale.ROOT, " %+.1f%%", priceMove),
                headline);

        eventBus.post(new AgentStreamEndEvent(
                "||PASSIVE||" + headline + "||REF||ID:" + cluster.id));
        return true;
    }

    /**
     * Publishes a headline for a feed-wide {@link SubjectUnit} (the editorial atom
     * after the #2 cutover). Same QA + broadcast as {@link #publish}, but the
     * grouping key is the <b>unit id</b> (so story continuity / dedup is per
     * subject, not per cluster) and the ticker + market snapshot come from the
     * <b>unit</b> (resolver-validated), never from the model. Returns {@code true}
     * when saved + broadcast, {@code false} on blank text or the identical-text
     * guard. Never throws on bad model output.
     */
    public boolean publishUnit(SubjectUnit unit, Draft draft) {
        return publishUnit(unit, draft, false, true);
    }

    public boolean publishUnit(SubjectUnit unit, Draft draft, boolean newsEnriched) {
        return publishUnit(unit, draft, newsEnriched, true);
    }

    /**
     * Same as {@link #publishUnit(SubjectUnit, Draft)} but records whether the
     * compose stage leaned on at least one external news item ({@code newsEnriched}
     * = the draft cited a {@code [news:ID]}), and honours the {@code suppressRedundant}
     * setting: when false the near-duplicate guard is skipped so a strict 1:1 mirror
     * publishes every dirty line, even a duplicate.
     */
    public boolean publishUnit(SubjectUnit unit, Draft draft, boolean newsEnriched, boolean suppressRedundant) {
        if (unit == null || draft == null) return false;
        String headline = stripHtml(draft.headline()).trim();
        if (headline.isEmpty()) return false;

        // Near-duplicate guard against a re-fire for the same unit. The 4B model often
        // re-emits the SAME line as an "-Update:" or a light reword ("hat"→"hält") on
        // fresh-but-story-redundant evidence, which a strict equals() misses — so compare
        // the NORMALISED core (strip the "-Update:" marker + punctuation) and a high
        // token-similarity, not the raw string. Skipped entirely in strict 1:1 mode.
        long now = System.currentTimeMillis() / 1000;
        boolean dup = suppressRedundant && agentRepository.getHeadlinesByClusterId(unit.id).stream()
                .filter(h -> (now - h.createdAt()) < NEAR_DUP_GUARD_SECS)
                .anyMatch(h -> isNearDuplicate(headline, h.headline()));
        if (dup) {
            LOG.info("[WRITE] skip near-duplicate headline for unit {}", unit.id);
            return false;
        }

        // Ticker + snapshot are the unit's resolver-validated facts, not the model's.
        String tickerSymbol = unit.isInstrument()
                ? unit.ticker().toUpperCase(Locale.ROOT) : null;
        MarketSnapshot snapshot = unit.snapshot();

        // The slim compose output is just {headline, highlight, mode} — the model no longer
        // cites source ids; the unit IS the evidence, so we don't track per-headline citations.
        List<String> threadIds = List.of();
        List<String> commentIds = List.of();

        // The subject for the UI glow is the unit itself: keep it only when the
        // unit has a validated ticker and a form of its name appears verbatim in
        // the line. The canonical name is Yahoo's LEGAL form ("Salesforce, Inc.")
        // while the line writes the short one ("Salesforce"), so the gild takes
        // the longest word-prefix of the canonical name the line actually contains.
        List<HeadlineSubject> subjects = new ArrayList<>();
        if (tickerSymbol != null) {
            String name = displayFormIn(headline, unit.canonicalName());
            if (name != null) {
                subjects.add(new HeadlineSubject(name, tickerSymbol));
            }
        }

        HeadlineHighlight modelHighlight = HeadlineHighlight.fromString(draft.highlight());
        HeadlineHighlight highlight = reconcileHighlight(modelHighlight, draft.trigger(), unit.snapshot());
        if (highlight != modelHighlight) {
            LOG.info("[WRITE] demote IMPORTANT→NORMAL for unit {} — trigger '{}' {}",
                    unit.id, draft.trigger(),
                    PRICE_TRIGGERS.contains(normalizeTrigger(draft.trigger()))
                            ? "is price-shaped but the unit has no verified price"
                            : "does not justify red");
        }

        // Price move is DERIVED from the resolver snapshot, not asked of the model.
        // Sentiment IS the model's (schema-forced enum since 2026-07-01): the slim
        // 3-key contract had left every line NEUTRAL, which starved the mood badge
        // and the unit's sentiment arc — the room's read is editorial data the
        // price can't supply. reconcileSentiment still flips a camp that
        // contradicts a hard day-move.
        Double priceMove = sanePriceMove(
                snapshot != null && snapshot.hasPrice() ? snapshot.dayChangePercent() : null, headline);
        List<String> sectors = List.of();
        String assetClass = null;
        HeadlineSentiment sentiment = reconcileSentiment(
                HeadlineSentiment.fromString(draft.sentiment()), priceMove);

        agentRepository.saveHeadline(unit.id, headline, "",
                threadIds, commentIds, highlight, tickerSymbol, subjects, priceMove,
                sectors, assetClass, sentiment, snapshot, newsEnriched);

        LOG.info("[WRITE] unit {} [{}{} {}{}]: {}", unit.id,
                highlight == HeadlineHighlight.IMPORTANT
                        ? "IMPORTANT/" + normalizeTrigger(draft.trigger()) : highlight,
                tickerSymbol == null ? "" : " " + tickerSymbol, sentiment,
                priceMove == null ? "" : String.format(Locale.ROOT, " %+.1f%%", priceMove),
                headline);

        eventBus.post(new AgentStreamEndEvent(
                "||PASSIVE||" + headline + "||REF||ID:" + unit.id));
        return true;
    }

    /** Words that must never be gilded on their own (a one-word "match" like "The"). */
    private static final Set<String> GILD_STOP = Set.of(
            "the", "and", "der", "die", "das", "inc", "corp", "group", "holdings", "aktiengesellschaft");

    /**
     * The longest word-prefix of {@code canonicalName} that appears in the headline
     * — the display form the UI gilds. Matching is CASE-INSENSITIVE (the line writes
     * "Nvidia", Yahoo's legal name is "NVIDIA Corporation") and the returned form is
     * the LINE's own spelling, so the front-end regex finds it verbatim.
     * "Salesforce, Inc." gilds "Salesforce"; "D-Wave Quantum Inc." gilds "D-Wave
     * Quantum" or "D-Wave", whichever the line wrote. Trailing commas/periods are
     * stripped per candidate; a lone generic word never gilds. {@code null} when no
     * form is in the line. Package-private for testing.
     */
    static String displayFormIn(String headline, String canonicalName) {
        if (headline == null || canonicalName == null || canonicalName.isBlank()) return null;
        String headlineLower = headline.toLowerCase(Locale.ROOT);
        String[] words = canonicalName.trim().split("\\s+");
        for (int k = words.length; k >= 1; k--) {
            String cand = String.join(" ", Arrays.copyOfRange(words, 0, k))
                    .replaceAll("[,.]+$", "").trim();
            if (cand.length() < 3) continue;
            if (k == 1 && GILD_STOP.contains(cand.toLowerCase(Locale.ROOT))) continue;
            int idx = headlineLower.indexOf(cand.toLowerCase(Locale.ROOT));
            // Word-boundary guard: "Aris" must not gild inside "Paris".
            if (idx >= 0
                    && (idx == 0 || !Character.isLetterOrDigit(headline.charAt(idx - 1)))
                    && (idx + cand.length() >= headline.length()
                        || !Character.isLetterOrDigit(headline.charAt(idx + cand.length())))) {
                return headline.substring(idx, idx + cand.length());
            }
        }
        return null;
    }

    // ---- IMPORTANT gate: the trigger is the mechanical anchor for red ----

    /** Triggers that justify IMPORTANT on their own — catalyst-shaped, no price needed
     *  (the quiet pennystock pooled call must stay red-capable without an L&S listing). */
    private static final Set<String> CATALYST_TRIGGERS = Set.of("HARD_CATALYST", "POOLED_CALL");
    /** Triggers whose whole case IS price action — without a resolver-verified price the
     *  "move" is the room's own screenshot claim, which never earns red on its own. */
    private static final Set<String> PRICE_TRIGGERS =
            Set.of("RUNNER", "SQUEEZE", "BREAKOUT", "EXTREME_DIRECTION");

    static String normalizeTrigger(String trigger) {
        return trigger == null ? "" : trigger.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Deterministic backstop for the IMPORTANT rubric: red must name the concrete
     * trigger that fired (schema-forced {@code trigger} field), so a "feels
     * important" classification with no nameable play demotes to NORMAL. Rules,
     * mirroring the prompt rubric verbatim:
     * <ul>
     *   <li>NORMAL passes through untouched — this gate only ever demotes, never
     *       promotes (red must stay scarce; a missed red is cheaper than a false one).</li>
     *   <li>IMPORTANT with a catalyst-shaped trigger (HARD_CATALYST, POOLED_CALL)
     *       stands — these are evidence-borne and legal without any price.</li>
     *   <li>IMPORTANT with a price-shaped trigger (RUNNER, SQUEEZE, BREAKOUT,
     *       EXTREME_DIRECTION) needs a resolver-verified price on the unit —
     *       the rubric's "an unverified screenshot % never earns red on its own".</li>
     *   <li>IMPORTANT with trigger NONE / blank / unknown (a salvage-path reply,
     *       a legacy draft) is the doubt case, and doubt reads NORMAL.</li>
     * </ul>
     * Package-private for testing.
     */
    static HeadlineHighlight reconcileHighlight(HeadlineHighlight highlight, String trigger,
            MarketSnapshot snapshot) {
        if (highlight != HeadlineHighlight.IMPORTANT) return highlight;
        String t = normalizeTrigger(trigger);
        if (CATALYST_TRIGGERS.contains(t)) return HeadlineHighlight.IMPORTANT;
        if (PRICE_TRIGGERS.contains(t) && snapshot != null && snapshot.hasPrice()) {
            return HeadlineHighlight.IMPORTANT;
        }
        return HeadlineHighlight.NORMAL;
    }

    // ---- sanitisers ported verbatim from the old PublishHeadlineTool ----

    private static String stripHtml(String s) {
        return s == null ? "" : HTML_TAG.matcher(s).replaceAll("");
    }

    /** Token-overlap above which two normalised headlines count as the same line. */
    private static final double DUP_SIM_THRESHOLD = 0.8;

    /**
     * True when {@code a} is essentially the same line as {@code b} — identical once the
     * "-Update:" continuation marker + punctuation are stripped (the model re-emitting a
     * line as an update), or a light reword above {@link #DUP_SIM_THRESHOLD} token overlap
     * ("hat"→"hält"). Package-private for testing.
     */
    static boolean isNearDuplicate(String a, String b) {
        String na = normalizeForDup(a), nb = normalizeForDup(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb)) return true;
        return tokenJaccard(na, nb) >= DUP_SIM_THRESHOLD;
    }

    /** Lower-cased core of a headline: HTML, the "-Update:" marker and punctuation removed. */
    static String normalizeForDup(String s) {
        String t = stripHtml(s).toLowerCase(Locale.ROOT);
        t = t.replaceAll("(?i)-?\\s*update\\s*:", " ");          // drop the "-Update:" continuation label
        t = t.replaceAll("[^a-z0-9äöüß ]", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Jaccard token overlap of two normalised strings (0..1). */
    private static double tokenJaccard(String a, String b) {
        Set<String> ta = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> tb = new HashSet<>(Arrays.asList(b.split(" ")));
        if (ta.isEmpty() || tb.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size();
    }

    private static Double sanePriceMove(Double priceMove, String headline) {
        if (priceMove == null) return null;
        if (Math.abs(priceMove) > 500.0
                && headline.matches(".*\\d[\\d.,]*\\s*(€|\\$|EUR|USD).*")) {
            return null; // money amount + huge % ⇒ almost always a P&L misread
        }
        return priceMove;
    }

    private static final java.util.Set<HeadlineSentiment> BULLISH_CAMP = java.util.EnumSet.of(
            HeadlineSentiment.BULLISH, HeadlineSentiment.FOMO,
            HeadlineSentiment.BREAKOUT, HeadlineSentiment.SQUEEZE);
    private static final java.util.Set<HeadlineSentiment> BEARISH_CAMP = java.util.EnumSet.of(
            HeadlineSentiment.BEARISH, HeadlineSentiment.CAPITULATION);

    /** Only a move at least this large (in %) overrides the model's directional label. */
    private static final double SENTIMENT_FLIP_MIN_MOVE = 1.5;

    /**
     * Makes the directional read agree with the sign of the number the headline
     * itself carries — a line with a −% can't read BULLISH, and vice versa (a
     * reader feels lied to otherwise). The number is the line's own
     * {@code priceMovePercent}, which is the figure the line is ABOUT — Yahoo- OR
     * user-sourced, it doesn't matter (sentiment is sentiment: a posted −13% is
     * bearish regardless of whether Yahoo confirms it).
     *
     * <p>Deliberately does NOT fall back to the instrument's market day-move: a
     * loss-porn post is BEARISH even when the stock is green today, so the model's
     * own classification must stand when the line carries no move of its own. Only
     * flips a directional label that <em>contradicts</em> a <b>prominent</b> move
     * ({@link #SENTIMENT_FLIP_MIN_MOVE}); a tiny ±0.x% day-move must NOT drag a
     * bullish narrative ("+20% seit dem Tief") to BEARISH. Non-directional reads
     * (NEUTRAL/MIXED/REVERSAL) and number-less lines stay as the model set them.
     * <b>Never a publish gate</b> — it only corrects the label.
     */
    static HeadlineSentiment reconcileSentiment(HeadlineSentiment sentiment, Double priceMove) {
        if (priceMove == null || !Double.isFinite(priceMove)
                || Math.abs(priceMove) < SENTIMENT_FLIP_MIN_MOVE) {
            return sentiment;
        }
        if (priceMove < 0 && BULLISH_CAMP.contains(sentiment)) return HeadlineSentiment.BEARISH;
        if (priceMove > 0 && BEARISH_CAMP.contains(sentiment)) return HeadlineSentiment.BULLISH;
        return sentiment;
    }

    private static String sanitizeTicker(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim();
        clean = clean.startsWith("$") ? clean.substring(1) : clean;
        return clean.matches("[A-Z]{1,5}([.-][A-Z0-9]{1,3})?") ? clean : null;
    }

    private static String normalizeAssetClass(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (s.matches("stock|stocks|equity|equities|aktie|aktien|share|shares")) return "stock";
        if (s.matches("etf|etfs|fund|funds")) return "etf";
        if (s.matches("crypto|cryptocurrency|coin|coins|token|tokens|krypto")) return "crypto";
        if (s.matches("commodity|commodities|metal|metals|oil|gold|silver")) return "commodity";
        if (s.matches("forex|fx|currency|currencies|pair|pairs")) return "forex";
        if (s.matches("bond|bonds|treasury|treasuries|note|notes|yield")) return "bond";
        if (s.matches("option|options|call|put|warrant|warrants")) return "option";
        if (s.matches("other|misc")) return "other";
        return null;
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static Collector<String, ?, List<String>> distinctByLower() {
        return Collector.of(ArrayList::new,
                (acc, s) -> {
                    String key = s.toLowerCase(Locale.ROOT);
                    if (acc.stream().noneMatch(x -> x.toLowerCase(Locale.ROOT).equals(key))) {
                        acc.add(s);
                    }
                },
                (a, b) -> { a.addAll(b); return a; });
    }
}
