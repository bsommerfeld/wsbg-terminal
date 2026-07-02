package de.bsommerfeld.wsbg.terminal.source.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link WebFetcher} decorator that turns repeat polls of unchanged endpoints
 * into HTTP conditional requests: it remembers each URL's validators
 * ({@code ETag} / {@code Last-Modified}) plus the body, injects
 * {@code If-None-Match} / {@code If-Modified-Since} on the next fetch, and on a
 * {@code 304 Not Modified} serves the cached body back as the original 2xx —
 * the caller never sees the 304. A revalidation looks exactly like a browser
 * cache hit and skips the body transfer, which is the only thing that helps
 * against a per-IP <em>volume</em> limit (traffic blending, Hebel 2).
 *
 * <p>Wired <b>around</b> the {@link WebFetchChain} (not inside it), so it sees
 * the chain's final answer. Graceful degradation is built in on both sides:
 * <ul>
 *   <li>A downstream transport that swallows request headers (the browser
 *       {@code fetch()} sets its own) simply returns a full {@code 200} — the
 *       response refreshes the cache, nothing breaks, only the saving is lost.</li>
 *   <li>Endpoints that never send a validator are never cached, so dynamic
 *       APIs (searches, RPC-style endpoints) pass through untouched.</li>
 * </ul>
 *
 * <p>Only 2xx responses that carry a validator are stored; errors ({@code >=400})
 * are never cached. The {@link WebFetcher} contract is GET-only, so idempotence
 * is given. The cache is in-memory only (matching the session-only doctrine for
 * fetched data) and bounded twice: by entry count and by total cached body size,
 * evicted LRU.
 */
public final class CachingWebFetcher implements WebFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(CachingWebFetcher.class);

    static final int DEFAULT_MAX_ENTRIES = 256;
    /** ~32 MB heap at 2 bytes/char — generous for JSON polls, bounded for listings. */
    static final long DEFAULT_MAX_TOTAL_BODY_CHARS = 16_000_000L;
    /** A single body larger than this is not worth pinning half the cache for. */
    static final long MAX_SINGLE_BODY_CHARS = 4_000_000L;

    private record CacheEntry(String etag, String lastModified, WebResponse response) {
        long weight() {
            return response.body().length();
        }
    }

    private final WebFetcher delegate;
    private final int maxEntries;
    private final long maxTotalBodyChars;

    /** LRU by access order; guarded by {@code this} (all map access is synchronized). */
    private final LinkedHashMap<String, CacheEntry> cache =
            new LinkedHashMap<>(64, 0.75f, true);
    private long totalBodyChars;

    public CachingWebFetcher(WebFetcher delegate) {
        this(delegate, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_TOTAL_BODY_CHARS);
    }

    CachingWebFetcher(WebFetcher delegate, int maxEntries, long maxTotalBodyChars) {
        this.delegate = delegate;
        this.maxEntries = maxEntries;
        this.maxTotalBodyChars = maxTotalBodyChars;
    }

    @Override
    public String name() {
        return "conditional[" + delegate.name() + "]";
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception {
        CacheEntry entry = get(url);

        Map<String, String> effective = headers == null ? Map.of() : headers;
        if (entry != null) {
            Map<String, String> conditional = new HashMap<>(effective);
            if (entry.etag() != null) conditional.put("If-None-Match", entry.etag());
            if (entry.lastModified() != null) conditional.put("If-Modified-Since", entry.lastModified());
            effective = conditional;
        }

        WebResponse response = delegate.fetch(url, effective, timeout);

        if (response.status() == 304 && entry != null) {
            LOG.debug("304 revalidation for {} — serving cached body ({} chars)",
                    url, entry.response().body().length());
            return entry.response();
        }

        if (response.status() >= 200 && response.status() < 300) {
            String etag = response.header("ETag").orElse(null);
            String lastModified = response.header("Last-Modified").orElse(null);
            if ((etag != null || lastModified != null)
                    && response.body().length() <= MAX_SINGLE_BODY_CHARS) {
                put(url, new CacheEntry(etag, lastModified, response));
            } else {
                // Validator gone (or body too big to pin) — drop the stale entry so
                // we stop sending dead conditionals for this URL.
                remove(url);
            }
        }
        // Non-2xx/non-304 (errors, blocks) are never cached and never evict a
        // known-good entry — a transient 500 shouldn't cost us the validators.
        return response;
    }

    private synchronized CacheEntry get(String url) {
        return cache.get(url);
    }

    private synchronized void put(String url, CacheEntry entry) {
        CacheEntry previous = cache.put(url, entry);
        if (previous != null) totalBodyChars -= previous.weight();
        totalBodyChars += entry.weight();
        evictIfNeeded();
    }

    private synchronized void remove(String url) {
        CacheEntry removed = cache.remove(url);
        if (removed != null) totalBodyChars -= removed.weight();
    }

    private void evictIfNeeded() {
        var it = cache.entrySet().iterator();
        while ((cache.size() > maxEntries || totalBodyChars > maxTotalBodyChars) && it.hasNext()) {
            CacheEntry eldest = it.next().getValue();
            it.remove();
            totalBodyChars -= eldest.weight();
        }
    }

    /** Current number of cached URLs — for tests/diagnostics. */
    synchronized int size() {
        return cache.size();
    }
}
