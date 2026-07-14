package de.bsommerfeld.wsbg.terminal.source.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A {@link WebFetcher} that resolves a URL by trying an ordered list of
 * strategies and returning the first <em>definitive</em> answer (2xx or 404).
 * A block-ish status (403/429/5xx/0) or a thrown transport error falls through
 * to the next strategy; if every strategy falls through, the last response (or a
 * synthetic failure) is returned so the caller still sees a status to act on.
 *
 * <p>This is the "array of fetch methods, tried in order" model: e.g.
 * {@code [browser, direct]} prefers the browser transport (which behaves like an
 * ordinary browser session) and falls back to plain HTTP, with no source-specific
 * glue. The set and order are pure wiring.
 */
public final class WebFetchChain implements WebFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(WebFetchChain.class);

    /**
     * Per-HOST rate-limit cooldowns, JVM-wide (static: both chain variants and
     * every source share one view of a host's limit — a 429 is an IP budget,
     * not a per-caller one). A host whose EVERY strategy answered 429 goes on
     * cooldown; while it lasts, fetches fail fast with a synthetic 429 —
     * no transport is touched, no hidden-browser re-anchor is burned (live
     * 2026-07-14: StockTitan 429s hammered browser AND direct per wire unit,
     * costing warmups and 45 s timeouts across the whole app). Repeat strikes
     * back off exponentially; a Retry-After header wins when present; any
     * definitive answer clears the host. Functions are never lost — the
     * source simply misses until the window passes, like any outage.
     */
    private static final Map<String, Cooldown> HOST_COOLDOWNS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long BASE_COOLDOWN_MS = 120_000;
    private static final long MAX_COOLDOWN_MS = 1_800_000;

    private record Cooldown(long untilMs, int strikes) {
    }

    private final List<WebFetcher> strategies;

    public WebFetchChain(List<WebFetcher> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("WebFetchChain needs at least one strategy");
        }
        this.strategies = List.copyOf(strategies);
    }

    @Override
    public String name() {
        StringBuilder sb = new StringBuilder("chain[");
        for (int i = 0; i < strategies.size(); i++) {
            if (i > 0) sb.append('→');
            sb.append(strategies.get(i).name());
        }
        return sb.append(']').toString();
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception {
        String host = hostOf(url);
        Cooldown cooldown = host == null ? null : HOST_COOLDOWNS.get(host);
        if (cooldown != null) {
            long leftMs = cooldown.untilMs() - System.currentTimeMillis();
            if (leftMs > 0) {
                LOG.debug("Host '{}' on rate-limit cooldown ({} s left) — failing fast for {}",
                        host, leftMs / 1000, url);
                return new WebResponse(429, "", Map.of());
            }
        }
        WebResponse last = WebResponse.failure();
        Exception lastError = null;
        for (WebFetcher f : strategies) {
            try {
                WebResponse r = f.fetch(url, headers, timeout);
                if (r.isDefinitive()) {
                    if (host != null) HOST_COOLDOWNS.remove(host);
                    return r;
                }
                LOG.debug("Fetch strategy '{}' → HTTP {} for {}, trying next", f.name(), r.status(), url);
                last = r;
            } catch (Exception e) {
                LOG.debug("Fetch strategy '{}' failed for {}: {}, trying next", f.name(), url, e.getMessage());
                lastError = e;
            }
        }
        // Every strategy fell through. A chain-wide 429 trips the host
        // cooldown (each transport was given its chance — the joker-rescue
        // path stays fully intact for the first strike).
        if (host != null && last.status() == 429) {
            int strikes = cooldown == null ? 1 : cooldown.strikes() + 1;
            long backoff = Math.min(MAX_COOLDOWN_MS, BASE_COOLDOWN_MS * (1L << (strikes - 1)));
            long retryAfterMs = last.header("Retry-After")
                    .map(WebFetchChain::parseRetryAfterMs).orElse(0L);
            long waitMs = Math.max(backoff, retryAfterMs);
            HOST_COOLDOWNS.put(host, new Cooldown(System.currentTimeMillis() + waitMs, strikes));
            LOG.info("Host '{}' answered 429 on every strategy — cooldown {} s (strike {}).",
                    host, waitMs / 1000, strikes);
        }
        // Prefer a real (block) response over an exception so the caller can
        // branch on the status (e.g. trip a breaker).
        if (last.status() != 0) return last;
        if (lastError != null) throw lastError;
        return last;
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** Retry-After: delta-seconds only (the HTTP-date form is rare on APIs). */
    private static long parseRetryAfterMs(String value) {
        try {
            return Long.parseLong(value.strip()) * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
