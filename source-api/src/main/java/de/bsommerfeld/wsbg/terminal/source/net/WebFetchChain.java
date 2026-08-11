package de.bsommerfeld.wsbg.terminal.source.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
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
     * not a per-caller one). A host whose EVERY strategy <em>answered</em> 429
     * goes on cooldown — a strategy that threw is not a 429, it is silence;
     * while it lasts, fetches fail fast with a synthetic 429 —
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

    /**
     * Per-HOST, per-STRATEGY silence memory, JVM-wide. A strategy that answers
     * a host with a THROWN transport error - a timeout, a reset stream, a
     * session that never comes up - said nothing at all, and it will almost
     * always say nothing again: the wall is a property of the host/transport
     * pair, not of the single request. Without a memory the chain re-buys that
     * silence at full timeout price for every URL.
     *
     * <p>Measured 2026-08-11 on one dossier: the St. Louis Fed answered the
     * browser leg in 100-300 ms and let the direct leg run into its 20 s
     * timeout - fourteen times in a row, once per macro series, 4 min 26 s for
     * a leg whose data was there all along. FINRA's daily files paid the same
     * toll 56 times, boerse.de twice per article.
     *
     * <p>Two strikes before muting (one timeout is a blip, two are a wall) and
     * a mute that BACKS OFF but expires, so a host that heals is heard again.
     * The mute is never allowed to empty the chain: if every strategy is muted,
     * they are all tried - a source may lose speed to this memory, never its
     * function.
     */
    private static final Map<String, Silence> STRATEGY_SILENCE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SILENCE_STRIKES = 2;
    private static final long BASE_MUTE_MS = 300_000;
    private static final long MAX_MUTE_MS = 1_800_000;

    private record Silence(int strikes, long mutedUntilMs) {
    }

    private static String silenceKey(String host, WebFetcher f) {
        return host + '|' + f.name();
    }

    /**
     * The strategies worth trying for this host right now, in chain order.
     * Falls back to the full chain when the memory would leave nothing.
     */
    private List<WebFetcher> planFor(String host) {
        if (host == null || strategies.size() < 2) return strategies;
        long now = System.currentTimeMillis();
        List<WebFetcher> plan = new ArrayList<>(strategies.size());
        for (WebFetcher f : strategies) {
            Silence s = STRATEGY_SILENCE.get(silenceKey(host, f));
            if (s != null && s.mutedUntilMs() > now) continue;
            plan.add(f);
        }
        return plan.isEmpty() ? strategies : plan;
    }

    /** A strategy answered (any status): it is alive on this host. */
    private static void heard(String host, WebFetcher f) {
        if (host != null) STRATEGY_SILENCE.remove(silenceKey(host, f));
    }

    /** A strategy threw: silence, and after {@value #SILENCE_STRIKES} a mute. */
    private static void silent(String host, WebFetcher f) {
        if (host == null) return;
        STRATEGY_SILENCE.compute(silenceKey(host, f), (k, s) -> {
            int strikes = s == null ? 1 : s.strikes() + 1;
            if (strikes < SILENCE_STRIKES) return new Silence(strikes, 0L);
            long mute = Math.min(MAX_MUTE_MS,
                    BASE_MUTE_MS * (1L << Math.min(3, strikes - SILENCE_STRIKES)));
            LOG.info("Strategy '{}' stayed silent on '{}' ({} strike(s)) — skipping it there "
                    + "for {} s; the rest of the chain still answers.",
                    f.name(), host, strikes, mute / 1000);
            return new Silence(strikes, System.currentTimeMillis() + mute);
        });
    }

    /** Test seam: forget every host's cooldown and strategy memory. */
    static void forgetAll() {
        HOST_COOLDOWNS.clear();
        STRATEGY_SILENCE.clear();
    }

    /**
     * Whether this host is currently failing fast on its rate-limit cooldown -
     * i.e. a fetch would return the synthetic 429 without touching a socket.
     *
     * <p>Callers that pay something BEFORE the fetch ask here first. The one
     * that made this necessary is the GDELT pace gate: it slept its full
     * host interval and only then learned, inside the chain, that no request
     * was going out at all (measured 2026-08-11: 39 of 83 GDELT requests in
     * one dossier slept 8 s each for a host on cooldown - five minutes of
     * waiting for zero bytes). A pace is owed to a host we are talking to,
     * never to one we are not.
     */
    public static boolean hostFailingFast(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        Cooldown cooldown = HOST_COOLDOWNS.get(host);
        return cooldown != null && cooldown.untilMs() - System.currentTimeMillis() > 0;
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
        // Only an ANSWERED 429 counts toward the cooldown. A strategy that threw
        // said nothing at all — see the flag's use below.
        boolean everyStrategyAnswered429 = true;
        List<WebFetcher> plan = planFor(host);
        // A strategy the memory skipped never answered 429 either - it was not
        // asked at all, and a chain that did not put its whole weight behind
        // the host must not speak for it in the cooldown verdict below.
        if (plan.size() != strategies.size()) everyStrategyAnswered429 = false;
        for (WebFetcher f : plan) {
            try {
                WebResponse r = f.fetch(url, headers, timeout);
                heard(host, f);
                if (r.isDefinitive()) {
                    if (host != null) HOST_COOLDOWNS.remove(host);
                    return r;
                }
                LOG.debug("Fetch strategy '{}' → HTTP {} for {}, trying next", f.name(), r.status(), url);
                if (r.status() != 429) everyStrategyAnswered429 = false;
                last = r;
            } catch (Exception e) {
                LOG.debug("Fetch strategy '{}' failed for {}: {}, trying next", f.name(), url, describe(e));
                silent(host, f);
                everyStrategyAnswered429 = false;
                lastError = e;
            }
        }
        // Every strategy fell through. A chain-wide 429 trips the host
        // cooldown (each transport was given its chance — the joker-rescue
        // path stays fully intact for the first strike).
        //
        // A transport ERROR is not a 429. Live 2026-08-09: Yahoo throttled our
        // IP, the browser leg's page-side fetch() hung past the caller timeout
        // (TimeoutException, no status at all) while direct answered 429 — and
        // the old "last.status() == 429" read that as the whole chain being
        // rate-limited. Yahoo went on an exponential host cooldown although the
        // joker had never been heard from, so EUR/USD fell to Frankfurter for
        // minutes on end. A silent transport must never speak for the host.
        if (host != null && everyStrategyAnswered429 && last.status() == 429) {
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

    /**
     * A readable cause for the log. Several transport failures carry no message
     * at all — a {@code TimeoutException} from the browser leg's reply channel is
     * the common one — and those used to print as a bare "null", which says
     * nothing about which failure occurred.
     */
    static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
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
