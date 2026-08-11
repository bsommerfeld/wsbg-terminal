package de.bsommerfeld.wsbg.terminal.web.impl.net;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.Transport;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE house fetcher: resolves a caller's {@link FetchUtil} array against the
 * registered transports and returns the first <em>definitive</em> answer.
 * Everything transport-independent lives here exactly ONCE — the four retry
 * layers of the old world collapse into this class:
 *
 * <ul>
 *   <li><b>Mode order</b> — the array is tried left to right; a block-ish
 *       status (403/429/5xx/0) or a thrown transport error falls through to
 *       the next mode. A mode with no registered transport (browser disabled)
 *       is skipped.</li>
 *   <li><b>Host cooldowns</b> — a host whose EVERY asked transport answered
 *       429 goes on exponential cooldown (Retry-After wins when present);
 *       while it lasts, fetches fail fast with a synthetic 429 and no socket
 *       is touched. Any definitive answer clears the host.</li>
 *   <li><b>Silence memory</b> — a transport that THREW on a host (timeout,
 *       reset, session never up) said nothing and will almost always say
 *       nothing again: two strikes mute the host/transport pair, backing off
 *       but expiring, and the mute never empties the plan.</li>
 *   <li><b>Conditional cache</b> — ETag/If-Modified-Since revalidation around
 *       the whole chain; a 304 comes back as the cached 2xx.</li>
 * </ul>
 */
