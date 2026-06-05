package de.bsommerfeld.wsbg.terminal.finanznachrichten;

import java.util.List;

/**
 * Fetches and parses a single {@link FnFeed} into news items. The seam between
 * {@link FnMonitorService} (scheduling, de-duplication, fan-out) and
 * {@link FnRssClient} (HTTP + XML), so the monitor can be unit-tested with an
 * in-memory fetcher and no network.
 */
@FunctionalInterface
public interface FeedFetcher {

    /**
     * Fetches {@code feed} and returns its items newest-first. Implementations
     * must never throw and must never return {@code null} — a failed fetch
     * returns an empty list so one broken feed cannot stall a whole sweep.
     */
    List<FnNewsItem> fetch(FnFeed feed);
}
