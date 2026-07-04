package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Persists one finished headline draft and broadcasts it to the UI. The
 * deterministic-pipeline replacement for {@code PublishHeadlineTool.execute}:
 * the editorial model produces a {@link Draft}, the {@link TickerResolver} has
 * already resolved the instruments, and this writer applies the
 * <em>quality</em> checks and saves.
 *
 * <p>The self-contained QA algorithms live in dedicated collaborators so the
 * publish flow reads as a linear recipe: trim ({@link HeadlineTailTrimmer}) →
 * dup-guard ({@link NearDuplicateGuard}) → gild ({@link HeadlineGilder}) →
 * highlight ({@link HighlightReconciler}) → sentiment ({@link SentimentReconciler}).
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

    private final AgentRepository agentRepository;
    private final ApplicationEventBus eventBus;

    @Inject
    public HeadlineWriter(AgentRepository agentRepository, ApplicationEventBus eventBus) {
        this.agentRepository = agentRepository;
        this.eventBus = eventBus;
    }

    /**
     * One headline as drafted by the editorial model — exactly the four fields the
     * schema-constrained compose output carries. Ticker, price and subjects are the
     * unit's resolver-validated facts and never the model's.
     */
    public record Draft(
            String headline,
            String sentiment,
            String highlight,
            String trigger) {
    }

    /**
     * Publishes a headline for a feed-wide {@link SubjectUnit} (the editorial atom
     * after the #2 cutover). The grouping key is the <b>unit id</b> (so story
     * continuity / dedup is per subject) and the ticker + market snapshot come from
     * the <b>unit</b> (resolver-validated), never from the model. Returns
     * {@code true} when saved + broadcast, {@code false} on blank text or the
     * near-duplicate guard. Never throws on bad model output.
     */
    public boolean publishUnit(SubjectUnit unit, Draft draft) {
        return publishUnit(unit, draft, List.of(), List.of());
    }

    public boolean publishUnit(SubjectUnit unit, Draft draft,
            List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> newsUsed) {
        return publishUnit(unit, draft, newsUsed, List.of());
    }

    /**
     * Same as {@link #publishUnit(SubjectUnit, Draft)} but records the news items the
     * line ACTUALLY leaned on: {@code newsUsed} is the woven-in subset (computed by the
     * caller against the published text), {@code inheritedRefs} are the sources carried
     * over from prior lines this one cites via {@code derivedFrom} (provenance
     * chaining). The "News" tag and its clickable source list promise "the articles
     * this line leans on", so a room-sentiment line on a news-rich subject carries
     * none.
     */
    public boolean publishUnit(SubjectUnit unit, Draft draft,
            List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> newsUsed,
            List<HeadlineNewsRef> inheritedRefs) {
        boolean newsEnriched = (newsUsed != null && !newsUsed.isEmpty())
                || (inheritedRefs != null && !inheritedRefs.isEmpty());
        if (unit == null || draft == null) return false;
        String headline = HeadlineTailTrimmer.trimInterpretiveTail(stripHtml(draft.headline()).trim());
        if (headline.isEmpty()) return false;

        // Near-duplicate guard over the recent WIRE, not just this unit. The 4B model often
        // re-emits the SAME line as an "-Update:" or a light reword ("hat"→"hält") on
        // fresh-but-story-redundant evidence, which a strict equals() misses — so compare
        // the NORMALISED core (strip the "-Update:" marker, punctuation AND numbers) with
        // token-similarity, not the raw string. The unit's own lines get the long window;
        // every other unit's lines get the shorter cross-unit window (one story told twice
        // by twin units the identity-merge didn't fold). Always on — the former strict-1:1
        // user toggle was removed 2026-07-03 with the judge-based dup check.
        long now = System.currentTimeMillis() / 1000;
        Optional<HeadlineRecord> dupOf = NearDuplicateGuard.findDuplicate(
                headline, unit.id, agentRepository.getRecentHeadlines(), now);
        if (dupOf.isPresent()) {
            LOG.info("[WRITE] skip near-duplicate for unit {} (matches {} from {}s ago): {}",
                    unit.id, dupOf.get().clusterId(), now - dupOf.get().createdAt(), headline);
            return false;
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
        String displayName = tickerSymbol == null ? null
                : HeadlineGilder.displayFormIn(headline, unit.canonicalName());
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
        HeadlineHighlight highlight =
                HighlightReconciler.reconcileHighlight(modelHighlight, draft.trigger(), unit.snapshot());
        if (highlight != modelHighlight) {
            LOG.info("[WRITE] demote IMPORTANT→NORMAL for unit {} — trigger '{}' {}",
                    unit.id, draft.trigger(),
                    HighlightReconciler.isPriceShaped(draft.trigger())
                            ? "is price-shaped but the unit has no verified price"
                            : "does not justify red");
        }

        // Price move is DERIVED from the resolver snapshot, not asked of the model.
        // Sentiment IS the model's (schema-forced enum since 2026-07-01): the slim
        // 3-key contract had left every line NEUTRAL, which starved the mood badge
        // and the unit's sentiment arc — the room's read is editorial data the
        // price can't supply. reconcileSentiment still flips a camp that
        // contradicts a hard day-move.
        Double priceMove = SentimentReconciler.sanePriceMove(
                snapshot != null && snapshot.hasPrice() ? snapshot.dayChangePercent() : null, headline);
        List<String> sectors = List.of();
        String assetClass = null;
        HeadlineSentiment sentiment = SentimentReconciler.reconcileSentiment(
                HeadlineSentiment.fromString(draft.sentiment()), priceMove);

        // The concrete articles behind the "News" provenance tag: ONLY the items the
        // line actually wove in, plus the sources inherited from cited prior lines
        // (provenance chaining) — so the click-open list keeps the tag's promise,
        // "the articles this line leans on", instead of dumping the subject's whole
        // news pool beside a room-sentiment line. De-duplicated by URL; items
        // without a link are useless as a source reference and are dropped.
        List<HeadlineNewsRef> newsRefs = buildNewsRefs(newsEnriched, newsUsed, inheritedRefs);

        agentRepository.saveHeadline(unit.id, headline, "",
                threadIds, commentIds, highlight, tickerSymbol, subjects, priceMove,
                sectors, assetClass, sentiment, snapshot, newsEnriched, newsRefs);

        LOG.info("[WRITE] unit {} [{}{} {}{}]: {}", unit.id,
                highlight == HeadlineHighlight.IMPORTANT
                        ? "IMPORTANT/" + HighlightReconciler.normalizeTrigger(draft.trigger()) : highlight,
                tickerSymbol == null ? "" : " " + tickerSymbol, sentiment,
                priceMove == null ? "" : String.format(Locale.ROOT, " %+.1f%%", priceMove),
                headline);

        eventBus.post(new AgentStreamEndEvent(
                "||PASSIVE||" + headline + "||REF||ID:" + unit.id));
        return true;
    }

    /**
     * The URL-deduped source list behind the "News" tag: the woven-in items first,
     * then the inherited refs from cited prior lines. Empty when the line leaned on
     * nothing. Extracted from {@code publishUnit}.
     */
    private static List<HeadlineNewsRef> buildNewsRefs(boolean newsEnriched,
            List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> newsUsed,
            List<HeadlineNewsRef> inheritedRefs) {
        if (!newsEnriched) return List.of();
        Map<String, HeadlineNewsRef> byUrl = new java.util.LinkedHashMap<>();
        if (newsUsed != null) {
            for (var n : newsUsed) {
                if (n.link() != null && !n.link().isBlank()) {
                    byUrl.putIfAbsent(n.link(), new HeadlineNewsRef(n.title(), n.publisher(),
                            n.link(), n.publishedAt() == null ? null : n.publishedAt().getEpochSecond()));
                }
            }
        }
        if (inheritedRefs != null) {
            for (HeadlineNewsRef ref : inheritedRefs) {
                if (ref.url() != null && !ref.url().isBlank()) byUrl.putIfAbsent(ref.url(), ref);
            }
        }
        return List.copyOf(byUrl.values());
    }

    private static String stripHtml(String s) {
        return s == null ? "" : HTML_TAG.matcher(s).replaceAll("");
    }
}
