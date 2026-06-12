package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The live headline wire: an in-memory window (24h soft TTL) over the
 * <b>permanent</b> {@link HeadlineArchive}. Every accepted headline is appended
 * to the archive (append-only JSONL, never deleted), and on startup the wire
 * re-seeds itself from the archive's last 24h — so published output survives
 * any restart, not just the short snapshot TTL. Only the <em>Reddit-derived</em>
 * state (threads, clusters, evidence) stays session-bound: that data goes stale
 * against the live feed; our own output never does. Coverage and ticker-throttle
 * checks read this cache, so they too survive restarts. Stable cluster ids
 * (= initial thread id) keep restored records linked to re-seeded clusters.
 *
 * <p>
 * Each headline can optionally carry source attribution
 * ({@link HeadlineRecord#sourceThreadIds()} / {@link HeadlineRecord#sourceCommentIds()})
 * and a {@link HeadlineHighlight} for UI styling — pennystock rockets and
 * breaking moves get loud rendering, routine activity stays muted.
 */
@Singleton
public class AgentRepository {

    /** Soft TTL of the live wire — the permanent history lives in {@link HeadlineArchive}. */
    private static final long TTL_SECONDS = 86400;

    private final List<HeadlineRecord> headlineCache = new CopyOnWriteArrayList<>();

    /** Permanent archive behind the wire; {@code null} in archive-less tests. */
    private final HeadlineArchive archive;

    @Inject
    public AgentRepository(HeadlineArchive archive) {
        this.archive = archive;
        if (archive != null) {
            // Re-seed the wire window from permanent history: headlines outlive
            // every restart, coverage + dedupe + ticker-throttle keep working.
            headlineCache.addAll(archive.recent(Duration.ofSeconds(TTL_SECONDS)));
        }
    }

    /** Archive-less store for tests and ad-hoc tooling — session-only, nothing persisted. */
    public AgentRepository() {
        this.archive = null;
    }

    /** Records a headline without source attribution and NEUTRAL sentiment. */
    public void saveHeadline(String clusterId, String headline, String context) {
        saveHeadline(clusterId, headline, context, List.of(), List.of(),
                HeadlineHighlight.NORMAL, null, List.of(), null, List.of(), null,
                HeadlineSentiment.NEUTRAL, null);
    }

    /**
     * Records a headline with explicit attribution, editorial flags, and the
     * crowd-sentiment classifier. Sectors + assetClass are neutral chips for
     * filtering; sentiment is the one chip that lights up coloured — it
     * encodes how the room is positioned, which is the single most useful
     * scan-by signal on a noisy wire.
     *
     * @param sourceThreadIds  thread IDs the agent leaned on (may be empty)
     * @param sourceCommentIds comment IDs the agent leaned on (may be empty)
     * @param highlight        editorial significance — drives row styling
     * @param tickerSymbol     PRIMARY symbol the headline centres on (drives
     *                         cooldown / dedup logic), or {@code null}
     * @param subjects         every named instrument visible in the headline
     *                         text, with its ticker — the UI wraps each name
     *                         with a glow + hover-flip animation. Includes
     *                         the primary subject; may have more for multi-
     *                         instrument headlines ("CRWD, PANW und ZS").
     * @param priceMovePercent signed % move tied to the headline, or {@code null}
     * @param sectors          industry chips (e.g. ["Semiconductors", "AI"])
     * @param assetClass       one of stock / etf / crypto / commodity / forex / bond / option
     * @param sentiment        crowd-mood enum — drives the coloured chip
     * @param snapshot         live Yahoo market snapshot for the primary
     *                         ticker (price, day move, sparkline series), or
     *                         {@code null} when there's no ticker or Yahoo
     *                         had nothing — the UI renders the quote strip
     *                         only when present
     */
    public void saveHeadline(String clusterId, String headline, String context,
            List<String> sourceThreadIds, List<String> sourceCommentIds,
            HeadlineHighlight highlight, String tickerSymbol,
            List<HeadlineSubject> subjects,
            Double priceMovePercent,
            List<String> sectors, String assetClass, HeadlineSentiment sentiment,
            MarketSnapshot snapshot) {
        long now = System.currentTimeMillis() / 1000;
        HeadlineRecord record = new HeadlineRecord(
                clusterId,
                headline,
                context,
                now,
                sourceThreadIds == null ? List.of() : List.copyOf(sourceThreadIds),
                sourceCommentIds == null ? List.of() : List.copyOf(sourceCommentIds),
                highlight == null ? HeadlineHighlight.NORMAL : highlight,
                tickerSymbol,
                subjects == null ? List.of() : List.copyOf(subjects),
                priceMovePercent,
                sectors == null ? List.of() : List.copyOf(sectors),
                assetClass,
                sentiment == null ? HeadlineSentiment.NEUTRAL : sentiment,
                snapshot);
        headlineCache.add(record);
        if (archive != null) archive.append(record); // permanent — survives everything
    }

    /** Returns every cached headline (for persistence snapshots). */
    public List<HeadlineRecord> getAllHeadlines() {
        return new java.util.ArrayList<>(headlineCache);
    }

    /**
     * Restores persisted headlines, skipping any whose ID-less identity
     * (clusterId + createdAt) already exists so a restore is idempotent.
     */
    public void restoreHeadlines(List<HeadlineRecord> records) {
        if (records == null || records.isEmpty()) return;
        for (HeadlineRecord r : records) {
            boolean dup = headlineCache.stream().anyMatch(h ->
                    h.createdAt() == r.createdAt()
                            && java.util.Objects.equals(h.clusterId(), r.clusterId())
                            && java.util.Objects.equals(h.headline(), r.headline()));
            if (!dup) headlineCache.add(r);
        }
    }

    /** Returns headlines from the last 24 hours, newest first. */
    public List<HeadlineRecord> getRecentHeadlines() {
        long cutoff = (System.currentTimeMillis() / 1000) - TTL_SECONDS;
        return headlineCache.stream()
                .filter(h -> h.createdAt() >= cutoff)
                .sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt()))
                .toList();
    }

    /**
     * Returns all headlines for a specific cluster, oldest first.
     */
    public List<HeadlineRecord> getHeadlinesByClusterId(String clusterId) {
        long cutoff = (System.currentTimeMillis() / 1000) - TTL_SECONDS;
        return headlineCache.stream()
                .filter(h -> h.createdAt() >= cutoff && h.clusterId().equals(clusterId))
                .sorted(Comparator.comparingLong(HeadlineRecord::createdAt))
                .toList();
    }

    /** Drops wire entries older than the TTL — the archive keeps them forever. Called from the hourly cycle. */
    public void cleanup() {
        long cutoff = (System.currentTimeMillis() / 1000) - TTL_SECONDS;
        headlineCache.removeIf(h -> h.createdAt() < cutoff);
    }

    /**
     * Drops every headline from the live wire. Used by the editorial-lab
     * "Reset" action. The permanent {@link HeadlineArchive} is deliberately
     * untouched — Reset wipes the session, never history.
     */
    public void clear() {
        headlineCache.clear();
    }

    /** Kept for API symmetry with the old persistent variant — no-op now. */
    public void shutdown() {
    }

    /**
     * Immutable headline data. Source-ID lists may be empty; ticker symbol
     * and price-move % are nullable when the agent didn't attribute them.
     */
    public record HeadlineRecord(
            String clusterId,
            String headline,
            String context,
            long createdAt,
            List<String> sourceThreadIds,
            List<String> sourceCommentIds,
            HeadlineHighlight highlight,
            String tickerSymbol,
            List<HeadlineSubject> subjects,
            Double priceMovePercent,
            List<String> sectors,
            String assetClass,
            HeadlineSentiment sentiment,
            MarketSnapshot snapshot) {
    }
}
