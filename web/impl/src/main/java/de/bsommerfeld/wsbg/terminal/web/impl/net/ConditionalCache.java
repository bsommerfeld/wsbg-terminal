package de.bsommerfeld.wsbg.terminal.web.impl.net;

import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The house fetcher's conditional-request memory: remembers each URL's
 * validators ({@code ETag} / {@code Last-Modified}) plus the body, injects
 * {@code If-None-Match} / {@code If-Modified-Since} on the next fetch, and on
 * a {@code 304 Not Modified} serves the cached body back as the original 2xx —
 * the caller never sees the 304. A revalidation looks exactly like a browser
 * cache hit and skips the body transfer, which is the only thing that helps
 * against a per-IP <em>volume</em> limit.
 *
 * <p>Graceful on both sides: a transport that swallows request headers simply
 * returns a full 200 (the response refreshes the cache), and endpoints that
 * never send a validator are never cached, so dynamic APIs pass through
 * untouched. Only 2xx responses that carry a validator are stored. In-memory
 * only, bounded twice (entry count + total body size), evicted LRU.
 */
final class ConditionalCache {

    private static final Logger LOG = LoggerFactory.getLogger(ConditionalCache.class);

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

    private final int maxEntries;
    private final long maxTotalBodyChars;

    /** LRU by access order; guarded by {@code this} (all map access is synchronized). */
    private final LinkedHashMap<String, CacheEntry> cache =
            new LinkedHashMap<>(64, 0.75f, true);
    private long totalBodyChars;

    ConditionalCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_TOTAL_BODY_CHARS);
    }

    ConditionalCache(int maxEntries, long maxTotalBodyChars) {
        this.maxEntries = maxEntries;
        this.maxTotalBodyChars = maxTotalBodyChars;
    }

    /** The headers to send: the caller's plus this URL's conditionals, if any. */
    Map<String, String> conditionalHeaders(String url, Map<String, String> headers) {
        CacheEntry entry = get(url);
        Map<String, String> effective = headers == null ? Map.of() : headers;
        if (entry == null) return effective;
        Map<String, String> conditional = new HashMap<>(effective);
        if (entry.etag() != null) conditional.put("If-None-Match", entry.etag());
        if (entry.lastModified() != null) conditional.put("If-Modified-Since", entry.lastModified());
        return conditional;
    }

    /**
     * Absorbs the chain's final answer: turns a 304 back into the cached 2xx,
     * stores fresh validators, drops stale ones.
     */
    WebResponse absorb(String url, WebResponse response) {
        CacheEntry entry = get(url);
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
