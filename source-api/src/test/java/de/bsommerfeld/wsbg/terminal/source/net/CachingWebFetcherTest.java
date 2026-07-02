package de.bsommerfeld.wsbg.terminal.source.net;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CachingWebFetcherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Scriptable delegate that records every request's headers. */
    private static final class FakeFetcher implements WebFetcher {
        final List<Map<String, String>> seenHeaders = new ArrayList<>();
        final List<String> seenUrls = new ArrayList<>();
        private final List<WebResponse> script = new ArrayList<>();

        FakeFetcher enqueue(WebResponse r) {
            script.add(r);
            return this;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            seenUrls.add(url);
            seenHeaders.add(new HashMap<>(headers));
            if (script.isEmpty()) throw new IllegalStateException("no scripted response left");
            return script.remove(0);
        }
    }

    private static WebResponse ok(String body, Map<String, String> headers) {
        return new WebResponse(200, body, headers);
    }

    @Test
    void servesCachedBodyOnA304Roundtrip() throws Exception {
        FakeFetcher delegate = new FakeFetcher()
                .enqueue(ok("fresh-body", Map.of("ETag", "\"v1\"", "Last-Modified", "Mon, 01 Jan 2026 00:00:00 GMT")))
                .enqueue(new WebResponse(304, "", Map.of()));
        CachingWebFetcher fetcher = new CachingWebFetcher(delegate);

        WebResponse first = fetcher.fetch("https://x.test/data", Map.of(), TIMEOUT);
        assertEquals(200, first.status());
        assertEquals("fresh-body", first.body());
        // First request carries no conditional headers — nothing cached yet.
        assertFalse(delegate.seenHeaders.get(0).containsKey("If-None-Match"));

        WebResponse second = fetcher.fetch("https://x.test/data", Map.of(), TIMEOUT);
        // The revalidation injected both validators…
        assertEquals("\"v1\"", delegate.seenHeaders.get(1).get("If-None-Match"));
        assertEquals("Mon, 01 Jan 2026 00:00:00 GMT", delegate.seenHeaders.get(1).get("If-Modified-Since"));
        // …and the caller sees the cached 200 with the original body, not the 304.
        assertEquals(200, second.status());
        assertEquals("fresh-body", second.body());
    }

    @Test
    void degradesGracefullyWhenTransportIgnoresConditionalHeaders() throws Exception {
        // Simulates the browser transport: the conditional header never reaches
        // the server, so a full 200 comes back — the caller just gets it.
        FakeFetcher delegate = new FakeFetcher()
                .enqueue(ok("body-1", Map.of("ETag", "\"v1\"")))
                .enqueue(ok("body-2", Map.of("ETag", "\"v2\"")));
        CachingWebFetcher fetcher = new CachingWebFetcher(delegate);

        fetcher.fetch("https://x.test/data", Map.of(), TIMEOUT);
        WebResponse second = fetcher.fetch("https://x.test/data", Map.of(), TIMEOUT);

        assertEquals("body-2", second.body());
        // The refreshed validator is what the next revalidation must send.
        delegate.enqueue(new WebResponse(304, "", Map.of()));
        WebResponse third = fetcher.fetch("https://x.test/data", Map.of(), TIMEOUT);
        assertEquals("\"v2\"", delegate.seenHeaders.get(2).get("If-None-Match"));
        assertEquals("body-2", third.body());
    }

    @Test
    void neverCachesErrorResponses() throws Exception {
        FakeFetcher delegate = new FakeFetcher()
                .enqueue(new WebResponse(500, "error", Map.of("ETag", "\"err\"")))
                .enqueue(ok("good", Map.of()));
        CachingWebFetcher fetcher = new CachingWebFetcher(delegate);

        WebResponse first = fetcher.fetch("https://x.test/err", Map.of(), TIMEOUT);
        assertEquals(500, first.status());
        assertEquals(0, fetcher.size());

        // Next request must not carry a conditional header from the error.
        fetcher.fetch("https://x.test/err", Map.of(), TIMEOUT);
        assertFalse(delegate.seenHeaders.get(1).containsKey("If-None-Match"));
    }

    @Test
    void aTransientErrorDoesNotEvictKnownGoodValidators() throws Exception {
        FakeFetcher delegate = new FakeFetcher()
                .enqueue(ok("body", Map.of("ETag", "\"v1\"")))
                .enqueue(new WebResponse(503, "", Map.of()))
                .enqueue(new WebResponse(304, "", Map.of()));
        CachingWebFetcher fetcher = new CachingWebFetcher(delegate);

        fetcher.fetch("https://x.test/data", Map.of(), TIMEOUT);
        WebResponse blocked = fetcher.fetch("https://x.test/data", Map.of(), TIMEOUT);
        assertEquals(503, blocked.status());

        WebResponse revalidated = fetcher.fetch("https://x.test/data", Map.of(), TIMEOUT);
        assertEquals("body", revalidated.body());
    }

    @Test
    void responsesWithoutValidatorsAreNotCached() throws Exception {
        FakeFetcher delegate = new FakeFetcher()
                .enqueue(ok("no-validators", Map.of()))
                .enqueue(ok("still-none", Map.of()));
        CachingWebFetcher fetcher = new CachingWebFetcher(delegate);

        fetcher.fetch("https://x.test/dyn", Map.of(), TIMEOUT);
        assertEquals(0, fetcher.size());
        fetcher.fetch("https://x.test/dyn", Map.of(), TIMEOUT);
        assertFalse(delegate.seenHeaders.get(1).containsKey("If-None-Match"));
    }

    @Test
    void entryBoundIsEnforcedLru() throws Exception {
        FakeFetcher delegate = new FakeFetcher();
        CachingWebFetcher fetcher = new CachingWebFetcher(delegate, 2, 1_000_000);

        for (int i = 0; i < 3; i++) {
            delegate.enqueue(ok("body-" + i, Map.of("ETag", "\"v" + i + "\"")));
            fetcher.fetch("https://x.test/" + i, Map.of(), TIMEOUT);
        }
        assertEquals(2, fetcher.size());

        // URL 0 was evicted (eldest) — its refetch must carry no conditional header.
        delegate.enqueue(ok("body-0b", Map.of("ETag", "\"v0b\"")));
        fetcher.fetch("https://x.test/0", Map.of(), TIMEOUT);
        assertFalse(delegate.seenHeaders.get(3).containsKey("If-None-Match"));
    }

    @Test
    void totalBodySizeBoundIsEnforced() throws Exception {
        FakeFetcher delegate = new FakeFetcher();
        CachingWebFetcher fetcher = new CachingWebFetcher(delegate, 100, 10);

        delegate.enqueue(ok("aaaaaaaa", Map.of("ETag", "\"a\""))); // 8 chars
        fetcher.fetch("https://x.test/a", Map.of(), TIMEOUT);
        delegate.enqueue(ok("bbbbbbbb", Map.of("ETag", "\"b\""))); // 8 chars — over 10 total
        fetcher.fetch("https://x.test/b", Map.of(), TIMEOUT);

        assertEquals(1, fetcher.size());
        // The older entry (a) fell out; only b survives.
        delegate.enqueue(new WebResponse(304, "", Map.of()));
        WebResponse b = fetcher.fetch("https://x.test/b", Map.of(), TIMEOUT);
        assertEquals("bbbbbbbb", b.body());
    }

    @Test
    void callerHeadersAreForwardedUnchanged() throws Exception {
        FakeFetcher delegate = new FakeFetcher().enqueue(ok("x", Map.of()));
        CachingWebFetcher fetcher = new CachingWebFetcher(delegate);

        fetcher.fetch("https://x.test/h", Map.of("Accept", "application/json"), TIMEOUT);
        assertEquals("application/json", delegate.seenHeaders.get(0).get("Accept"));
    }
}
