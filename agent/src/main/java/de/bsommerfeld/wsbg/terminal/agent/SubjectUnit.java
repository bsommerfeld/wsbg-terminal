package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One feed-wide editorial subject (#2, "Subjekt = Einheit"). Unlike a thread
 * cluster, a {@code SubjectUnit} accumulates evidence for ONE market subject —
 * an instrument, a theme, a person — from <em>every</em> thread/comment across
 * the whole feed. Evidence piles up here; headlines are generated <em>per
 * unit</em> from its accumulated story (NVIDIA with 5 mentions writes a
 * substantial line; a one-off list-mention is just one more data point).
 *
 * <p>Identity ({@link #id}): the ticker for instruments (so {@code NVIDIA},
 * {@code Nvidia}, {@code NVDA}, {@code Team Green} all fold into one), the
 * normalised canonical name for ticker-less themes/people.
 */
public final class SubjectUnit {

    /** Per-unit news cap — re-exported from {@link NewsBox#MAX_NEWS} for callers/tests. */
    static final int MAX_NEWS = NewsBox.MAX_NEWS;

    /** Stable identity key — ticker (UPPER) for instruments, normalised name otherwise. */
    public final String id;
    public final Instant firstSeen;
    private volatile Instant lastActivity;

    private volatile String canonicalName;
    private volatile String ticker;            // null for theme/person
    private volatile String isin;              // desk-stamped hard identity; null when unstamped
    private volatile MarketSnapshot snapshot;  // latest resolved

    /** News + covered-news-ids for this unit. Accessed only under this unit's monitor. */
    private final NewsBox newsBox = new NewsBox();

    /**
     * Price anchor: the first verified (Yahoo) price ever resolved for this unit,
     * and when. Survives every prune — it's what lets the wire say "seit erster
     * Erwähnung +12%" long after the original evidence aged out.
     */
    private volatile Double firstPrice;
    private volatile Instant firstPriceAt;

    /** Evidence keyed by thread/comment so the same source is never double-counted. */
    private final Map<String, EvidenceRef> evidence = new LinkedHashMap<>();

    /**
     * How long the unit REMEMBERS having seen an evidence key after the content
     * itself was pruned. Must comfortably outlive the Reddit repository's thread
     * retention ({@code reddit.data-retention-hours}, default 6 h): as long as the
     * source comment still exists, every re-prep re-delivers it — and without this
     * memory the prune (60 min TTL) wiped the dedupe key, so the SAME old comment
     * re-entered as "genuinely new" evidence, re-dirtied the unit and re-composed
     * it in an endless loop (the 2026-07-09 re-dirty bug). Once the comment has
     * aged out of the repository it can never be re-delivered, so forgetting the
     * key after 24 h is safe and bounds the memory.
     */
    static final Duration SEEN_RETENTION = Duration.ofHours(24);

    /**
     * Every evidence key this unit has EVER accepted (within {@link #SEEN_RETENTION}),
     * mapped to when it was first added — survives the content prune, which
     * {@link #evidence} deliberately does not. Guarded by this unit's monitor.
     */
    private final Map<String, Long> seenEvidence = new LinkedHashMap<>();

    /**
     * Evidence-version + compose-timing gate: "has anything changed since the last
     * compose / is it settled / cooling down?". NOT snapshotted — a restored unit
     * starts a fresh gate at 0/0 so its re-seeded story isn't re-published; only
     * fresh post-restart evidence re-wakes it. Mutated only under this unit's monitor.
     */
    private final ComposeGate composeGate = new ComposeGate();

    /** Headlines already published for this unit — context for the NEW/UPDATE call. */
    private final List<UnitHeadline> headlines = new ArrayList<>();

    public SubjectUnit(String id, String canonicalName) {
        this.id = id;
        this.canonicalName = canonicalName == null ? id : canonicalName;
        this.firstSeen = Instant.now();
        this.lastActivity = this.firstSeen;
    }

    /**
     * Restore ctor — rebuilds a unit verbatim from a {@link Snapshot} (short-TTL
     * session persistence, so a quick restart resumes the exact subject state:
     * accumulated evidence, published-headline history, the price anchor, and
     * covered-news ids). Live Yahoo {@code news} is deliberately NOT snapshotted —
     * it's enrichment that re-fetches on the next attribution, and freezing a
     * session-old quote/headline set would be staler than re-asking Yahoo; the
     * {@code coveredNewsIds} survive, so 3b dedup still holds by id.
     */
    public SubjectUnit(Snapshot s) {
        this.id = s.id();
        this.canonicalName = s.canonicalName() == null ? s.id() : s.canonicalName();
        this.ticker = s.ticker();
        this.isin = s.isin();
        this.firstSeen = Instant.ofEpochSecond(s.firstSeenEpoch());
        this.lastActivity = Instant.ofEpochSecond(s.lastActivityEpoch());
        this.firstPrice = s.firstPrice();
        this.firstPriceAt = s.firstPriceAtEpoch() == null ? null
                : Instant.ofEpochSecond(s.firstPriceAtEpoch());
        this.snapshot = s.snapshot();
        if (s.evidence() != null) {
            for (EvidenceRef e : s.evidence()) {
                evidence.put(e.key(), e);
                // Seed the seen-memory from the restored refs so the first prune
                // after a restart can't re-open the re-dirty loop for them.
                seenEvidence.put(e.key(), e.addedAtEpoch());
            }
        }
        if (s.headlines() != null) headlines.addAll(s.headlines());
        newsBox.restore(s.coveredNewsIds());
    }

    /**
     * Refreshes the unit's resolved Yahoo data (ticker, snapshot, news). News is
     * <b>merged by uuid</b>, never replaced: items the unit already holds stay (a
     * cited article must not vanish because a later search returned other hits),
     * fresh items are added, and the list is capped at {@link #MAX_NEWS} dropping
     * the oldest. Covered-ids of items that fell off the cap are dropped with them.
     * The first price ever seen is pinned as the unit's price anchor.
     *
     * <p>A {@code null} snapshot does NOT clear the last good one — a transient
     * resolve miss (Yahoo rate-limit, or the 02:00–07:30 price-gap where the chain
     * deliberately skips fetching) must not wipe the headline's chart/price.
     */
    public synchronized void updateResolved(String canonicalName, String ticker,
            MarketSnapshot snapshot, List<RawNewsItem> news) {
        if (canonicalName != null && !canonicalName.isBlank()) this.canonicalName = canonicalName;
        this.ticker = ticker;
        if (snapshot != null) this.snapshot = snapshot; // keep last good price when this resolve had none
        if (firstPrice == null && snapshot != null && snapshot.hasPrice()) {
            firstPrice = snapshot.price();
            firstPriceAt = Instant.now();
        }
        newsBox.merge(news);
    }

    /**
     * Adds one piece of evidence; returns {@code true} if it was new (not a
     * duplicate source). "New" is judged against {@link #seenEvidence}, not just
     * the live {@link #evidence} map: a key whose content the TTL prune dropped
     * but that the unit has already seen is a RE-DELIVERY (the source thread still
     * lives in the repository), not news — re-accepting it as fresh was the
     * re-dirty loop. Such a re-delivery is ignored entirely (not re-inserted):
     * the prune dropped that content deliberately, for context relief.
     */
    public synchronized boolean addEvidence(EvidenceRef ref) {
        String key = ref.key();
        if (seenEvidence.containsKey(key) && !evidence.containsKey(key)) {
            return false; // seen before, content pruned — a re-delivery is not new evidence
        }
        boolean added = evidence.putIfAbsent(key, ref) == null;
        if (added) {
            seenEvidence.put(key, ref.addedAtEpoch());
            lastActivity = Instant.now();
            composeGate.onEvidenceAdded();
        }
        return added;
    }

    /**
     * Notes the desk-stamped ISIN (last verdict wins; a stampless re-resolve never
     * clears it). The hard identity fact behind the ticker: the registry's
     * identity-merge folds two ticker units whose ISINs agree — a WKN-keyed venue
     * unit and its Yahoo-keyed twin are the SAME paper.
     */
    public void noteIsin(String isin) {
        if (isin != null && !isin.isBlank()) this.isin = isin;
    }

    public String isin() { return isin; }

    public String canonicalName() { return canonicalName; }
    public String ticker() { return ticker; }
    public boolean isInstrument() { return ticker != null && !ticker.isBlank(); }
    public MarketSnapshot snapshot() { return snapshot; }
    public List<RawNewsItem> news() { return newsBox.news(); }
    public Instant lastActivity() { return lastActivity; }
    public Double firstPrice() { return firstPrice; }
    public Instant firstPriceAt() { return firstPriceAt; }

    public synchronized List<EvidenceRef> evidence() { return new ArrayList<>(evidence.values()); }
    public synchronized int evidenceCount() { return evidence.size(); }

    /** False until this unit's FIRST headline publishes — lets the dispatch give a never-seen subject priority. */
    public synchronized boolean hasPublishedHeadline() { return !headlines.isEmpty(); }

    /** Current evidence version — snapshot it before composing, hand it back via {@link #markComposedAt}. */
    public synchronized long evidenceVersion() { return composeGate.evidenceVersion(); }

    /**
     * Records that a compose ran against version {@code version} (captured before the
     * compose started). Monotonic: a stale stamp from a slower path can't move it
     * backwards. After this, {@link #hasUncomposedEvidence()} is false until fresh
     * evidence bumps the evidence version again — so a redundant recompose of the
     * same evidence is suppressed while a genuine update (new evidence, even mid-compose)
     * still re-fires.
     */
    public synchronized void markComposedAt(long version) {
        composeGate.markComposedAt(version);
    }

    /** Wall-clock ms of the last completed compose (0 = never) — for the compose cooldown. */
    public synchronized long lastComposedAtMs() { return composeGate.lastComposedAtMs(); }

    /** Wall-clock ms the unit first became dirty since its last compose (0 = clean) — for the settle delay. */
    public synchronized long dirtySinceMs() { return composeGate.dirtySinceMs(); }

    /** True when evidence has been added since the last completed compose — i.e. there's something new to say. */
    public synchronized boolean hasUncomposedEvidence() {
        return composeGate.hasUncomposedEvidence();
    }

    /** Source keys of this unit's evidence — used to detect a shared mention with another unit. */
    public synchronized Set<String> evidenceKeys() { return new HashSet<>(evidence.keySet()); }

    /** Absorbs another unit's evidence (and headline history) into this one. Used by identity-merge. */
    public synchronized void absorb(SubjectUnit other) {
        for (EvidenceRef ref : other.evidence()) addEvidence(ref);
        // The victim's seen-memory rides along: keys it already saw (even ones whose
        // content its prune dropped) must not re-dirty the absorber on the next re-prep.
        for (Map.Entry<String, Long> seen : other.seenEvidenceSnapshot().entrySet()) {
            seenEvidence.putIfAbsent(seen.getKey(), seen.getValue());
        }
        headlines.addAll(other.headlines());
    }

    /** Copy of the seen-evidence memory — for {@link #absorb} (lock order: absorber, then victim). */
    private synchronized Map<String, Long> seenEvidenceSnapshot() {
        return new LinkedHashMap<>(seenEvidence);
    }

    /** Records a headline published for this unit, with its sentiment + the verified price at publish time. */
    public synchronized void addHeadline(String text, String sentiment) {
        Double price = snapshot != null && snapshot.hasPrice() ? snapshot.price() : null;
        headlines.add(new UnitHeadline(text, Instant.now().getEpochSecond(),
                sentiment == null ? "" : sentiment, price));
        lastActivity = Instant.now();
    }

    /** Records a headline without sentiment/price metadata (older call sites, identity-merge). */
    public synchronized void addHeadline(String text) {
        addHeadline(text, "");
    }

    /**
     * Seeds a prior headline from the permanent archive on a cold restart,
     * preserving its ORIGINAL publish time ({@code atEpoch}) — not "now". The
     * timestamp matters: the brief's covered-evidence boundary is the unit's
     * latest headline time, so a seeded headline must carry its real time or
     * freshly-fetched evidence would wrongly read as already-covered. Does not
     * bump {@code lastActivity} (hydration isn't fresh activity).
     */
    public synchronized void seedHeadline(String text, String sentiment, long atEpoch) {
        headlines.add(new UnitHeadline(text, atEpoch,
                sentiment == null ? "" : sentiment, null));
    }

    public synchronized List<UnitHeadline> headlines() { return new ArrayList<>(headlines); }

    /**
     * Drops already-consumed <b>evidence</b> older than {@code maxAge} while keeping
     * the unit itself alive (identity, ticker, latest snapshot, price anchor stay).
     * A rolling context window: the model is never fed hour-old comments.
     *
     * <p>Published headlines are deliberately <b>not</b> pruned: they are the unit's
     * story memory. Wiping them made the unit look like "no prior headlines" after
     * the TTL, and the compose prompt's "no prior headlines → ALWAYS write a line"
     * rule then re-published the hour-old story verbatim as NEW. They're ~20 words
     * each and the brief renders only the last few plus a digest, so keeping all of
     * them costs almost nothing. Returns how many evidence entries were dropped.
     */
    public synchronized int pruneOlderThan(Duration maxAge) {
        long cutoff = Instant.now().minus(maxAge).getEpochSecond();
        int before = evidence.size();
        evidence.values().removeIf(e -> e.addedAtEpoch() < cutoff);
        // The seen-keys survive the CONTENT prune on purpose (they are what stops a
        // pruned-but-still-live comment from re-entering as "new") and age out on
        // their own, longer horizon — see SEEN_RETENTION.
        long seenCutoff = Instant.now().minus(SEEN_RETENTION).getEpochSecond();
        seenEvidence.values().removeIf(at -> at < seenCutoff);
        return before - evidence.size();
    }

    public synchronized String lastHeadlineText() {
        return headlines.isEmpty() ? null : headlines.get(headlines.size() - 1).text();
    }

    /** Marks news ids as cited by a published headline — they won't be offered to the next compose. */
    public synchronized void markNewsCovered(Collection<String> ids) {
        newsBox.markCovered(ids);
    }

    public synchronized boolean isNewsCovered(String id) {
        return newsBox.isCovered(id);
    }

    public synchronized Set<String> coveredNewsIds() { return newsBox.coveredIdsCopy(); }

    /** Captures the unit's full state for short-TTL session persistence (see the restore ctor). */
    public synchronized Snapshot toSnapshot() {
        return new Snapshot(id, canonicalName, ticker, isin,
                firstSeen.getEpochSecond(), lastActivity.getEpochSecond(),
                firstPrice, firstPriceAt == null ? null : firstPriceAt.getEpochSecond(),
                snapshot,
                new ArrayList<>(evidence.values()),
                new ArrayList<>(headlines),
                new ArrayList<>(newsBox.coveredIdsCopy()));
    }

    /**
     * Serializable form of a unit for {@code agent-snapshot.json}. Times are epoch
     * seconds (no Jackson time module needed). Live {@code news} is intentionally
     * absent — re-fetched from Yahoo on the next attribution (see the restore ctor).
     */
    public record Snapshot(
            String id, String canonicalName, String ticker, String isin,
            long firstSeenEpoch, long lastActivityEpoch,
            Double firstPrice, Long firstPriceAtEpoch,
            MarketSnapshot snapshot,
            List<EvidenceRef> evidence,
            List<UnitHeadline> headlines,
            List<String> coveredNewsIds) {
    }

    /**
     * One headline this unit published. {@code sentiment} is the compose stage's
     * classification (BULLISH/BEARISH/…, may be empty), {@code priceAtTime} the
     * verified price when the line went out ({@code null} if none) — together they
     * are the unit's story arc, cheap enough to keep forever.
     */
    public record UnitHeadline(String text, long atEpoch,
            String sentiment, Double priceAtTime) {

        /** Older call sites without story metadata. */
        public UnitHeadline(String text, long atEpoch) {
            this(text, atEpoch, "", null);
        }
    }

    /**
     * One mention of the subject: where it was said (thread, optional comment),
     * a short snippet of the saying, and which source it came from (reddit,
     * later: a financial-news feed). {@code commentId == null} = the post body.
     */
    public record EvidenceRef(String threadId, String commentId, String snippet,
            String source, long addedAtEpoch) {
        public String key() {
            return threadId + "/" + (commentId == null ? "_post" : commentId);
        }
    }
}
