package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Was hat sich geändert" backend: the GitHub releases → wire-shape mapping
 * ({@link GitHubReleases#parseReleases}). The fetch / fresh-update wiring is
 * exercised live; here we pin the pure mapping (draft/prerelease/empty-body
 * filtering, field pick).
 */
class ChangelogBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsPublishedReleasesToWireShape() throws Exception {
        var root = MAPPER.readTree("""
                [
                  {"tag_name":"1.1.0","name":"I Am Speed","published_at":"2026-07-02T00:48:36Z",
                   "draft":false,"prerelease":false,"body":"# Was hat sich geändert?\\n\\nSchneller."},
                  {"tag_name":"1.0.0","name":"Das WSBG-Terminal","published_at":"2026-06-30T14:02:10Z",
                   "draft":false,"prerelease":false,"body":"**Alles. Das hier ist Tag 1.**"}
                ]""");
        List<Map<String, Object>> out = GitHubReleases.parseReleases(root);
        assertEquals(2, out.size());
        assertEquals("1.1.0", out.get(0).get("tag"));
        assertEquals("I Am Speed", out.get(0).get("name"));
        assertEquals("2026-07-02T00:48:36Z", out.get(0).get("publishedAt"));
        assertTrue(((String) out.get(0).get("body")).contains("Schneller."));
    }

    @Test
    void skipsDraftPrereleaseAndBodyless() throws Exception {
        var root = MAPPER.readTree("""
                [
                  {"tag_name":"1.2.0","draft":true,"prerelease":false,"body":"Entwurf"},
                  {"tag_name":"1.2.0-rc1","draft":false,"prerelease":true,"body":"RC"},
                  {"tag_name":"1.1.5","draft":false,"prerelease":false,"body":""},
                  {"tag_name":"1.1.0","draft":false,"prerelease":false,"body":"Echt."}
                ]""");
        List<Map<String, Object>> out = GitHubReleases.parseReleases(root);
        assertEquals(1, out.size());
        assertEquals("1.1.0", out.get(0).get("tag"));
    }

    @Test
    void toleratesNonArrayAndNull() {
        assertTrue(GitHubReleases.parseReleases(null).isEmpty());
        assertTrue(GitHubReleases.parseReleases(MAPPER.createObjectNode()).isEmpty());
    }
}
