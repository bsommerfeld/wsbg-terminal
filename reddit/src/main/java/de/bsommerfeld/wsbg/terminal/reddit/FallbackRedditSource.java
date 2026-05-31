package de.bsommerfeld.wsbg.terminal.reddit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
 * <p>The active delegate is resolved by {@link RedditSource#probe} and cached;
 * it is re-resolved from the top of the chain whenever the resolve interval has
 * elapsed, so the source self-heals — it upgrades back to OAuth once it
 * recovers and falls away from a path that has gone dark, all without
 * restarting. Probes are cheap ({@code limit=1}, no parsing), so re-resolving a
 * few requests every interval is negligible.
 */
public final class FallbackRedditSource implements RedditSource {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackRedditSource.class);

    private final List<RedditSource> chain;
    private final String probeSubreddit;
    private final long resolveIntervalMillis;

    private volatile RedditSource active;
    private volatile long lastResolvedAt = 0L;

    /**
     * @param chain            delegates in preference order (must be non-empty)
     * @param probeSubreddit   subreddit used for reachability probes
     * @param resolveIntervalSeconds how often to re-evaluate the chain from the top
     */
    public FallbackRedditSource(List<RedditSource> chain, String probeSubreddit,
            long resolveIntervalSeconds) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("FallbackRedditSource needs at least one delegate");
        }
        this.chain = List.copyOf(chain);
        this.probeSubreddit = probeSubreddit;
        this.resolveIntervalMillis = Math.max(0L, resolveIntervalSeconds) * 1000L;
    }

    @Override
    public ScrapeStats scanSubreddit(String subreddit) {
        return active().scanSubreddit(subreddit);
    }

    @Override
    public ScrapeStats scanSubredditHot(String subreddit) {
        return active().scanSubredditHot(subreddit);
    }

    @Override
    public ScrapeStats updateThreadsBatch(List<String> threadIds) {
        return active().updateThreadsBatch(threadIds);
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
}
