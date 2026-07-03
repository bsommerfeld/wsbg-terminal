package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef;
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
    /** Skip a NEAR-duplicate of a unit's OWN recent headline within this window. Much longer
     *  than the rapid-double guard: with the compose settle/cooldown, a unit's re-composes are
     *  spaced minutes apart, and a hot thread re-dirties its unit all session — the ^GDAXI wire
     *  once carried 7 near-identical "wartet auf Katalysator" lines in 35 min, and re-tells of
     *  an unmoved story still surfaced 1.5h apart (SIVE.ST, live 2026-07-02). An EXACT match
     *  (normalised-equal) is checked against the whole 24h wire regardless of this window —
     *  zero new tokens is zero development, however much time passed. */
    private static final long NEAR_DUP_GUARD_SECS = 7200;
    /** Skip a NEAR-duplicate of ANY other unit's recent headline within this (shorter) window.
     *  Two units can legitimately tell one story from two angles hours apart, but the same
     *  sentence twice within half an hour is one story published twice — the merz/friedrich-merz
     *  twin units once wrote the same Reformpaket line 5 min apart, invisible to the per-unit
     *  guard. */
    private static final long CROSS_UNIT_DUP_GUARD_SECS = 1800;

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
        String headline = trimInterpretiveTail(stripHtml(draft.headline()).trim());
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
        return publishUnit(unit, draft, List.of(), true);
    }

    /**
     * Same as {@link #publishUnit(SubjectUnit, Draft)} but records the news items the
     * line ACTUALLY leaned on ({@code newsUsed} — the woven-in subset, computed by the
     * caller against the published text; the "News" tag and its clickable source list
     * promise "the articles this line leans on", so a room-sentiment line on a
     * news-rich subject carries none), and honours the {@code suppressRedundant}
     * setting: when false the near-duplicate guard is skipped so a strict 1:1 mirror
     * publishes every dirty line, even a duplicate.
     */
    public boolean publishUnit(SubjectUnit unit, Draft draft,
            List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> newsUsed, boolean suppressRedundant) {
        boolean newsEnriched = newsUsed != null && !newsUsed.isEmpty();
        if (unit == null || draft == null) return false;
        String headline = trimInterpretiveTail(stripHtml(draft.headline()).trim());
        if (headline.isEmpty()) return false;

        // Near-duplicate guard over the recent WIRE, not just this unit. The 4B model often
        // re-emits the SAME line as an "-Update:" or a light reword ("hat"→"hält") on
        // fresh-but-story-redundant evidence, which a strict equals() misses — so compare
        // the NORMALISED core (strip the "-Update:" marker, punctuation AND numbers) with
        // token-similarity, not the raw string. The unit's own lines get the long window;
        // every other unit's lines get the shorter cross-unit window (one story told twice
        // by twin units the identity-merge didn't fold). Skipped entirely in strict 1:1 mode.
        long now = System.currentTimeMillis() / 1000;
        if (suppressRedundant) {
            String normNew = normalizeForDup(headline);
            var dupOf = agentRepository.getRecentHeadlines().stream()
                    .filter(h -> normNew.equals(normalizeForDup(h.headline())) // exact: whole 24h wire
                            || ((now - h.createdAt())
                                    < (unit.id.equals(h.clusterId()) ? NEAR_DUP_GUARD_SECS : CROSS_UNIT_DUP_GUARD_SECS)
                                && isNearDuplicate(headline, h.headline())))
                    .findFirst();
            if (dupOf.isPresent()) {
                LOG.info("[WRITE] skip near-duplicate for unit {} (matches {} from {}s ago): {}",
                        unit.id, dupOf.get().clusterId(), now - dupOf.get().createdAt(), headline);
                return false;
            }
        }

        // Ticker + snapshot are the unit's resolver-validated facts, not the model's —
        // but they attach ONLY when the line actually names the unit (a form of the
        // canonical name appears in the text). A line that never names the subject is
        // either a mis-attributed compose or a mis-resolved theme-word unit (SPCX once
        // carried a Daiwa-Kursziel line, RCT.AX a "Casino-Sektor" line); shipping the
        // ticker + price strip beside it binds a quote to a story the reader can't map.
        // The line still publishes — the mirror stays 1:1 — it just carries no claim
        // to the instrument it didn't name.
        String tickerSymbol = unit.isInstrument()
                ? unit.ticker().toUpperCase(Locale.ROOT) : null;
        String displayName = tickerSymbol == null ? null : displayFormIn(headline, unit.canonicalName());
        if (tickerSymbol != null && displayName == null) {
            LOG.info("[WRITE] line never names unit {} ({}) — publishing without ticker/snapshot: {}",
                    unit.id, unit.canonicalName(), headline);
            tickerSymbol = null;
        }
        MarketSnapshot snapshot = tickerSymbol == null ? null : unit.snapshot();

        // The slim compose output is just {headline, highlight, mode} — the model no longer
        // cites source ids; the unit IS the evidence, so we don't track per-headline citations.
        List<String> threadIds = List.of();
        List<String> commentIds = List.of();

        // The subject for the UI glow is the unit itself, in the display form the line
        // actually wrote. The canonical name is Yahoo's LEGAL form ("Salesforce, Inc.")
        // while the line writes the short one ("Salesforce"), so the gild takes
        // the longest word-prefix of the canonical name the line actually contains.
        List<HeadlineSubject> subjects = new ArrayList<>();
        if (tickerSymbol != null) {
            subjects.add(new HeadlineSubject(displayName, tickerSymbol));
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

        // The concrete articles behind the "News" provenance tag: ONLY the items the
        // line actually wove in (title + publisher + permalink), so the click-open
        // list keeps the tag's promise — "the articles this line leans on" — instead
        // of dumping the subject's whole news pool beside a room-sentiment line.
        // Items without a link are useless as a source reference and are dropped.
        List<HeadlineNewsRef> newsRefs = !newsEnriched ? List.of() : newsUsed.stream()
                .filter(n -> n.link() != null && !n.link().isBlank())
                .map(n -> new HeadlineNewsRef(n.title(), n.publisher(), n.link(),
                        n.publishedAt() == null ? null : n.publishedAt().getEpochSecond()))
                .toList();

        agentRepository.saveHeadline(unit.id, headline, "",
                threadIds, commentIds, highlight, tickerSymbol, subjects, priceMove,
                sectors, assetClass, sentiment, snapshot, newsEnriched, newsRefs);

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
            "the", "and", "der", "die", "das", "inc", "corp", "corporation", "company",
            "incorporated", "limited", "ltd", "plc", "group", "gruppe", "holding", "holdings",
            "aktiengesellschaft", "ucits", "etf");

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
        // A leading article makes every prefix start with it, so "The Wendy's
        // Company" could never gild the line's "Wendy's" — strip it up front.
        // A domain suffix on the first word never appears in a written line
        // ("Amazon.com, Inc." — the line writes "Amazon"), so it goes too.
        String name = canonicalName.trim()
                .replaceFirst("(?i)^(the|der|die|das)\\s+", "")
                .replaceFirst("(?i)^([a-z0-9-]+)\\.(com|de|net|org)\\b", "$1");
        String form = prefixFormIn(headline, name.split("\\s+"));
        if (form != null) return form;
        // A line often drops a brand/wrapper first word entirely ("iShares Core MSCI
        // EM IMI …" is written as "Core MSCI EM IMI"): one retry without it. Only for
        // names long enough that the remainder still identifies the entity.
        String[] words = name.split("\\s+");
        return words.length >= 3
                ? prefixFormIn(headline, Arrays.copyOfRange(words, 1, words.length)) : null;
    }

    /** Longest word-prefix of {@code words} that appears in the headline, or null. */
    private static String prefixFormIn(String headline, String[] words) {
        String headlineLower = headline.toLowerCase(Locale.ROOT);
        for (int k = words.length; k >= 1; k--) {
            String cand = String.join(" ", Arrays.copyOfRange(words, 0, k))
                    .replaceAll("[,.]+$", "").trim();
            if (cand.length() < 3) continue;
            if (k == 1 && GILD_STOP.contains(cand.toLowerCase(Locale.ROOT))) continue;
            int idx = headlineLower.indexOf(cand.toLowerCase(Locale.ROOT));
            // Word-boundary guard: "Aris" must not gild inside "Paris". A single
            // trailing "s" is the German genitive ("Rheinmetalls Auftrag") and
            // still counts as a boundary — the name IS in the line.
            if (idx >= 0
                    && (idx == 0 || !Character.isLetterOrDigit(headline.charAt(idx - 1)))
                    && isWordEndAt(headline, idx + cand.length())) {
                return headline.substring(idx, idx + cand.length());
            }
        }
        return null;
    }

    /** True when position {@code end} closes a word: end of string, a non-letter, or a
     *  lone genitive "s" followed by one of those ("Rheinmetalls", "Teslas"). */
    private static boolean isWordEndAt(String headline, int end) {
        if (end >= headline.length()) return true;
        char c = headline.charAt(end);
        if (!Character.isLetterOrDigit(c)) return true;
        return (c == 's' || c == 'S')
                && (end + 1 >= headline.length()
                    || !Character.isLetterOrDigit(headline.charAt(end + 1)));
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

    /** A trailing ", was/wodurch/womit …" clause up to the end of the line. */
    private static final Pattern INTERPRETIVE_TAIL =
            Pattern.compile(",\\s*(was|wodurch|womit)\\s[^,]*$", Pattern.CASE_INSENSITIVE);
    /** A clause carrying any of these is DETAIL, not interpretation — never cut. */
    private static final Pattern TAIL_CONCRETE = Pattern.compile("[0-9%€$£]");
    /** The head must still be a full wire line after the cut. */
    private static final int TAIL_MIN_HEAD_WORDS = 6;

    /**
     * Deterministic backstop for the abstraction-ladder rule: the 4B model keeps
     * appending an interpretation clause to an otherwise concrete line ("…, was die
     * Aufmerksamkeit auf den Halbleitersektor lenkt") — measured live at a stable
     * ~20 % of lines (21 % before a prompt rule against it, 19 % after: prose rules
     * bend, a mechanical gate holds — the trigger-gate lesson). The clause construes
     * in the abstract what the concrete head already showed. Cuts it ONLY when it
     * carries no concrete token (no digit, no %, no currency — a figure-bearing
     * clause is detail, not interpretation) and the remaining head is still a full
     * line. Package-private for testing.
     */
    static String trimInterpretiveTail(String headline) {
        if (headline == null || headline.isEmpty()) return headline;
        var m = INTERPRETIVE_TAIL.matcher(headline);
        if (!m.find()) return headline;
        String clause = headline.substring(m.start());
        if (TAIL_CONCRETE.matcher(clause).find()) return headline;
        String head = headline.substring(0, m.start()).stripTrailing();
        if (head.split("\\s+").length < TAIL_MIN_HEAD_WORDS) return headline;
        // Re-close the sentence the way the line would have ended.
        String trimmed = head.endsWith(".") || head.endsWith("!") ? head : head + ".";
        LOG.info("[WRITE] trimmed interpretive tail: \"{}\" → \"{}\"", clause.strip(), trimmed);
        return trimmed;
    }

    /** Token-overlap above which two normalised headlines count as the same line. */
    private static final double DUP_SIM_THRESHOLD = 0.8;

    /**
     * True when {@code a} is essentially the same line as {@code b} — identical once the
     * "-Update:" continuation marker, punctuation and numbers are stripped (the model
     * re-emitting a line as an update or with a ticked day-move), a light reword above
     * {@link #DUP_SIM_THRESHOLD} token overlap ("hat"→"hält"), or one line CONTAINING the
     * other (the model re-emitting a prior line with an appended clause — Jaccard alone
     * goes blind there because the union grows: the MU wire once carried the same
     * GM-Chip line three times, twice with a tacked-on subclause). Package-private for
     * testing.
     */
    static boolean isNearDuplicate(String a, String b) {
        String na = normalizeForDup(a), nb = normalizeForDup(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb)) return true;
        Set<String> ta = new HashSet<>(Arrays.asList(na.split(" ")));
        Set<String> tb = new HashSet<>(Arrays.asList(nb.split(" ")));
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        double jaccard = (double) inter.size() / union.size();
        double containment = (double) inter.size() / Math.min(ta.size(), tb.size());
        return Math.max(jaccard, containment) >= DUP_SIM_THRESHOLD;
    }

    /**
     * Lower-cased core of a headline: HTML, the "-Update:" marker, punctuation and
     * NUMBERS removed. Numbers go because the day-move ticking is not a story
     * development — the ^GDAXI unit once published the same "wartet auf Katalysator"
     * line at +1,66 %, +1,78 % and +2,01 %; the quote strip carries the live figure
     * anyway. A genuinely new number (a price target, a contract volume) arrives with
     * new WORDS around it, which the token comparison still sees.
     */
    static String normalizeForDup(String s) {
        String t = stripHtml(s).toLowerCase(Locale.ROOT);
        t = t.replaceAll("(?i)-?\\s*update\\s*:", " ");          // drop the "-Update:" continuation label
        t = t.replaceAll("[^a-z0-9äöüß ]", " ");
        t = t.replaceAll("\\b\\d+\\b", " ");                     // numbers are not developments
        return t.replaceAll("\\s+", " ").trim();
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