@Singleton
public final class HouseFetcher implements WebFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(HouseFetcher.class);

    private static final long BASE_COOLDOWN_MS = 120_000;
    private static final long MAX_COOLDOWN_MS = 1_800_000;
    private static final int SILENCE_STRIKES = 2;
    private static final long BASE_MUTE_MS = 300_000;
    private static final long MAX_MUTE_MS = 1_800_000;

    private record Cooldown(long untilMs, int strikes) {
    }

    private record Silence(int strikes, long mutedUntilMs) {
    }

    /**
     * Per-HOST rate-limit cooldowns. Instance state on the ONE singleton (the
     * old static JVM-globals are gone), shared by every source because a 429
     * is an IP budget, not a per-caller one.
     */
    private final Map<String, Cooldown> hostCooldowns = new ConcurrentHashMap<>();

    /** Per-HOST, per-TRANSPORT silence memory. */
    private final Map<String, Silence> transportSilence = new ConcurrentHashMap<>();

    private final EnumMap<FetchUtil, Transport> transports = new EnumMap<>(FetchUtil.class);
    private final ConditionalCache cache = new ConditionalCache();

    @Inject
    public HouseFetcher(Set<Transport> registered) {
        for (Transport t : registered) {
            Transport previous = transports.putIfAbsent(t.util(), t);
            if (previous != null) {
                throw new IllegalStateException(
                        "two transports registered for " + t.util() + ": "
                                + previous.getClass().getName() + " and " + t.getClass().getName());
            }
        }
        if (!transports.containsKey(FetchUtil.DIRECT)) {
            throw new IllegalStateException("no DIRECT transport registered — the house cannot fetch");
        }
    }

    /** What kind of request rides the chain — decides transport capability. */
    private enum Kind { TEXT, BINARY, POST }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
            FetchUtil... modes) throws Exception {
        Map<String, String> conditional = cache.conditionalHeaders(url, headers);
        WebResponse response = run(url, conditional, timeout, modes, Kind.TEXT, null, null);
        return cache.absorb(url, response);
    }

    @Override
    public WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout,
            FetchUtil... modes) throws Exception {
        // No conditional cache on the byte path — validators are a text economy.
        return run(url, headers == null ? Map.of() : headers, timeout, modes, Kind.BINARY, null, null);
    }

    @Override
    public WebResponse post(String url, Map<String, String> headers, String body,
            String contentType, Duration timeout, FetchUtil... modes) throws Exception {
        // POSTs are never conditionally cached — they are not idempotent reads.
        return run(url, headers == null ? Map.of() : headers, timeout, modes, Kind.POST,
                body, contentType);
    }

    @Override
    public boolean hostCoolingDown(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        Cooldown cooldown = hostCooldowns.get(host);
        return cooldown != null && cooldown.untilMs() - System.currentTimeMillis() > 0;
    }

    private WebResponse run(String url, Map<String, String> headers, Duration timeout,
            FetchUtil[] modes, Kind kind, String body, String contentType) throws Exception {
        if (modes == null || modes.length == 0) {
            // The house default for plain consumers that are not
            // self-describing sources: fast leg first, joker as the rescue.
            modes = new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
        }
        String host = hostOf(url);
        Cooldown cooldown = host == null ? null : hostCooldowns.get(host);
        if (cooldown != null) {
            long leftMs = cooldown.untilMs() - System.currentTimeMillis();
            if (leftMs > 0) {
                LOG.debug("Host '{}' on rate-limit cooldown ({} s left) — failing fast for {}",
                        host, leftMs / 1000, url);
                return WebResponse.text(429, "", Map.of());
            }
        }

        List<Transport> declared = new ArrayList<>(modes.length);
        for (FetchUtil mode : modes) {
            Transport t = transports.get(mode);
            if (t == null) continue; // mode not registered (browser off) — skip
            if (kind == Kind.BINARY && !t.supportsBinary()) continue;
            if (kind == Kind.POST && !t.supportsPost()) continue;
            if (!declared.contains(t)) declared.add(t);
        }
        if (declared.isEmpty()) {
            // Nothing this environment can do for the declared modes — a
            // transport-level failure, not an HTTP answer.
            return WebResponse.failure();
        }

        WebResponse last = WebResponse.failure();
        Exception lastError = null;
        // Only an ANSWERED 429 counts toward the cooldown. A transport that
        // threw said nothing at all; one the memory skipped was never asked —
        // neither may speak for the host in the cooldown verdict.
        boolean everyTransportAnswered429 = true;
        List<Transport> plan = planFor(host, declared);
        if (plan.size() != declared.size()) everyTransportAnswered429 = false;

        for (Transport t : plan) {
            try {
                WebResponse r = switch (kind) {
                    case TEXT -> t.fetch(url, headers, timeout);
                    case BINARY -> t.fetchBinary(url, headers, timeout);
                    case POST -> t.post(url, headers, body, contentType, timeout);
                };
                heard(host, t);
                if (r.isDefinitive()) {
                    if (host != null) hostCooldowns.remove(host);
                    return r;
                }
                LOG.debug("Transport '{}' → HTTP {} for {}, trying next", t.util(), r.status(), url);
                if (r.status() != 429) everyTransportAnswered429 = false;
                last = r;
            } catch (Exception e) {
                LOG.debug("Transport '{}' failed for {}: {}, trying next", t.util(), url, describe(e));
                silent(host, t);
                everyTransportAnswered429 = false;
                lastError = e;
            }
        }

        // Every transport fell through. A chain-wide 429 trips the host
        // cooldown; a silent transport must never speak for the host (live
        // 2026-08-09: a hung browser leg beside a direct 429 is NOT a
        // chain-wide rate limit).
        if (host != null && everyTransportAnswered429 && last.status() == 429) {
            int strikes = cooldown == null ? 1 : cooldown.strikes() + 1;
            long backoff = Math.min(MAX_COOLDOWN_MS, BASE_COOLDOWN_MS * (1L << (strikes - 1)));
            long retryAfterMs = last.header("Retry-After")
                    .map(HouseFetcher::parseRetryAfterMs).orElse(0L);
            long waitMs = Math.max(backoff, retryAfterMs);
            hostCooldowns.put(host, new Cooldown(System.currentTimeMillis() + waitMs, strikes));
            LOG.info("Host '{}' answered 429 on every transport — cooldown {} s (strike {}).",
                    host, waitMs / 1000, strikes);
        }
        // Prefer a real (block) response over an exception so the caller can
        // branch on the status (e.g. trip a breaker).
        if (last.status() != 0) return last;
        if (lastError != null) throw lastError;
        return last;
    }

    /**
     * The transports worth trying for this host right now, in declared order.
     * Falls back to the full plan when the memory would leave nothing — a
     * source may lose speed to this memory, never its function.
     */
    private List<Transport> planFor(String host, List<Transport> declared) {
        if (host == null || declared.size() < 2) return declared;
        long now = System.currentTimeMillis();
        List<Transport> plan = new ArrayList<>(declared.size());
        for (Transport t : declared) {
            Silence s = transportSilence.get(silenceKey(host, t));
            if (s != null && s.mutedUntilMs() > now) continue;
            plan.add(t);
        }
        return plan.isEmpty() ? declared : plan;
    }

    /** A transport answered (any status): it is alive on this host. */
    private void heard(String host, Transport t) {
        if (host != null) transportSilence.remove(silenceKey(host, t));
    }

    /** A transport threw: silence, and after {@value #SILENCE_STRIKES} a mute. */
    private void silent(String host, Transport t) {
        if (host == null) return;
        transportSilence.compute(silenceKey(host, t), (k, s) -> {
            int strikes = s == null ? 1 : s.strikes() + 1;
            if (strikes < SILENCE_STRIKES) return new Silence(strikes, 0L);
            long mute = Math.min(MAX_MUTE_MS,
                    BASE_MUTE_MS * (1L << Math.min(3, strikes - SILENCE_STRIKES)));
            LOG.info("Transport '{}' stayed silent on '{}' ({} strike(s)) — skipping it there "
                    + "for {} s; the rest of the plan still answers.",
                    t.util(), host, strikes, mute / 1000);
            return new Silence(strikes, System.currentTimeMillis() + mute);
        });
    }

    private static String silenceKey(String host, Transport t) {
        return host + '|' + t.util();
    }

    /** Test seam: forget every host's cooldown and transport memory. */
    void forgetAll() {
        hostCooldowns.clear();
        transportSilence.clear();
    }

    /**
     * A readable cause for the log. Several transport failures carry no
     * message at all, and those used to print as a bare "null".
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
