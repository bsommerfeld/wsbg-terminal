package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.ui.AppMain;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-app update indicator + relaunch, the counterpart to the launcher's
 * auto-update opt-out. When the user turned auto-update OFF, the launcher no
 * longer updates on every start — so the running app periodically checks GitHub
 * for a newer release and, if one exists, lights the titlebar's green "update
 * now" button ({@code update-available} broadcast). Clicking it ({@code update}
 * / {@code apply}) hands off to {@link AppMain#relaunchForUpdate()}, which closes
 * cleanly and relaunches the launcher to apply the update.
 *
 * <p>Active only when auto-update is off AND the launcher told us its executable
 * path ({@code WSBG_LAUNCHER_EXECUTABLE}); otherwise there is nothing to do
 * (the launcher already updates, or — in a dev {@code run.sh} start — there is no
 * launcher to relaunch), so no check runs and the button stays hidden.
 */
@Singleton
public final class UpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateService.class);

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/bsommerfeld/wsbg-terminal/releases/latest";
    private static final long INITIAL_DELAY_SECONDS = 25;
    private static final long CHECK_INTERVAL_SECONDS = 6 * 3600;

    private final PushHub hub;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private volatile boolean available = false;
    private volatile String latestVersion = null;

    @Inject
    public UpdateService(GlobalConfig config, PushHub hub) {
        this.hub = hub;
        boolean autoUpdate = config.getUser().isAutoUpdate();
        String launcherExe = System.getenv("WSBG_LAUNCHER_EXECUTABLE");
        boolean haveLauncher = launcherExe != null && !launcherExe.isBlank();
        this.enabled = !autoUpdate && haveLauncher;

        hub.on("update", this::onCommand);
        hub.onClientOpen(this::push);

        if (enabled) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "update-check");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::check,
                    INITIAL_DELAY_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } else {
            LOG.info("In-app update check disabled (autoUpdate={}, launcher={}).",
                    autoUpdate, haveLauncher);
        }
    }

    private void onCommand(Map<String, Object> payload) {
        if (enabled && available && "apply".equals(payload.get("command"))) {
            LOG.info("User requested in-app update → relaunching launcher.");
            AppMain.relaunchForUpdate();
        }
    }

    /** One GitHub poll: compare the latest release tag to our installed version. */
    private void check() {
        try {
            String local = readLocalVersion();
            if (local == null) return; // unknown installed version (dev) → never nag
            String remote = fetchLatestTag();
            if (remote == null) return;
            boolean nowAvailable = !remote.equals(local);
            if (nowAvailable != available || !remote.equals(latestVersion)) {
                available = nowAvailable;
                latestVersion = remote;
                push();
            }
        } catch (Exception e) {
            LOG.debug("Update check failed: {}", e.getMessage());
        }
    }

    private void push() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available);
        m.put("version", latestVersion);
        hub.broadcast("update-available", m);
    }

    /** Reads the installed version tag the launcher wrote to {@code version.txt}. */
    private static String readLocalVersion() {
        try {
            Path vf = StorageUtils.getAppDataDir().resolve("version.txt");
            if (!Files.exists(vf)) return null;
            String s = Files.readString(vf).strip();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches the latest GitHub release tag, or null on any failure. */
    private String fetchLatestTag() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "wsbg-terminal")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        JsonNode root = mapper.readTree(resp.body());
        String tag = root.path("tag_name").asText(null);
        return tag == null || tag.isBlank() ? null : tag;
    }
}
