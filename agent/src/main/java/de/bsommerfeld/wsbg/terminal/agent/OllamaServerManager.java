package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.OllamaEndpoint;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages our <strong>own, isolated</strong> Ollama instance.
 *
 * <p>
 * We never use the user's system Ollama. Instead we run a private server bound
 * to {@link #PORT} (not the default 11434) from the standalone binary the setup
 * script installs under {@code <appData>/ollama/bin}, reading and writing models
 * in {@code <appData>/ollama/models} via the {@code OLLAMA_MODELS} env var. This keeps a
 * user's existing Ollama — binary, models, and any server on 11434 — completely
 * untouched, and means uninstalling is just deleting the app data folder.
 *
 * <p>
 * The only "reuse" that happens is reconnecting to <em>our own</em> server on
 * {@link #PORT} if it survived a previous crash — never the user's. Such a
 * survivor is <em>adopted</em>, not merely used: it is claimed as this session's
 * process and shut down with it, so an orphan cannot live on across restart
 * after restart (see {@link #adoptRunningServer}).
 *
 * @see StorageUtils
 */
@Singleton
public final class OllamaServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaServerManager.class);

    /**
     * Endpoint coordinates — re-exported from {@link OllamaEndpoint} (core) so the
     * isolated instance has a single source of truth. Deliberately not Ollama's
     * default 11434, so we never collide with or hijack a server the user is running.
     */
    public static final int PORT = OllamaEndpoint.PORT;
    public static final String HOST = OllamaEndpoint.HOST;
    public static final String BASE_URL = OllamaEndpoint.BASE_URL;

    /** Sub-directory of the app data dir holding the isolated runtime + models. */
    static final String OLLAMA_DIR = "ollama";

    static final int MAX_RETRIES = 15;
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);
    static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

    private final Path appDataDir;

    /** Set only when WE spawned the server — kept for the exit-code diagnostics in {@link #waitForServer}. */
    private Process serverProcess;

    /**
     * The server this app is responsible for killing — whether we spawned it or
     * adopted it from a previous run. A {@link ProcessHandle} rather than a
     * {@link Process} because an adopted orphan is not our child and there is no
     * {@code Process} object for it.
     */
    private ProcessHandle ownedServer;

    /** Production constructor — resolves the OS-native app data directory. */
    public OllamaServerManager() {
        this(StorageUtils.getAppDataDir());
    }

    /** Test seam: inject the app data directory explicitly. */
    OllamaServerManager(Path appDataDir) {
        this.appDataDir = appDataDir;
    }

    /**
     * Ordered candidate locations of the {@code ollama} binary inside {@code ollama/},
     * accounting for the differing internal layouts of the per-platform archives
     * (linux {@code .tar.zst} → {@code bin/ollama}; macOS {@code .tgz} → bare
     * {@code ollama}; Windows {@code .zip} → {@code ollama.exe} at the root). The
     * lib/ folder always stays next to the binary, so we never move it apart.
     */
    static List<Path> candidateBinaries(Path appDataDir, String osName) {
        Path ai = appDataDir.resolve(OLLAMA_DIR);
        if (osName.toLowerCase().contains("win")) {
            return List.of(ai.resolve("ollama.exe"), ai.resolve("bin").resolve("ollama.exe"));
        }
        return List.of(ai.resolve("bin").resolve("ollama"), ai.resolve("ollama"));
    }

    /** Our isolated model store ({@code OLLAMA_MODELS}). */
    static Path modelsDir(Path appDataDir) {
        return appDataDir.resolve(OLLAMA_DIR).resolve("models");
    }

    /**
     * Concurrent gemma4 request slots — both Ollama's {@code NUM_PARALLEL} AND the app-side
     * LLM gate ({@link AgentBrain}) read this, so they always agree. Fixed at 2.
     *
     * <p>3 was tried (RAM-adaptive) on the theory that the dominant compose gate-wait was a
     * permit shortage. Profiling refuted it: gemma4 is GPU-bound, so at 2 slots the GPU is
     * already ~saturated — a 3rd concurrent request just time-slices the GPU, so every call's
     * gen-time rose (compose 8→12s, extraction 13→28s), gate-hold grew with it, and net
     * throughput FELL (4.0→3.5 composes/min). The real lever is less GPU work per call
     * (smaller prefill / killing the JSON whitespace-loop), not more parallelism.
     */
    public static int llmParallelism() {
        return 2;
    }

    /**
     * Ensures our isolated Ollama server on {@link #PORT} is reachable, starting
     * it from the bundled binary if needed.
     *
     * @param baseUrl our private endpoint ({@link #BASE_URL}); a reachable server
     *                here is always one we started, never the user's (which runs
     *                on the default 11434)
     * @throws IllegalStateException if the server cannot be reached after retries
     */
    public void ensureRunning(String baseUrl) {
        LOG.info("Checking our Ollama server at {}...", baseUrl);

        if (isReachable(baseUrl)) {
            adoptRunningServer(baseUrl);
            return;
        }

        LOG.warn("Our Ollama server not reachable at {} — starting isolated instance", baseUrl);
        startServer();
        waitForServer(baseUrl);
        LOG.info("Isolated Ollama server is ready at {}", baseUrl);
    }

    /**
     * Takes ownership of a server that is already listening on {@link #PORT} —
     * an orphan from a previous run that died without its shutdown (a SIGKILL, a
     * hard crash, a dev run cut off at the terminal).
     *
     * <p><b>Why this exists.</b> Reusing such a server was safe but not enough:
     * {@link #shutdown()} only ever killed a process it had spawned itself, so a
     * reused one was left running — and the NEXT start found it again, reused it
     * again, and left it again. One orphan therefore became immortal, holding its
     * model resident forever ({@code KEEP_ALIVE=-1}) with no app attached to it.
     * Adopting it closes that loop: the orphan dies with this session.
     *
     * <p>Claiming it is safe by construction. {@link #PORT} is ours and is
     * deliberately NOT Ollama's default 11434, so whatever answers there came
     * from a previous run of this app — never the user's own instance. The
     * process lookup insists on that too: only a binary living inside our own
     * {@code <appData>/ollama} directory is a candidate.
     *
     * <p>If the process cannot be identified (an OS that hides other processes'
     * command lines, a binary moved since it started), the server is still used —
     * it works, it just outlives us, exactly as before. That is a degradation
     * worth a WARNing, not a reason to refuse a perfectly good server.
     */
    private void adoptRunningServer(String baseUrl) {
        ownedServer = findOwnServerProcess().orElse(null);
        if (ownedServer != null) {
            LOG.info("Our Ollama server already running at {} — adopted it (PID: {}), "
                    + "it will be shut down with this session", baseUrl, ownedServer.pid());
        } else {
            LOG.warn("Our Ollama server already running at {} — reusing it, but its process "
                    + "could not be identified. It will keep running after this session.",
                    baseUrl);
        }
    }

    /**
     * Finds the {@code ollama serve} process belonging to THIS app: one whose
     * binary lives inside our own {@code <appData>/ollama} directory.
     *
     * <p>Both the server and its {@code runner} children run that same binary, so
     * the match alone does not identify the root. Two ways to pick it, in order:
     * the first argument is {@code serve}; failing that (some platforms do not
     * expose other processes' arguments) the one candidate whose parent is not
     * itself a candidate — a runner always hangs below its server. Killing the
     * root reaps the children through the existing tree teardown.
     */
    private java.util.Optional<ProcessHandle> findOwnServerProcess() {
        Path ollamaDir = appDataDir.resolve(OLLAMA_DIR);
        List<ProcessHandle> ours = ProcessHandle.allProcesses()
                .filter(h -> runsBinaryUnder(h, ollamaDir))
                .toList();
        if (ours.isEmpty()) return java.util.Optional.empty();

        java.util.Optional<ProcessHandle> byArgs = ours.stream()
                .filter(h -> {
                    String[] args = h.info().arguments().orElse(new String[0]);
                    return args.length > 0 && "serve".equals(args[0]);
                })
                .findFirst();
        if (byArgs.isPresent()) return byArgs;

        java.util.Set<Long> pids = ours.stream().map(ProcessHandle::pid)
                .collect(java.util.stream.Collectors.toSet());
        return ours.stream()
                .filter(h -> h.parent().map(p -> !pids.contains(p.pid())).orElse(true))
                .findFirst();
    }

    /** Whether {@code h} runs an executable located inside {@code dir}. */
    private static boolean runsBinaryUnder(ProcessHandle h, Path dir) {
        String command = h.info().command().orElse("");
        if (command.isEmpty()) return false;
        try {
            return Path.of(command).startsWith(dir);
        } catch (Exception e) {
            return false; // unparseable command line — not a candidate
        }
    }

    /**
     * Destroys the server this session owns — the one we spawned, or the orphan
     * we adopted in {@link #adoptRunningServer}.
     */
    public void shutdown() {
        // Flag the teardown FIRST — even when the server could not be claimed, any
        // lane still mid-call must fail fast instead of riding the connect-retry ladder.
        ChatGateway.noteAppShutdown();
        ProcessHandle server = ownedServer;
        if (server == null) {
            LOG.debug("No owned Ollama server to shut down");
            return;
        }

        long pid = server.pid();
        LOG.info("Shutting down owned Ollama server (PID: {})...", pid);

        // Snapshot the process tree *before* destroying the root. 'ollama serve'
        // spawns 'ollama runner' children that hold the model in memory and keep
        // file handles open; destroying only the parent orphans them (there is no
        // parent→child kill propagation on Windows). After the parent dies its
        // descendants are reparented, so descendants() would return nothing —
        // hence we capture them up front and reap them at the end.
        List<ProcessHandle> tree = server.descendants().toList();

        server.destroy();
        try {
            // Grace period before force-kill — Ollama needs time to flush model
            // state. 5s matches the typical unload latency. onExit() rather than
            // Process.waitFor: an adopted orphan is not our child, so there is no
            // Process to wait on — only its handle.
            server.onExit().get(5, TimeUnit.SECONDS);
            LOG.info("Ollama server (PID: {}) shut down cleanly", pid);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.warn("Ollama server did not exit within 5s — force killing PID {}", pid);
            server.destroyForcibly();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for Ollama shutdown — force killing PID {}", pid);
            server.destroyForcibly();
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            LOG.warn("Waiting for Ollama shutdown failed ({}) — force killing PID {}",
                    e.getCause() == null ? e.toString() : e.getCause().toString(), pid);
            server.destroyForcibly();
        }

        // Reap any runner children that outlived the parent.
        for (ProcessHandle child : tree) {
            if (child.isAlive()) {
                LOG.warn("Force killing orphaned Ollama child process (PID: {})", child.pid());
                child.destroyForcibly();
            }
        }

        ownedServer = null;
        serverProcess = null;
    }

    /** Whether a server is ours to shut down — spawned by us or adopted from a previous run. */
    public boolean isManaged() {
        return ownedServer != null && ownedServer.isAlive();
    }

    /**
     * Resolves the path to our bundled ollama binary. Falls back to a bare
     * {@code "ollama"} (PATH lookup) only if the bundle is missing — even then
     * isolation holds, because the OLLAMA_HOST/OLLAMA_MODELS env still pins the
     * port and model store away from the user's instance.
     */
    private String resolveBinary() {
        for (Path candidate : candidateBinaries(appDataDir, System.getProperty("os.name", ""))) {
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        LOG.warn("Bundled ollama binary not found under {}/{} — falling back to PATH. "
                + "Isolation (own port + model store) still applies.", appDataDir, OLLAMA_DIR);
        return "ollama";
    }

    private void startServer() {
        try {
            String binary = resolveBinary();
            Path models = modelsDir(appDataDir);

            ProcessBuilder pb = new ProcessBuilder(binary, "serve");
            pb.redirectErrorStream(true);

            // Discard output — Ollama logs to stderr internally, and we don't
            // need its output polluting our logs.
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            // ── Isolation env ──────────────────────────────────────────────
            // OLLAMA_HOST pins our server to the private port so it never
            // collides with a user's default-port (11434) instance.
            // OLLAMA_MODELS points at our own model store, so we never read or
            // write the user's ~/.ollama models.
            pb.environment().put("OLLAMA_HOST", HOST + ":" + PORT);
            pb.environment().put("OLLAMA_MODELS", models.toString());

            // Run ollama from a neutral directory. By default a child process
            // inherits our working directory (the app data folder); on Windows
            // that folder is then locked for the server's lifetime, so an
            // orphaned/crashed ollama makes the install undeletable. The temp
            // dir is always writable and outside the install tree.
            File neutralDir = new File(System.getProperty("java.io.tmpdir"));
            if (neutralDir.isDirectory()) {
                pb.directory(neutralDir);
            }

            // Concurrent gemma4 slots — fixed at 2 (see llmParallelism(): gemma4 is
            // GPU-bound, so a 3rd concurrent request just time-slices the GPU and net
            // throughput FALLS). The KV cache scales with num_ctx × this.
            pb.environment().putIfAbsent("OLLAMA_NUM_PARALLEL", String.valueOf(llmParallelism()));

            // Flash attention + quantised KV cache roughly halve the KV-cache
            // memory at negligible quality loss. q8_0 is the conservative
            // choice (q4_0 saves more but can degrade long-context recall).
            //
            // CAVEAT (measured 2026-08-03, gemma4:26b-mlx on an M4 Max): both
            // are ggml/llama.cpp knobs. A `-mlx` tag runs the MLX runner, which
            // IGNORES them — q8_0, f16 and flash=0 all loaded at an identical
            // 16.02 GiB. Since the MLX twin is the STANDARD on Apple Silicon
            // (ModelCatalog.tagFor), that whole fleet runs unquantised here.
            // Harmless in practice — the KV share measured ~1.8 GB at an 18k
            // prompt — so this is documentation, not a bug to chase. What DOES
            // dominate is the resident weights plus MLX's high-water allocator,
            // which never shrinks back (see OLLAMA_KEEP_ALIVE below).
            pb.environment().putIfAbsent("OLLAMA_FLASH_ATTENTION", "1");
            pb.environment().putIfAbsent("OLLAMA_KV_CACHE_TYPE", "q8_0");

            // Keep the resident gemma4 pinned in memory. Without this Ollama unloads it
            // after its 5-min default keep-alive; the next call then forces a reload that
            // has to wait for the GPU. The deployment is single-model now (the embedding
            // model was removed 2026-07-03), but MAX_LOADED_MODELS stays at 2 as harmless
            // headroom.
            //
            // The price, on the big tiers: pinned means the run's PEAK footprint
            // is what stays resident. Measured 2026-08-03 on gemma4:26b-mlx —
            // 17 GB freshly loaded, grown to ~25 GB under a live pipeline session,
            // and MLX never gives it back. `putIfAbsent` is the escape hatch: an
            // OLLAMA_KEEP_ALIVE already exported into the app's environment wins,
            // so a finite value (e.g. 15m) trades ~4 s of reload latency for the
            // idle memory.
            pb.environment().putIfAbsent("OLLAMA_KEEP_ALIVE", "-1");
            pb.environment().putIfAbsent("OLLAMA_MAX_LOADED_MODELS", "2");

            serverProcess = pb.start();
            ownedServer = serverProcess.toHandle();
            LOG.info("Started isolated '{} serve' on {}:{} (models={}, NUM_PARALLEL={}, "
                            + "FLASH_ATTENTION=1, KV_CACHE_TYPE=q8_0, KEEP_ALIVE=-1, "
                            + "MAX_LOADED_MODELS=2, PID={})",
                    binary, HOST, PORT, models, llmParallelism(), serverProcess.pid());
        } catch (Exception e) {
            LOG.error("Failed to start isolated 'ollama serve' — was the bundled binary "
                    + "installed under {}/{}/bin by the setup script?", appDataDir, OLLAMA_DIR, e);
            throw new IllegalStateException(
                    "Failed to start isolated 'ollama serve' — bundled binary missing?", e);
        }
    }

    private void waitForServer(String baseUrl) {
        for (int i = 1; i <= MAX_RETRIES; i++) {
            if (isReachable(baseUrl)) {
                LOG.info("Ollama server ready after {} attempt(s)", i);
                return;
            }

            // Check early exit — process crashed before becoming ready
            if (serverProcess != null && !serverProcess.isAlive()) {
                int exitCode = serverProcess.exitValue();
                serverProcess = null;
                LOG.error("ollama serve exited prematurely with code {}", exitCode);
                throw new IllegalStateException(
                        "ollama serve exited with code " + exitCode);
            }

            LOG.debug("Waiting for Ollama server... (attempt {}/{})", i, MAX_RETRIES);
            sleep(RETRY_INTERVAL);
        }

        LOG.error("Ollama server did not become reachable within {}s", MAX_RETRIES);
        // Clean up the hanging process
        if (serverProcess != null && serverProcess.isAlive()) {
            LOG.warn("Killing unresponsive Ollama server (PID: {})", serverProcess.pid());
            serverProcess.destroyForcibly();
            serverProcess = null;
        }
        throw new IllegalStateException(
                "Ollama server did not become reachable within " + MAX_RETRIES + "s");
    }

    /**
     * Probes the Ollama HTTP root endpoint. A 200 response confirms
     * the server is up and accepting connections.
     */
    boolean isReachable(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(HEALTH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            LOG.trace("Ollama health check failed: {}", e.getMessage());
            return false;
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
