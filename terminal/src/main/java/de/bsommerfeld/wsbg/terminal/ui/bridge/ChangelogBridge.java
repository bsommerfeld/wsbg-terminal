package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Was hat sich geändert" overlay's backend: after a fresh update, the first
 * client open finds the installed version ({@code version.txt}) ahead of the last
 * version whose notes the user saw ({@code user.last-seen-changelog-version}) and
 * pushes the recent GitHub release bodies so the page can open the overlay once.
 *
 * <p>Inbound: {@code {type:"changelog", payload:{command:"open"|"seen"}}} —
 * {@code open} pushes the releases unconditionally (manual/dev trigger),
 * {@code seen} persists the installed version so the overlay stays closed until
 * the next update. Outbound: one {@code changelog} broadcast
 * {@code {show, current, releases:[{tag, name, publishedAt, body}]}}.
 *
 * <p>A fresh install (empty last-seen) records the installed version silently —
 * the overlay is an update digest, not a first-run greeting. A dev {@code run.sh}
 * start has no {@code version.txt} and never auto-opens.
 */
@Singleton
public final class ChangelogBridge {

    private static final Logger LOG = LoggerFactory.getLogger(ChangelogBridge.class);

    private static final String RELEASES_URL =
            "https://api.github.com/repos/bsommerfeld/wsbg-terminal/releases?per_page=10";
    private static final long CACHE_TTL_MS = 10 * 60_000;

    private final GlobalConfig config;
    private final PushHub hub;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    // The GitHub fetch happens off the websocket thread; one lane is plenty.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "changelog-fetch");
        t.setDaemon(true);
        return t;
    });

    private volatile List<Map<String, Object>> cachedReleases;
    private volatile long cachedAtMs;

    @Inject
    public ChangelogBridge(GlobalConfig config, PushHub hub) {
        this.config = config;
        this.hub = hub;
        hub.on("changelog", this::onCommand);
        hub.onClientOpen(this::maybeShowFreshUpdate);
    }

    private void onCommand(Map<String, Object> payload) {
        Object cmd = payload.get("command");
        if ("open".equals(cmd)) {
            worker.submit(() -> pushReleases(UpdateService.readLocalVersion()));
        } else if ("seen".equals(cmd)) {
            markSeen();
        }
    }

    /** Auto-open check, run on every client connect (idempotent until "seen"). */
    private void maybeShowFreshUpdate() {
        String current = UpdateService.readLocalVersion();
        if (current == null) return; // dev run — no version.txt, never auto-open
        String lastSeen = config.getUser().getLastSeenChangelogVersion();
        if (lastSeen == null || lastSeen.isBlank()) {
            // Fresh install: nothing was updated, just remember where we start.
            config.getUser().setLastSeenChangelogVersion(current);
            config.save();
            return;
        }
        if (lastSeen.equals(current)) return;
        worker.submit(() -> pushReleases(current));
    }

    private void markSeen() {
        String current = UpdateService.readLocalVersion();
        if (current == null || current.equals(config.getUser().getLastSeenChangelogVersion())) return;
        config.getUser().setLastSeenChangelogVersion(current);
        config.save();
    }

    private void pushReleases(String current) {
        try {
            List<Map<String, Object>> releases = fetchReleases();
            if (releases.isEmpty()) return; // GitHub unreachable → retry on the next connect
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("show", true);
            m.put("current", current);
            m.put("releases", releases);
            hub.broadcast("changelog", m);
        } catch (Exception e) {
            LOG.debug("changelog fetch failed: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> fetchReleases() throws Exception {
        List<Map<String, Object>> cached = cachedReleases;
        if (cached != null && System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) return cached;
        HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "wsbg-terminal")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return cached != null ? cached : List.of();
        List<Map<String, Object>> releases = parseReleases(mapper.readTree(resp.body()));
        if (!releases.isEmpty()) {
            cachedReleases = releases;
            cachedAtMs = System.currentTimeMillis();
        }
        return releases;
    }

    /** Maps the GitHub releases array to the wire shape. Package-private for testing. */
    static List<Map<String, Object>> parseReleases(JsonNode root) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (root == null || !root.isArray()) return out;
        for (JsonNode rel : root) {
            if (rel.path("draft").asBoolean(false) || rel.path("prerelease").asBoolean(false)) continue;
            String tag = rel.path("tag_name").asText(null);
            String body = rel.path("body").asText(null);
            if (tag == null || tag.isBlank() || body == null || body.isBlank()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tag", tag);
            m.put("name", rel.path("name").asText(""));
            m.put("publishedAt", rel.path("published_at").asText(""));
            m.put("body", body);
            out.add(m);
        }
        return out;
    }
}
