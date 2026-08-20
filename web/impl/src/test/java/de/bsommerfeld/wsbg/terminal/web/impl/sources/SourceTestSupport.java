package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared test plumbing for the migrated source tests: fixture loading from
 * {@code /sources/<module>/…} and a {@link WebFetcher} fake that serves bodies
 * by URL fragment (first match wins), records every call and can fail or
 * answer an arbitrary status on demand.
 */
public final class SourceTestSupport {

    private SourceTestSupport() {}

    /** Loads a classpath fixture, e.g. {@code sources/welt/welt-latest-rss.xml}. */
    public static String fixture(String path) {
        try (InputStream in = SourceTestSupport.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves bodies by URL fragment, insertion order, first match wins. */
    public static final class FakeWebFetcher implements WebFetcher {

        private final Map<String, String> byFragment = new LinkedHashMap<>();
        public final List<String> calls = new ArrayList<>();
        public final List<Map<String, String>> headers = new ArrayList<>();
        public boolean failAll;
        /** Status for URLs no fixture matches. */
        public int missStatus = 404;

        public FakeWebFetcher() {
        }

        public FakeWebFetcher(Map<String, String> routes) {
            byFragment.putAll(routes);
        }

        /** Registers a body for every URL containing {@code fragment}. */
        public FakeWebFetcher on(String fragment, String body) {
            byFragment.put(fragment, body);
            return this;
        }

        public int count(String fragment) {
            return (int) calls.stream().filter(u -> u.contains(fragment)).count();
        }

        public int total() {
            return calls.size();
        }

        public List<String> callsContaining(String fragment) {
            return calls.stream().filter(u -> u.contains(fragment)).toList();
        }

        public String lastCallContaining(String fragment) {
            return calls.stream().filter(u -> u.contains(fragment))
                    .reduce((a, b) -> b).orElse("");
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> h, Duration timeout,
                FetchUtil... modes) {
            calls.add(url);
            headers.add(h);
            if (failAll) return WebResponse.failure();
            for (Map.Entry<String, String> e : byFragment.entrySet()) {
                if (url.contains(e.getKey())) {
                    return WebResponse.text(200, e.getValue(), Map.of());
                }
            }
            return WebResponse.text(missStatus, "", Map.of());
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> h, Duration timeout,
                FetchUtil... modes) {
            throw new UnsupportedOperationException("no binary fetches in these tests");
        }

        @Override
        public WebResponse post(String url, Map<String, String> h, String body,
                String contentType, Duration timeout, FetchUtil... modes) {
            throw new UnsupportedOperationException("no POSTs in these tests");
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    }
}
