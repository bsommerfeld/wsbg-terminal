package de.bsommerfeld.wsbg.terminal.tradingview;

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
 * Network-free transport for the TradingView tests: replies are registered per
 * URL SUBSTRING (the URLs carry encoded filters, so an exact key would only
 * restate the production string), every call is recorded with its headers so a
 * test can prove BOTH that a request happened and that it carried the
 * mandatory Origin/Referer pair - and, for the paywall rule, that a request
 * never happened at all.
 */
final class FakeTradingViewFetcher implements WebFetcher {

    record Call(String url, Map<String, String> headers) {}

    final List<Call> calls = new ArrayList<>();
    private final Map<String, WebResponse> replies = new LinkedHashMap<>();

    FakeTradingViewFetcher on(String urlPart, WebResponse response) {
        replies.put(urlPart, response);
        return this;
    }

    FakeTradingViewFetcher onFixture(String urlPart, String fixture) {
        return on(urlPart, ok(load(fixture)));
    }

    List<String> urls() {
        return calls.stream().map(Call::url).toList();
    }

    boolean fetched(String urlPart) {
        return urls().stream().anyMatch(u -> u.contains(urlPart));
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
        calls.add(new Call(url, Map.copyOf(headers)));
        for (Map.Entry<String, WebResponse> e : replies.entrySet()) {
            if (url.contains(e.getKey())) return e.getValue();
        }
        throw new AssertionError("unexpected fetch: " + url);
    }

    static WebResponse ok(String body) {
        return new WebResponse(200, body, Map.of());
    }

    static String load(String fixture) {
        try (InputStream in = FakeTradingViewFetcher.class.getResourceAsStream("/" + fixture)) {
            if (in == null) throw new IllegalStateException("fixture missing: " + fixture);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture unreadable: " + fixture, e);
        }
    }
}
