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

    /** House budget for a host nobody asked for anything special. */
    private static final int GATE_CONCURRENCY = 2;
    private static final double GATE_PER_SECOND = 2.0;
    private static final double GATE_BURST = 4.0;

    /**
     * How long a caller waits for a host's budget before being turned away
     * with a synthetic 429. Long enough to absorb the fan's own bursts, short
     * enough that one throttled host cannot stall a compose.
     */
    private static final long GATE_WAIT_MS = 8_000;

    /**
     * Hosts that publish (or enforce) a tighter budget than the house default.
     * GDELT documents one request every five seconds and answers an overrun
     * with an IP block; 4chan's API asks for a second between calls; Yahoo and
     * the SEC both punish volume hard, the latter with an immediate block.
     */
    private static final Map<String, HostBudget> HOST_BUDGETS = Map.of(
            "api.gdeltproject.org", new HostBudget(1, 0.2, 1.0),
            "data.gdeltproject.org", new HostBudget(1, 0.2, 1.0),
            "a.4cdn.org", new HostBudget(1, 1.0, 2.0),
            "efts.sec.gov", new HostBudget(1, 2.0, 2.0),
            "www.sec.gov", new HostBudget(1, 2.0, 2.0),
            "query2.finance.yahoo.com", new HostBudget(1, 1.0, 2.0),
            "query1.finance.yahoo.com", new HostBudget(1, 1.0, 2.0),
            "www.bing.com", new HostBudget(1, 0.5, 1.0),
            "news.google.com", new HostBudget(2, 1.0, 2.0));

    private record HostBudget(int concurrency, double perSecond, double burst) {
    }

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

    /**
     * Per-HOST proactive budgets. Every path — collectors, the instrument fan,
     * the facts graze, the archive fan, the anonymous reddit leg — runs through
     * {@link #run}, so this one map paces the whole process.
     */
    private final Map<String, HostGate> hostGates = new ConcurrentHashMap<>();

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

        HostGate gate = host == null ? null : hostGates.computeIfAbsent(host, HouseFetcher::gateFor);
        if (gate == null) {
            return attempt(url, headers, timeout, modes, kind, body, contentType, host);
        }
        if (!gate.acquire(GATE_WAIT_MS)) {
            LOG.debug("Host '{}' budget saturated — turning away {} rather than queueing", host, url);
            return WebResponse.text(429, "", Map.of());
        }
        try {
            return attempt(url, headers, timeout, modes, kind, body, contentType, host);
        } finally {
            gate.release();
        }
    }

    private static HostGate gateFor(String host) {
        HostBudget budget = HOST_BUDGETS.get(host);
        return budget == null
                ? new HostGate(GATE_CONCURRENCY, GATE_PER_SECOND, GATE_BURST)
                : new HostGate(budget.concurrency(), budget.perSecond(), budget.burst());
    }

    private WebResponse attempt(String url, Map<String, String> headers, Duration timeout,
            FetchUtil[] modes, Kind kind, String body, String contentType,
            String host) throws Exception {
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
        // Only an ANSWERED block counts toward the cooldown. A transport that
        // threw said nothing at all; one the memory skipped was never asked —
        // neither may speak for the host in the cooldown verdict.
        boolean everyTransportBlocked = true;
        List<Transport> plan = planFor(host, declared);
        if (plan.size() != declared.size()) everyTransportBlocked = false;

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
                if (!isBlock(r.status())) everyTransportBlocked = false;
                last = r;
            } catch (Exception e) {
                LOG.debug("Transport '{}' failed for {}: {}, trying next", t.util(), url, describe(e));
                silent(host, t);
                everyTransportBlocked = false;
                lastError = e;
            }
        }

        // Every transport fell through. A chain-wide BLOCK trips the host
        // cooldown; a silent transport must never speak for the host (live
        // 2026-08-09: a hung browser leg beside a direct 429 is NOT a
        // chain-wide rate limit).
        if (host != null && everyTransportBlocked && isBlock(last.status())) {
            long retryAfterMs = last.header("Retry-After")
                    .map(HouseFetcher::parseRetryAfterMs).orElse(0L);
            penalize(host, retryAfterMs, "answered HTTP " + last.status() + " on every transport");
        }
        // Prefer a real (block) response over an exception so the caller can
        // branch on the status (e.g. trip a breaker).
        if (last.status() != 0) return last;
        if (lastError != null) throw lastError;
        return last;
    }

    @Override
    public void reportWall(String url) {
        String host = hostOf(url);
        if (host == null) return;
        // A 200-shaped challenge (consent interstitial, bot check, JS wall) is
        // a block the status line does not admit to. Only the source can tell
        // — it is the one that knows what a real answer looks like — so it
        // hands the verdict back here instead of the house hammering on.
        penalize(host, 0L, "served a 200-shaped wall instead of content");
    }

    /**
     * A status the host used to turn us away. 429 is the polite form, 403 the
     * blunt one — and a 403 wall never lifts on its own, so it earns the same
     * exponential patience rather than an endless retry at full cadence.
     */
    private static boolean isBlock(int status) {
        return status == 429 || status == 403;
    }

    /** Puts (or deepens) a host's cooldown and says why in the log. */
    private void penalize(String host, long retryAfterMs, String why) {
        Cooldown previous = hostCooldowns.get(host);
        int strikes = previous == null ? 1 : previous.strikes() + 1;
        long backoff = Math.min(MAX_COOLDOWN_MS, BASE_COOLDOWN_MS * (1L << Math.min(31, strikes - 1)));
        long waitMs = Math.max(backoff, retryAfterMs);
        hostCooldowns.put(host, new Cooldown(System.currentTimeMillis() + waitMs, strikes));
        LOG.info("Host '{}' {} — cooldown {} s (strike {}).", host, why, waitMs / 1000, strikes);
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

    /** One host's rate-limit cooldown, for the debug bridge. */
    public record CooldownView(String host, long untilMs, long leftMs, int strikes) {
    }

    /** One muted host/transport pair, for the debug bridge. */
    public record SilenceView(String hostTransport, int strikes, long mutedUntilMs, long leftMs) {
    }

    /**
     * Debug read path (on-demand only): the live cooldown table as a copy.
     * Iterates the {@link ConcurrentHashMap} weakly-consistent — no lock, no
     * ordering edge, no behaviour change. Expired entries are reported with
     * {@code leftMs == 0} rather than filtered, so the strike history stays
     * visible until the next definitive answer clears the host.
     */
    public List<CooldownView> debugCooldowns() {
        long now = System.currentTimeMillis();
        List<CooldownView> out = new ArrayList<>();
        hostCooldowns.forEach((host, c) -> out.add(new CooldownView(
                host, c.untilMs(), Math.max(0L, c.untilMs() - now), c.strikes())));
        out.sort(java.util.Comparator.comparingLong(CooldownView::leftMs).reversed());
        return out;
    }

    /** Debug read path (on-demand only): the transport-silence table as a copy. */
    public List<SilenceView> debugSilences() {
        long now = System.currentTimeMillis();
        List<SilenceView> out = new ArrayList<>();
        transportSilence.forEach((key, s) -> out.add(new SilenceView(
                key, s.strikes(), s.mutedUntilMs(), Math.max(0L, s.mutedUntilMs() - now))));
        out.sort(java.util.Comparator.comparingLong(SilenceView::leftMs).reversed());
        return out;
    }

    /** Debug read path: how many hosts have an active pacing gate. */
    public int debugGateCount() {
        return hostGates.size();
    }

    /** Test seam: how much of a host's cooldown is left, 0 when it is clear. */
    long cooldownLeftMs(String url) {
        String host = hostOf(url);
        if (host == null) return 0L;
        Cooldown cooldown = hostCooldowns.get(host);
        return cooldown == null ? 0L : Math.max(0L, cooldown.untilMs() - System.currentTimeMillis());
    }

    /** Test seam: forget every host's cooldown, budget and transport memory. */
    void forgetAll() {
        hostCooldowns.clear();
        transportSilence.clear();
        hostGates.clear();
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

    /**
     * Retry-After in either legal shape. The date form is rarer on APIs but
     * carries the longest waits — collapsing a "come back in an hour" to the
     * 120 s base backoff is exactly the mistake that earns a ban.
     */
    private static long parseRetryAfterMs(String value) {
        String raw = value == null ? "" : value.strip();
        if (raw.isEmpty()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(raw) * 1000L);
        } catch (NumberFormatException ignored) {
            // not delta-seconds — try the HTTP-date form
        }
        try {
            long epochMs = java.time.ZonedDateTime
                    .parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            return Math.max(0L, epochMs - System.currentTimeMillis());
        } catch (Exception e) {
            return 0L;
        }
    }
}
