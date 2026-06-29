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

    /**
     * News items carried per unit. Merged (not replaced) on every re-resolve so an
     * item a headline already leaned on can't vanish just because Yahoo's next
     * search returned a different set; capped so a long-lived unit doesn't hoard
     * a session's worth of headlines-context. Oldest items fall off first.
     */
    static final int MAX_NEWS = 12;

    /** Stable identity key — ticker (UPPER) for instruments, normalised name otherwise. */
    public final String id;
    public final Instant firstSeen;
    private volatile Instant lastActivity;

    private volatile String canonicalName;
    private volatile String ticker;            // null for theme/person
    private volatile MarketSnapshot snapshot;  // latest resolved
    private volatile List<RawNewsItem> news = List.of();

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
     * Monotonic counter bumped every time genuinely-new evidence is added (the same
     * trigger that marks the unit dirty). Paired with {@link #composedEvidenceVersion}
     * it answers "has anything actually changed since the last compose?" — the guard
     * that stops a unit from being re-composed when an in-flight copy already covered
     * its current evidence (the merge cadence would otherwise keep a unit dirty across
     * a long compose and fire a redundant, near-identical follow-up). NOT snapshotted:
     * a restored unit starts at 0/0 so its re-seeded story isn't re-published — only
     * fresh post-restart evidence (which bumps this) re-wakes it.
     */
    private long evidenceVersion;
    /** The {@link #evidenceVersion} the last completed compose ran against (see above). */
    private long composedEvidenceVersion;
    /** Wall-clock ms of the last completed compose — drives the per-unit compose cooldown (evidence batching). */
    private long lastComposedAtMs;
    /** Wall-clock ms the unit first became dirty since its last compose (0 = clean) — drives the settle delay. */
    private long dirtySinceMs;

    /** Headlines already published for this unit — context for the NEW/UPDATE call. */
    private final List<UnitHeadline> headlines = new ArrayList<>();

    /**
     * IDs of news items a published headline has already cited (#2 step 3b).
     * Source-agnostic — any news source (Yahoo now, finanznachrichten later) hands
     * a stable id here. A covered item is filtered out of the next compose so two
     * headlines never rest on the same piece of news.
     */
    private final Set<String> coveredNewsIds = new HashSet<>();

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
        this.firstSeen = Instant.ofEpochSecond(s.firstSeenEpoch());
        this.lastActivity = Instant.ofEpochSecond(s.lastActivityEpoch());
        this.firstPrice = s.firstPrice();
        this.firstPriceAt = s.firstPriceAtEpoch() == null ? null
                : Instant.ofEpochSecond(s.firstPriceAtEpoch());
        this.snapshot = s.snapshot();
        if (s.evidence() != null) {
            for (EvidenceRef e : s.evidence()) evidence.put(e.key(), e);
        }
        if (s.headlines() != null) headlines.addAll(s.headlines());
        if (s.coveredNewsIds() != null) coveredNewsIds.addAll(s.coveredNewsIds());
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
        this.news = mergeNews(this.news, news);
        Set<String> kept = new HashSet<>();
        for (RawNewsItem n : this.news) {
            if (n.uuid() != null) kept.add(n.uuid());
        }
        coveredNewsIds.retainAll(kept);
    }

    /** Existing ∪ fresh, deduped by uuid (existing wins), newest first, capped at {@link #MAX_NEWS}. */
    private static List<RawNewsItem> mergeNews(List<RawNewsItem> existing, List<RawNewsItem> fresh) {
        Map<String, RawNewsItem> byUuid = new LinkedHashMap<>();
        for (RawNewsItem n : existing) {
            if (n != null && n.uuid() != null && !n.uuid().isBlank()) byUuid.putIfAbsent(n.uuid(), n);
        }
        if (fresh != null) {
            for (RawNewsItem n : fresh) {
                if (n != null && n.uuid() != null && !n.uuid().isBlank()) byUuid.putIfAbsent(n.uuid(), n);
            }
        }
        List<RawNewsItem> merged = new ArrayList<>(byUuid.values());
        // Newest first; items without a timestamp sort last (oldest), so they're
        // the first to fall off the cap.
        merged.sort((a, b) -> {
            Instant pa = a.publishedAt();
            Instant pb = b.publishedAt();
            if (pa == null && pb == null) return 0;
            if (pa == null) return 1;
            if (pb == null) return -1;
            return pb.compareTo(pa);
        });
        return merged.size() <= MAX_NEWS ? merged : new ArrayList<>(merged.subList(0, MAX_NEWS));
    }

    /** Adds one piece of evidence; returns {@code true} if it was new (not a duplicate source). */
    public synchronized boolean addEvidence(EvidenceRef ref) {
        boolean added = evidence.putIfAbsent(ref.key(), ref) == null;
        if (added) {
            lastActivity = Instant.now();
            if (evidenceVersion == composedEvidenceVersion) dirtySinceMs = System.currentTimeMillis();
            evidenceVersion++;
        }
        return added;
    }

    public String canonicalName() { return canonicalName; }
    public String ticker() { return ticker; }
    public boolean isInstrument() { return ticker != null && !ticker.isBlank(); }
    public MarketSnapshot snapshot() { return snapshot; }
    public List<RawNewsItem> news() { return news; }
    public Instant lastActivity() { return lastActivity; }
    public Double firstPrice() { return firstPrice; }
    public Instant firstPriceAt() { return firstPriceAt; }

    public synchronized List<EvidenceRef> evidence() { return new ArrayList<>(evidence.values()); }
    public synchronized int evidenceCount() { return evidence.size(); }

    /** Current evidence version — snapshot it before composing, hand it back via {@link #markComposedAt}. */
    public synchronized long evidenceVersion() { return evidenceVersion; }

    /**
     * Records that a compose ran against version {@code version} (captured before the
     * compose started). Monotonic: a stale stamp from a slower path can't move it
     * backwards. After this, {@link #hasUncomposedEvidence()} is false until fresh
     * evidence bumps {@link #evidenceVersion} again — so a redundant recompose of the
     * same evidence is suppressed while a genuine update (new evidence, even mid-compose)
     * still re-fires.
     */
    public synchronized void markComposedAt(long version) {
        if (version > composedEvidenceVersion) composedEvidenceVersion = version;
        lastComposedAtMs = System.currentTimeMillis();
        dirtySinceMs = 0; // composed → clean; the next fresh evidence restarts the settle clock
    }

    /** Wall-clock ms of the last completed compose (0 = never) — for the compose cooldown. */
    public synchronized long lastComposedAtMs() { return lastComposedAtMs; }

    /** Wall-clock ms the unit first became dirty since its last compose (0 = clean) — for the settle delay. */
    public synchronized long dirtySinceMs() { return dirtySinceMs; }

    /** True when evidence has been added since the last completed compose — i.e. there's something new to say. */
    public synchronized boolean hasUncomposedEvidence() {
        return evidenceVersion > composedEvidenceVersion;
    }

    /** Source keys of this unit's evidence — used to detect a shared mention with another unit. */
    public synchronized Set<String> evidenceKeys() { return new HashSet<>(evidence.keySet()); }

    /** Absorbs another unit's evidence (and headline history) into this one. Used by identity-merge. */
    public synchronized void absorb(SubjectUnit other) {
        for (EvidenceRef ref : other.evidence()) addEvidence(ref);
        headlines.addAll(other.headlines());
    }

    /** Records a headline published for this unit (NEW or UPDATE), with its sentiment + the verified price at publish time. */
    public synchronized void addHeadline(String text, boolean update, String sentiment) {
        Double price = snapshot != null && snapshot.hasPrice() ? snapshot.price() : null;
        headlines.add(new UnitHeadline(text, update, Instant.now().getEpochSecond(),
                sentiment == null ? "" : sentiment, price));
        lastActivity = Instant.now();
    }

    /** Records a headline without sentiment/price metadata (older call sites, identity-merge). */
    public synchronized void addHeadline(String text, boolean update) {
        addHeadline(text, update, "");
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
        headlines.add(new UnitHeadline(text, false, atEpoch,
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
        return before - evidence.size();
    }

    public synchronized String lastHeadlineText() {
        return headlines.isEmpty() ? null : headlines.get(headlines.size() - 1).text();
    }

    /** Marks news ids as cited by a published headline — they won't be offered to the next compose. */
    public synchronized void markNewsCovered(Collection<String> ids) {
        if (ids == null) return;
        for (String id : ids) {
            if (id != null && !id.isBlank()) coveredNewsIds.add(id);
        }
    }

    public synchronized boolean isNewsCovered(String id) {
        return id != null && coveredNewsIds.contains(id);
    }

    public synchronized Set<String> coveredNewsIds() { return new HashSet<>(coveredNewsIds); }

    /** Captures the unit's full state for short-TTL session persistence (see the restore ctor). */
    public synchronized Snapshot toSnapshot() {
        return new Snapshot(id, canonicalName, ticker,
                firstSeen.getEpochSecond(), lastActivity.getEpochSecond(),
                firstPrice, firstPriceAt == null ? null : firstPriceAt.getEpochSecond(),
                snapshot,
                new ArrayList<>(evidence.values()),
                new ArrayList<>(headlines),
                new ArrayList<>(coveredNewsIds));
    }

    /**
     * Serializable form of a unit for {@code agent-snapshot.json}. Times are epoch
     * seconds (no Jackson time module needed). Live {@code news} is intentionally
     * absent — re-fetched from Yahoo on the next attribution (see the restore ctor).
     */
    public record Snapshot(
            String id, String canonicalName, String ticker,
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
    public record UnitHeadline(String text, boolean update, long atEpoch,
            String sentiment, Double priceAtTime) {

        /** Older call sites without story metadata. */
        public UnitHeadline(String text, boolean update, long atEpoch) {
            this(text, update, atEpoch, "", null);
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
