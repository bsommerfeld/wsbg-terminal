package de.bsommerfeld.tinyfetch.cache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers the last answer of a URL together with its validators
 * ({@code ETag}, {@code Last-Modified}), so the next request can ask
 * "changed since?" and a {@code 304} is answered from here.
 *
 * <p>This is what a browser cache does, and it is both halves of the point:
 * a browser that re-downloads an unchanged resource on every visit is not a
 * browser, and a {@code 304} costs the host a fraction of a full answer.
 *
 * <p>Bounded by entry count (least recently used goes first) and by body
 * size per entry; thread-safe.
 */
public final class ValidatorCache {

    /** Larger bodies are not kept - they would crowd everything else out. */
    public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** One remembered answer. */
    public record Entry(String etag, String lastModified, byte[] body, Map<String, List<String>> headers) {

        /** The request headers that ask whether this entry is still current. */
        public Map<String, String> conditionalHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            if (etag != null) {
                headers.put("if-none-match", etag);
            }
            if (lastModified != null) {
                headers.put("if-modified-since", lastModified);
            }
            return headers;
        }
    }

    private final LinkedHashMap<String, ValidatorCache.Entry> entries;

    public ValidatorCache(int capacity) {
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ValidatorCache.Entry> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized Optional<Entry> get(String url) {
        return Optional.ofNullable(entries.get(url));
    }

    /** Keeps a {@code 200} that carries a validator; anything else evicts the URL. */
    public synchronized void store(String url, int status, Optional<String> etag, Optional<String> lastModified,
            byte[] body, Map<String, List<String>> headers) {
        boolean cacheable = status == 200
                && (etag.isPresent() || lastModified.isPresent())
                && body.length <= MAX_BODY_BYTES;
        if (cacheable) {
            entries.put(url, new Entry(etag.orElse(null), lastModified.orElse(null), body, headers));
        } else {
            entries.remove(url);
        }
    }
}
