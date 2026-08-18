package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared GitHub REST plumbing for the two update-facing bridges: the latest
 * release tag (for {@link UpdateService}'s "update available" check) and the
 * recent release bodies (for {@link ChangelogBridge}'s "was hat sich geändert"
 * overlay). Extracted so both stop duplicating the same request builder / mapper /
 * client, and so version-reading is no longer a static reach-through between the
 * two services.
 */
@Singleton
public final class GitHubReleases {

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/bsommerfeld/wsbg-terminal/releases/latest";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/bsommerfeld/wsbg-terminal/releases?per_page=10";
    private static final long CACHE_TTL_MS = 10 * 60_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final GlobalConfig config;

    private volatile List<Map<String, Object>> cachedReleases;
    private volatile long cachedAtMs;
    private volatile boolean cachedExperimental;

    @Inject
    public GitHubReleases(GlobalConfig config) {
        this.config = config;
    }

    /**
     * Whether this install accepts pre-releases ({@code user.experimental-updates},
     * the same key the launcher's channel question writes). Both readers below
     * follow it: an install running a pre-release must compare itself against
     * pre-releases, or the titlebar's update button would light up permanently
     * against an older stable tag.
     */
    private boolean experimental() {
        return config.getUser().isExperimentalUpdates();
    }

    /** Fetches the newest release tag this install is entitled to, or null on any failure. */
    String latestTag() throws Exception {
        if (experimental()) {
            // GitHub's "latest" endpoint excludes pre-releases by definition,
            // so the experimental channel has to read the listing instead.
            HttpResponse<String> resp = get(RELEASES_URL);
            if (resp.statusCode() != 200) return null;
            JsonNode root = mapper.readTree(resp.body());
            return firstPublishedTag(root);
        }
        HttpResponse<String> resp = get(LATEST_RELEASE_URL);
        if (resp.statusCode() != 200) return null;
        JsonNode root = mapper.readTree(resp.body());
        String tag = root.path("tag_name").asText(null);
        return tag == null || tag.isBlank() ? null : tag;
    }

    /**
     * The recent releases in wire shape, cached for {@value #CACHE_TTL_MS} ms.
     * The channel is part of the cache identity — flipping the setting must not
     * leave the overlay showing the other channel's notes for ten minutes.
     */
    List<Map<String, Object>> recentReleases() throws Exception {
        boolean experimental = experimental();
        List<Map<String, Object>> cached = cachedReleases;
        if (cached != null && cachedExperimental == experimental
                && System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) {
            return cached;
        }
        HttpResponse<String> resp = get(RELEASES_URL);
        if (resp.statusCode() != 200) return cached != null ? cached : List.of();
        List<Map<String, Object>> releases = parseReleases(mapper.readTree(resp.body()), experimental);
        if (!releases.isEmpty()) {
            cachedReleases = releases;
            cachedAtMs = System.currentTimeMillis();
            cachedExperimental = experimental;
        }
        return releases;
    }

    /**
     * The newest publishable tag in a release listing — the first non-draft
     * entry, pre-releases included. GitHub returns the array newest-first.
     * Package-private for testing.
     */
    static String firstPublishedTag(JsonNode root) {
        if (root == null || !root.isArray()) return null;
        for (JsonNode rel : root) {
            if (rel.path("draft").asBoolean(false)) continue;
            String tag = rel.path("tag_name").asText(null);
            if (tag != null && !tag.isBlank()) return tag;
        }
        return null;
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "wsbg-terminal")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Maps the GitHub releases array to the wire shape. Drafts never surface;
     * pre-releases only on the experimental channel, where they are the notes
     * for the version actually installed. Package-private for testing.
     */
    static List<Map<String, Object>> parseReleases(JsonNode root, boolean includePreReleases) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (root == null || !root.isArray()) return out;
        for (JsonNode rel : root) {
            if (rel.path("draft").asBoolean(false)) continue;
            if (!includePreReleases && rel.path("prerelease").asBoolean(false)) continue;
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

    /**
     * Reads the installed version tag the launcher wrote to {@code version.txt},
     * or null (dev run / no file). Shared by both update-facing bridges.
     */
    static String readLocalVersion() {
        try {
            Path vf = StorageUtils.getAppDataDir().resolve("version.txt");
            if (!Files.exists(vf)) return null;
            String s = Files.readString(vf).strip();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }
}
