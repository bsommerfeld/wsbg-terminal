package de.bsommerfeld.wsbg.terminal.finanzennet;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared test plumbing: the live-captured fixtures (2026-08-02, pulled with the
 * full Akamai header set) and a fetcher that serves them by URL fragment.
 */
final class Fixtures {

    private Fixtures() {}

    static String load(String name) throws IOException {
        try (InputStream in = Fixtures.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Serves fixtures by URL fragment, records every call, can fail on demand. */
    static final class FakeFetcher implements WebFetcher {
        private final Map<String, String> byFragment;
        final List<String> calls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        boolean failAll;
        int status = 200;

        FakeFetcher(Map<String, String> byFragment) {
            this.byFragment = byFragment;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> h, Duration timeout) {
            calls.add(url);
            headers.add(h);
            if (failAll) return WebResponse.failure();
            for (Map.Entry<String, String> e : byFragment.entrySet()) {
                if (url.contains(e.getKey())) return new WebResponse(status, e.getValue(), Map.of());
            }
            return new WebResponse(404, "", Map.of());
        }

        int count(String fragment) {
            return (int) calls.stream().filter(c -> c.contains(fragment)).count();
        }
    }
}
