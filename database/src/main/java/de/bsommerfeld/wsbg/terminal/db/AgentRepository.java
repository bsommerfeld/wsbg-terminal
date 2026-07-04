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

    /**
     * Which headlines belong to the CURRENT session — published live this run, or
     * restored from the short-TTL snapshot. NOT the ones merely re-seeded from the
     * permanent archive at startup. Lets "Archiv löschen" drop the archived history
     * from the wire while keeping the live session intact.
     */
    private final SessionLedger session = new SessionLedger();

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
                HeadlineSentiment.NEUTRAL, null, false);
    }

    /** Records a headline without concrete news references (legacy/cluster path). */
    public void saveHeadline(String clusterId, String headline, String context,
            List<String> sourceThreadIds, List<String> sourceCommentIds,
            HeadlineHighlight highlight, String tickerSymbol,
            List<HeadlineSubject> subjects,
            Double priceMovePercent,
            List<String> sectors, String assetClass, HeadlineSentiment sentiment,
            MarketSnapshot snapshot, boolean newsEnriched) {
        saveHeadline(clusterId, headline, context, sourceThreadIds, sourceCommentIds,
                highlight, tickerSymbol, subjects, priceMovePercent, sectors, assetClass,
                sentiment, snapshot, newsEnriched, List.of());
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
     * @param newsEnriched     {@code true} when the editorial compose leaned on
     *                         at least one external news item (cited a
     *                         {@code [news:ID]}) — a quiet provenance hint the UI
     *                         surfaces as a subtle "News" tag
     * @param newsRefs         the concrete external articles behind that hint
     *                         (title + publisher + permalink), so the UI can list
     *                         the original sources behind the tag; may be empty
     */
    public void saveHeadline(String clusterId, String headline, String context,
            List<String> sourceThreadIds, List<String> sourceCommentIds,
            HeadlineHighlight highlight, String tickerSymbol,
            List<HeadlineSubject> subjects,
            Double priceMovePercent,
            List<String> sectors, String assetClass, HeadlineSentiment sentiment,
            MarketSnapshot snapshot, boolean newsEnriched, List<HeadlineNewsRef> newsRefs) {
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
                snapshot,
                newsEnriched,
                newsRefs == null ? List.of() : List.copyOf(newsRefs));
        headlineCache.add(record);
        session.markLive(record); // live-published this session
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
            // Snapshot-restored headlines ARE the current session — mark them so
            // "Archiv löschen" keeps them, even when the archive re-seed already
            // put an identical copy in the wire.
            session.markLive(r);
            String id = HeadlineIdentity.of(r);
            boolean dup = headlineCache.stream()
                    .anyMatch(h -> HeadlineIdentity.of(h).equals(id));
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

    /**
     * The user's "Archiv löschen": wipes the permanent {@link HeadlineArchive} and
     * drops every archive-only headline from the live wire, KEEPING only the
     * current session (live-published + snapshot-restored, tracked by
     * {@link #sessionIdentities}). So the UI clears down to "what's happening now",
     * not to empty. No-op on the archive in archive-less tests.
     */
    public void clearArchiveKeepSession() {
        if (archive != null) archive.clear();
        headlineCache.removeIf(h -> !session.contains(h));
    }

    /**
     * The user's "Daten löschen": a FULL wipe — the live wire, the permanent
     * {@link HeadlineArchive}, and the session-identity tracking. Nothing is kept;
     * the wire repopulates from the next scan as if the app had just started.
     */
    public void clearAll() {
        headlineCache.clear();
        session.clear();
        if (archive != null) archive.clear();
    }

    /** Kept for API symmetry with the old persistent variant — no-op now. */
    public void shutdown() {
    }
}
