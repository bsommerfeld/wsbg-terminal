package de.bsommerfeld.wsbg.terminal.boersede;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test support: the live boerse.de fixtures (curled 2026-08-02 with a browser
 * UA and trimmed to what the parsers touch) plus a {@link WebFetcher} that
 * serves them by URL substring, so a test can assert WHICH pages were fetched.
 */
final class Fixtures {

    private Fixtures() {}

    static String load(String name) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("missing fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves a fixture when the requested URL contains a registered marker. */
    static final class FakeFetcher implements WebFetcher {

        private final Map<String, String> byMarker = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();
        int statusForUnknown = 410;

        FakeFetcher on(String urlMarker, String fixtureName) {
            byMarker.put(urlMarker, load(fixtureName));
            return this;
        }

        FakeFetcher body(String urlMarker, String body) {
            byMarker.put(urlMarker, body);
            return this;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            requested.add(url);
            for (Map.Entry<String, String> e : byMarker.entrySet()) {
                if (url.contains(e.getKey())) {
                    return new WebResponse(200, e.getValue(), Map.of());
                }
            }
            return new WebResponse(statusForUnknown, "", Map.of());
        }
    }
}
