package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooNewsItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Stable identity key — ticker (UPPER) for instruments, normalised name otherwise. */
    public final String id;
    public final Instant firstSeen;
    private volatile Instant lastActivity;

    private volatile String canonicalName;
    private volatile String ticker;            // null for theme/person
    private volatile MarketSnapshot snapshot;  // latest resolved
    private volatile List<YahooNewsItem> news = List.of();

    /** Evidence keyed by thread/comment so the same source is never double-counted. */
    private final Map<String, EvidenceRef> evidence = new LinkedHashMap<>();

    public SubjectUnit(String id, String canonicalName) {
        this.id = id;
        this.canonicalName = canonicalName == null ? id : canonicalName;
        this.firstSeen = Instant.now();
        this.lastActivity = this.firstSeen;
    }

    /** Refreshes the unit's resolved Yahoo data (ticker, snapshot, news). */
    public synchronized void updateResolved(String canonicalName, String ticker,
            MarketSnapshot snapshot, List<YahooNewsItem> news) {
        if (canonicalName != null && !canonicalName.isBlank()) this.canonicalName = canonicalName;
        this.ticker = ticker;
        this.snapshot = snapshot;
        this.news = news == null ? List.of() : news;
    }

    /** Adds one piece of evidence; returns {@code true} if it was new (not a duplicate source). */
    public synchronized boolean addEvidence(EvidenceRef ref) {
        boolean added = evidence.putIfAbsent(ref.key(), ref) == null;
        if (added) lastActivity = Instant.now();
        return added;
    }

    public String canonicalName() { return canonicalName; }
    public String ticker() { return ticker; }
    public boolean isInstrument() { return ticker != null && !ticker.isBlank(); }
    public MarketSnapshot snapshot() { return snapshot; }
    public List<YahooNewsItem> news() { return news; }
    public Instant lastActivity() { return lastActivity; }

    public synchronized List<EvidenceRef> evidence() { return new ArrayList<>(evidence.values()); }
    public synchronized int evidenceCount() { return evidence.size(); }

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
