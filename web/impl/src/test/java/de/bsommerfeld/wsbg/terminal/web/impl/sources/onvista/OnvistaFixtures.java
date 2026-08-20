package de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Test scaffolding: trimmed LIVE captures (probed 2026-08-02 against SAP
 * {@code STOCK/82849}, the DAX and the worldwide calendar) plus a scripted
 * {@link WebFetcher} so the multi-request paths can be exercised without a
 * network.
 */
final class OnvistaFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OnvistaFixtures() {}

    /** Raw fixture text from {@code src/test/resources/onvista/}. */
    static String raw(String name) {
        try (InputStream in = OnvistaFixtures.class.getResourceAsStream("/onvista/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + name + " unreadable", e);
        }
    }

    /** Fixture parsed as JSON. */
    static JsonNode json(String name) {
        try {
            return JSON.readTree(raw(name));
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + name + " is not JSON", e);
        }
    }

    /**
     * A fetcher that answers a scripted queue of bodies in order (GET and POST
     * share the queue), recording the URLs it was asked for. A body of
     * {@code null} means "transport failure" (HTTP 500) so failure paths stay
     * testable.
     */
    static final class ScriptedFetcher implements WebFetcher {

        private final Deque<String> bodies;
        final List<String> urls = new ArrayList<>();

        ScriptedFetcher(String... bodies) {
            this.bodies = new ArrayDeque<>();
            for (String b : bodies) this.bodies.add(b == null ? "" : b);
        }

        private WebResponse next(String url) {
            urls.add(url);
            String body = bodies.poll();
            if (body == null || body.isEmpty()) return WebResponse.text(500, "", Map.of());
            return WebResponse.text(200, body, Map.of());
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            return next(url);
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers,
                Duration timeout, FetchUtil... modes) {
            throw new UnsupportedOperationException("no binary fetches in these tests");
        }

        @Override
        public WebResponse post(String url, Map<String, String> headers, String body,
                String contentType, Duration timeout, FetchUtil... modes) {
            return next(url);
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    }
}
