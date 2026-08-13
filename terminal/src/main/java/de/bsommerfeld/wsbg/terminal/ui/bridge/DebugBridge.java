package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.jshepherd.annotation.Key;
import de.bsommerfeld.jshepherd.annotation.Section;
import de.bsommerfeld.wsbg.terminal.agent.AgentBrain;
import de.bsommerfeld.wsbg.terminal.agent.EditorialQueue;
import de.bsommerfeld.wsbg.terminal.agent.LlmGate;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.debug.BasinInflow;
import de.bsommerfeld.wsbg.terminal.core.debug.CollectorClock;
import de.bsommerfeld.wsbg.terminal.core.debug.DebugGauges;
import de.bsommerfeld.wsbg.terminal.core.debug.DevMode;
import de.bsommerfeld.wsbg.terminal.core.debug.LlmDebug;
import de.bsommerfeld.wsbg.terminal.core.debug.RedditPollDebug;
import de.bsommerfeld.wsbg.terminal.core.debug.SourceHealthRegistry;
import de.bsommerfeld.wsbg.terminal.reddit.FallbackRedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.ui.debug.DebugLog;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import de.bsommerfeld.wsbg.terminal.web.impl.pool.InMemoryArticlePool;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * THE debug data interface — the single seam a debug UI talks to. Pure data:
 * this class ships no HTML/CSS/JS and never will; a later agent builds the
 * view on top of exactly this contract.
 *
 * <h2>Availability</h2>
 * Bound ONLY in dev mode ({@link DevMode#active()}, see {@code BridgeModule}).
 * In a shipped build there is no binding, no {@code hub.on("debug", …)}
 * handler, and the PushHub simply never answers the type. The underlying
 * registries are additionally write-guarded by the JIT-foldable
 * {@code Debug.ENABLED}, so shipped builds collect nothing either.
 *
 * <h2>Wire protocol (mirrors {@link ArchiveQueryBridge})</h2>
 * Request (page → Java), over the existing WebSocket envelope:
 * <pre>{@code {type: "debug", payload: {command: "<command>", requestId?: any, limit?: int}}}</pre>
 * Response (Java → page), one broadcast per request:
 * <pre>{@code {type: "debug-<command>", payload: {command, requestId?, at: <epochMs>, data: {…}}}}</pre>
 * {@code requestId} is echoed verbatim when present. Errors inside one data
 * section never fail the response — the section is replaced by
 * {@code {error: "<message>"}}. An unknown command answers
 * {@code debug-unknown} with the list of valid commands.
 *
 * <p>There is NO periodic push: the dashboard is almost always closed, so
 * the page polls (e.g. every 2 s while open) and each answer is built on
 * demand. {@code limit} caps event-list lengths where noted.
 *
 * <h2>Commands and data shapes</h2>
 * <dl>
 * <dt>{@code overview}</dt>
 * <dd>{@code {devMode, pid, javaVersion, os, uptimeMs, heapUsedBytes,
 *     heapMaxBytes, processors, commands: [names]}}</dd>
 *
 * <dt>{@code sources} — the Quellenmesser (limit: events, default 200)</dt>
 * <dd>{@code {health: [{source, lastStatus, lastRunMs, lastSuccessMs,
 *     lastItemCount, lastNote, consecutiveFailures, runs, failures,
 *     recentItemCounts: [int, -1 = failed run]}] (suspicious first),
 *     events: [{atMs, source, status, itemCount, note}],
 *     totalEvents, cooldowns: [{host, untilMs, leftMs, strikes}],
 *     silences: [{hostTransport, strikes, mutedUntilMs, leftMs}],
 *     hostGates: int}}. Statuses: DELIVERED | EMPTY | FAILED | SKIPPED.</dd>
 *
 * <dt>{@code collectors} — the Kollektor-Uhr (limit: passes, default 200)</dt>
 * <dd>{@code {clock: [{source, lastStartMs, lastDurationMs, lastItems,
 *     lastFresh, lastError, nextDueMs, passes, misses}],
 *     passes: [{atMs, source, durationMs, items, fresh, error}]}}</dd>
 *
 * <dt>{@code basin} — the article pool (limit: pours, default 300)</dt>
 * <dd>{@code {stats: {size, maxItems, durable, live, sentiment,
 *     oldestPouredAtMs, newestPouredAtMs, bySource: {name: count},
 *     ageBuckets: {"<15m"|"15m-1h"|"1-6h"|"6-24h"|">24h": count}},
 *     inflowTotals: [{source, pours, offered, fresh, lastPourMs}],
 *     pours: [{atMs, source, offered, fresh, basinSize}]}}</dd>
 *
 * <dt>{@code runtime} — model, gate, pools (limit: llm calls, default 50)</dt>
 * <dd>{@code {model: {tag, runner: "mlx"|"gguf",
 *     contextTokensDeclared, contextEnforcedByRunner: bool,
 *     truncationLimitIfGguf, numPredict},
 *     llmMeasured: {calls, maxTokensIn, maxTokensOut, lastTokensIn,
 *     lastTokensOut, totalTokensIn, totalTokensOut, totalGateWaitMs,
 *     totalGenMs}, llmCalls: [{atMs, thread, gateWaitMs, genMs, tokensIn,
 *     tokensOut}], gate: {permits, inUse, interactiveWaiting,
 *     backgroundWaiting, interactiveStreak, priority},
 *     editorialQueue: {size, inFlight}, gauges: {name: long}}}.
 *     <b>Declared vs. measured is structural here:</b> {@code
 *     contextTokensDeclared} is what the app ASKS for; the MLX runner ignores
 *     num_ctx entirely ({@code contextEnforcedByRunner=false}) and the GGUF
 *     runner truncates at {@code truncationLimitIfGguf}; {@code
 *     llmMeasured.maxTokensIn} is what Ollama actually counted. Render the
 *     pair, never one number.</dd>
 *
 * <dt>{@code config}</dt>
 * <dd>{@code {differing: int, entries: [{key, value, default, differs}]}} —
 *     every {@code @Key} of every {@code @Section}, live value vs. a freshly
 *     constructed default. Honesty limit (by the TOML mechanism): the layer
 *     can prove "weicht vom Default ab", NOT "vom Nutzer gesetzt" — after
 *     first start the file contains every key, so presence proves nothing.</dd>
 *
 * <dt>{@code log} (limit: lines, default 500)</dt>
 * <dd>{@code {lines: [{atMs, level, logger, thread, message, error}],
 *     totalAppended, aggregate: {windowStartMs, lines, warnCount, errorCount,
 *     warnAndErrorByLogger: {logger: count}}}}. Only THIS JVM (the launcher
 *     logs separately); ring capacity 4000 lines.</dd>
 *
 * <dt>{@code reddit} (limit: lanes, default 200)</dt>
 * <dd>{@code {lanes: [{atMs, lane: "scan"|"comments"|"gap-fill", scope,
 *     durationMs, newThreads, newUpvotes, newComments, scanned, failed}],
 *     throttle: {bucketWaitMsTotal, bucketAcquires, backoffSleepMsTotal,
 *     backoffCount, lastRatelimitRemaining (-1 = never seen),
 *     lastRatelimitSeenAtMs}, sourceEvents: [{atMs, kind:
 *     "active"|"switch"|"demote"|"degraded"|"healthy", detail}],
 *     chain: [{name, active, demotedUntilMs (0 = not demoted)}]}}</dd>
 * </dl>
 *
 * <h2>All timestamps</h2> epoch millis; all durations millis. Every list is
 * oldest-first unless documented otherwise. Everything is Jackson-serialised
 * records/maps — no domain objects cross this seam.
 *
 * <h2>Not localised</h2> deliberately: this surface never appears in a
 * shipped build, so the i18n mandate does not apply (CLAUDE.md).
 */
@Singleton
public final class DebugBridge {

    static final List<String> COMMANDS = List.of(
            "overview", "sources", "collectors", "basin", "runtime", "config", "log", "reddit");

    private final PushHub hub;
    private final GlobalConfig config;
    private final AgentBrain brain;
    private final LlmGate llmGate;
    private final EditorialQueue editorialQueue;
    private final InMemoryArticlePool pool;
    private final HouseFetcher fetcher;
    private final RedditSource redditSource;

    @Inject
    public DebugBridge(PushHub hub, GlobalConfig config, AgentBrain brain, LlmGate llmGate,
            EditorialQueue editorialQueue, InMemoryArticlePool pool, HouseFetcher fetcher,
            RedditSource redditSource) {
        this.hub = hub;
        this.config = config;
        this.brain = brain;
        this.llmGate = llmGate;
        this.editorialQueue = editorialQueue;
        this.pool = pool;
        this.fetcher = fetcher;
        this.redditSource = redditSource;
        hub.on("debug", this::onRequest);
    }

    private void onRequest(Map<String, Object> payload) {
        String command = sanitizeCommand(Payloads.str(payload.get("command")));
        hub.broadcastSafe("debug-" + command, () -> respond(command, payload));
    }

    /** The broadcast type must stay a clean literal — never echo raw input into it. */
    static String sanitizeCommand(String raw) {
        if (raw == null) return "overview";
        String c = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return COMMANDS.contains(c) ? c : "unknown";
    }

    /** Builds one full response envelope. Package-private for tests (no socket needed). */
    Map<String, Object> respond(String command, Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        Object requestId = payload.get("requestId");
        if (requestId != null) out.put("requestId", requestId);
        out.put("at", System.currentTimeMillis());
        int limit = Math.max(1, Math.min(Payloads.intOr(payload.get("limit"), defaultLimit(command)), 5_000));
        out.put("data", section(command, limit));
        return out;
    }

    private static int defaultLimit(String command) {
        return switch (command) {
            case "log" -> 500;
            case "basin" -> 300;
            case "runtime" -> 50;
            default -> 200;
        };
    }

    private Map<String, Object> section(String command, int limit) {
        try {
            return switch (command) {
                case "overview" -> overview();
                case "sources" -> sources(limit);
                case "collectors" -> collectors(limit);
                case "basin" -> basin(limit);
                case "runtime" -> runtime(limit);
                case "config" -> configDiff(config);
                case "log" -> log(limit);
                case "reddit" -> reddit(limit);
                default -> Map.of("error", "unknown command", "commands", COMMANDS);
            };
        } catch (Throwable t) {
            return Map.of("error", String.valueOf(t));
        }
    }

    // ---- sections -------------------------------------------------------

    private Map<String, Object> overview() {
        Runtime rt = Runtime.getRuntime();
        var runtimeBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devMode", DevMode.active());
        out.put("pid", ProcessHandle.current().pid());
        out.put("javaVersion", System.getProperty("java.version", ""));
        out.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""));
        out.put("uptimeMs", runtimeBean.getUptime());
        out.put("heapUsedBytes", rt.totalMemory() - rt.freeMemory());
        out.put("heapMaxBytes", rt.maxMemory());
        out.put("processors", rt.availableProcessors());
        out.put("commands", COMMANDS);
        return out;
    }

    private Map<String, Object> sources(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        SourceHealthRegistry registry = SourceHealthRegistry.get();
        out.put("health", registry.snapshot());
        out.put("events", registry.recentEvents(limit));
        out.put("totalEvents", registry.totalEvents());
        if (fetcher != null) {
            out.put("cooldowns", fetcher.debugCooldowns());
            out.put("silences", fetcher.debugSilences());
            out.put("hostGates", fetcher.debugGateCount());
        }
        return out;
    }

    private Map<String, Object> collectors(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clock", CollectorClock.get().snapshot());
        out.put("passes", CollectorClock.get().recentPasses(limit));
        return out;
    }

    private Map<String, Object> basin(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (pool != null) out.put("stats", pool.debugStats());
        out.put("inflowTotals", BasinInflow.get().snapshotTotals());
        out.put("pours", BasinInflow.get().recentPours(limit));
        return out;
    }

    private Map<String, Object> runtime(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (brain != null) {
            Map<String, Object> model = new LinkedHashMap<>();
            String tag = brain.getAgentModelName();
            int ctx = brain.contextTokens();
            boolean mlx = tag != null && tag.endsWith("-mlx");
            model.put("tag", tag);
            model.put("runner", mlx ? "mlx" : "gguf");
            model.put("contextTokensDeclared", ctx);
            // MLX ignores num_ctx entirely; GGUF silently truncates below it —
            // the exact arithmetic ChatGateway warns with (see its comment).
            model.put("contextEnforcedByRunner", !mlx);
            model.put("truncationLimitIfGguf", ctx - Math.max((ctx - 5) / 2, 1));
            model.put("numPredict", brain.numPredict());
            out.put("model", model);
        }
        out.put("llmMeasured", LlmDebug.get().stats());
        out.put("llmCalls", LlmDebug.get().recentCalls(limit));
        if (llmGate != null) out.put("gate", llmGate.snapshot());
        if (editorialQueue != null) {
            out.put("editorialQueue", Map.of(
                    "size", editorialQueue.size(),
                    "inFlight", editorialQueue.inFlightCount()));
        }
        out.put("gauges", DebugGauges.get().sample());
        return out;
    }

    /**
     * Reflection diff of the live config against a freshly built default —
     * every {@code @Key} of every {@code @Section}. Runs only per request.
     */
    static Map<String, Object> configDiff(GlobalConfig live) throws Exception {
        GlobalConfig defaults = new GlobalConfig();
        List<Map<String, Object>> entries = new ArrayList<>();
        int differing = 0;
        for (Field sectionField : GlobalConfig.class.getDeclaredFields()) {
            Section section = sectionField.getAnnotation(Section.class);
            if (section == null) continue;
            sectionField.setAccessible(true);
            Object liveSection = live == null ? null : sectionField.get(live);
            Object defaultSection = sectionField.get(defaults);
            if (liveSection == null || defaultSection == null) continue;
            for (Field keyField : liveSection.getClass().getDeclaredFields()) {
                Key key = keyField.getAnnotation(Key.class);
                if (key == null) continue;
                keyField.setAccessible(true);
                Object liveValue = keyField.get(liveSection);
                Object defaultValue = keyField.get(defaultSection);
                boolean differs = !Objects.equals(liveValue, defaultValue);
                if (differs) differing++;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", key.value());
                entry.put("value", stringify(liveValue));
                entry.put("default", stringify(defaultValue));
                entry.put("differs", differs);
                entries.add(entry);
            }
        }
        // Differences first — they are the only interesting rows.
        entries.sort((a, b) -> Boolean.compare(
                Boolean.TRUE.equals(b.get("differs")), Boolean.TRUE.equals(a.get("differs"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("differing", differing);
        out.put("entries", entries);
        return out;
    }

    private static Object stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> log(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", DebugLog.recent(limit));
        out.put("totalAppended", DebugLog.totalAppended());
        out.put("aggregate", DebugLog.aggregate());
        return out;
    }

    private Map<String, Object> reddit(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        RedditPollDebug debug = RedditPollDebug.get();
        out.put("lanes", debug.recentLanes(limit));
        out.put("throttle", debug.throttle());
        out.put("sourceEvents", debug.recentSourceEvents(limit));
        out.put("chain", redditSource instanceof FallbackRedditSource f
                ? f.debugChain() : List.of());
        return out;
    }
}
