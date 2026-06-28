package de.bsommerfeld.wsbg.terminal.reddit;

import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.RedditHealthEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A {@link RedditSource} that auto-selects a working delegate from an ordered
 * preference chain — every install finds its own path with no shared
 * configuration. The wrapped consumer only ever sees the {@link RedditSource}
 * interface and never learns which concrete implementation answered, exactly
 * like a persistence layer that hides whether it talks to a DB, a YAML file, or
 * something else entirely.
 *
 * <p>Preference order (best fidelity first):
 * <ol>
 *   <li>OAuth ({@code oauth.reddit.com}) — full data, needs a client ID.</li>
 *   <li>Anonymous {@code .json} — full data, blocked for some IPs/UAs.</li>
 *   <li>RSS Atom feeds — always reachable anonymously, reduced fidelity.</li>
 * </ol>
 *
 * <p><b>Two recovery mechanisms.</b> The active delegate is resolved by
 * {@link RedditSource#probe} and cached, then re-resolved from the top of the
 * chain every {@code resolveIntervalSeconds} so the source upgrades back to a
 * higher-fidelity path once it recovers. But a probe passing doesn't guarantee a
 * scan succeeds (the browser anchor can load yet the {@code .json} listing still
 * 503/403 behind a JS challenge). So on a hard <em>scan</em> failure
 * ({@link ScrapeStats#failed}) the source <b>falls through immediately</b> to the
 * next delegate in the chain instead of sitting on the dead path until the next
 * re-probe, and <b>demotes</b> the failed delegate for {@link #DEMOTE_COOLDOWN_MS}
 * so it's skipped (no repeated stalls / browser re-anchor storms) until the
 * cooldown lets a later re-probe try to scale back up to it.
 *
 * <p><b>Health is owned here</b>, not by the individual scrapers: the UI's
 * "Defekt" indicator should reflect the <em>aggregate</em> — degraded only when
 * the whole chain is down, healthy as soon as any delegate (e.g. the RSS floor)
 * answers. The delegates are therefore wired with a {@code null} event bus and
 * this class posts the {@link RedditHealthEvent} transitions.
 */
public final class FallbackRedditSource implements RedditSource {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackRedditSource.class);

    /** How long a delegate that failed a scan is skipped before a re-probe may try it again. */
    private static final long DEMOTE_COOLDOWN_MS = 10 * 60 * 1000L;

    private final List<RedditSource> chain;
    private final String probeSubreddit;
    private final long resolveIntervalMillis;
    private final ApplicationEventBus eventBus; // nullable; owns the health signal

    private final Map<RedditSource, Long> demoteUntil = new ConcurrentHashMap<>();
    private volatile RedditSource active;
    private volatile long lastResolvedAt = 0L;
    private volatile boolean degraded = false;

    /**
     * @param chain                  delegates in preference order (must be non-empty)
     * @param probeSubreddit         subreddit used for reachability probes
     * @param resolveIntervalSeconds how often to re-evaluate the chain from the top
     * @param eventBus               where to post aggregate health transitions (nullable)
     */
    public FallbackRedditSource(List<RedditSource> chain, String probeSubreddit,
            long resolveIntervalSeconds, ApplicationEventBus eventBus) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("FallbackRedditSource needs at least one delegate");
        }
        this.chain = List.copyOf(chain);
        this.probeSubreddit = probeSubreddit;
        this.resolveIntervalMillis = Math.max(0L, resolveIntervalSeconds) * 1000L;
        this.eventBus = eventBus;
    }

    @Override
    public ScrapeStats scanSubreddit(String subreddit) {
        return runScan(s -> s.scanSubreddit(subreddit));
    }

    @Override
    public ScrapeStats scanSubredditHot(String subreddit) {
        return runScan(s -> s.scanSubredditHot(subreddit));
    }

    @Override
    public ScrapeStats updateThreadsBatch(List<String> threadIds) {
        return runScan(s -> s.updateThreadsBatch(threadIds));
    }

    @Override
    public ThreadAnalysisContext fetchThreadContext(String permalink) {
        return active().fetchThreadContext(permalink);
    }

    @Override
    public boolean probe(String subreddit) {
        for (RedditSource s : chain) {
            if (s.probe(subreddit)) return true;
        }
        return false;
    }

    @Override
    public String sourceName() {
        RedditSource a = active;
        return a != null ? a.sourceName() : "unresolved";
    }

    /**
     * Runs a scan against the active delegate and, on a hard failure, falls
     * through the lower-preference delegates until one succeeds — demoting each
     * failed one. Posts the aggregate health transition. Returns the first
     * successful result, or the active delegate's failed result if the whole
     * chain (from the active downward) is down.
     */
    private ScrapeStats runScan(Function<RedditSource, ScrapeStats> op) {
        RedditSource start = active();
        ScrapeStats result = op.apply(start);
        if (!result.failed) {
            reportHealthy();
            return result;
        }

        demote(start);
        int startIdx = chain.indexOf(start);
        for (int i = startIdx + 1; i < chain.size(); i++) {
            RedditSource next = chain.get(i);
            if (isDemoted(next)) continue;
            LOG.info("Reddit source {} failed a scan — falling through to {}.",
                    start.sourceName(), next.sourceName());
            ScrapeStats r = op.apply(next);
            if (!r.failed) {
                active = next;
                lastResolvedAt = System.currentTimeMillis();
                LOG.info("Reddit source active: {} (fell back from {}).",
                        next.sourceName(), start.sourceName());
                reportHealthy();
                return r;
            }
            demote(next);
        }
        LOG.warn("All Reddit sources down — staying on {}.", start.sourceName());
        reportDegraded();
        return result;
    }

    /** Marks a delegate skip-worthy for the cooldown. The last-resort floor is never demoted. */
    private void demote(RedditSource s) {
        if (s == chain.get(chain.size() - 1)) return; // keep the always-anonymous floor eligible
        demoteUntil.put(s, System.currentTimeMillis() + DEMOTE_COOLDOWN_MS);
        LOG.info("Reddit source {} demoted for ~{} min (will retry the upgrade after).",
                s.sourceName(), DEMOTE_COOLDOWN_MS / 60000);
    }

    private boolean isDemoted(RedditSource s) {
        Long until = demoteUntil.get(s);
        return until != null && System.currentTimeMillis() < until;
    }

    /** Returns the active delegate, (re)resolving it if stale. */
    private RedditSource active() {
        long now = System.currentTimeMillis();
        if (active == null || now - lastResolvedAt > resolveIntervalMillis) {
            return resolve();
        }
        return active;
    }

    private synchronized RedditSource resolve() {
        long now = System.currentTimeMillis();
        // Another thread may have just resolved while we waited on the monitor.
        if (active != null && now - lastResolvedAt <= resolveIntervalMillis) {
            return active;
        }
        RedditSource previous = active;
        RedditSource chosen = null;
        for (RedditSource s : chain) {
            if (isDemoted(s)) {
                LOG.debug("Reddit source {} still demoted, skipping in re-probe.", s.sourceName());
                continue;
            }
            if (s.probe(probeSubreddit)) {
                chosen = s;
                break;
            }
            LOG.debug("Reddit source {} unreachable, trying next.", s.sourceName());
        }
        if (chosen == null) {
            // Nothing probed — keep the previous choice if we had one, else
            // fall back to the last (always-anonymous RSS) and keep retrying.
            chosen = previous != null ? previous : chain.get(chain.size() - 1);
            LOG.warn("No Reddit source probed successfully; using {}.", chosen.sourceName());
        }
        lastResolvedAt = now;
        if (chosen != previous) {
            LOG.info("Reddit source active: {}", chosen.sourceName());
        }
        active = chosen;
        return chosen;
    }

    // ---- aggregate health (transition-based; the delegates don't post their own) ----

    private void reportHealthy() {
        if (degraded) {
            degraded = false;
            postHealth(RedditHealthEvent.State.OK, 0L);
        }
    }

    private void reportDegraded() {
        if (!degraded) {
            degraded = true;
            postHealth(RedditHealthEvent.State.DEGRADED, System.currentTimeMillis());
        }
    }

    private void postHealth(RedditHealthEvent.State state, long sinceMs) {
        if (eventBus == null) return;
        try {
            eventBus.post(new RedditHealthEvent(state, sinceMs));
        } catch (Exception e) {
            LOG.debug("Failed to post RedditHealthEvent: {}", e.getMessage());
        }
    }
}
