package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import de.bsommerfeld.wsbg.terminal.web.impl.pool.InMemoryArticlePool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the response contract without a socket — the bridge registers its
 * handler on an unstarted {@link PushHub} and {@code respond(...)} is called
 * directly, the same seam {@link ArchiveQueryBridgeTest} uses.
 */
class DebugBridgeTest {

    private static DebugBridge bridge(GlobalConfig config) {
        // Unavailable collaborators are null — every section must degrade,
        // never throw: the bridge is a reporter, not a dependency magnet.
        return new DebugBridge(new PushHub(), config, null, null, null,
                new InMemoryArticlePool(), null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @Test
    void commandSanitizerNeverEchoesRawInputIntoTheType() {
        assertEquals("sources", DebugBridge.sanitizeCommand("  Sources "));
        assertEquals("overview", DebugBridge.sanitizeCommand(null));
        assertEquals("unknown", DebugBridge.sanitizeCommand("evil'; drop"));
        assertEquals("unknown", DebugBridge.sanitizeCommand("headline-trace"));
    }

    @Test
    void requestIdIsEchoedVerbatim() {
        Map<String, Object> response = bridge(new GlobalConfig())
                .respond("overview", Map.of("requestId", 42));
        assertEquals(42, response.get("requestId"));
        assertEquals("overview", response.get("command"));
        assertNotNull(response.get("at"));
    }

    @Test
    void overviewReportsDevModeAndTheCommandList() {
        Map<String, Object> data = data(bridge(new GlobalConfig()).respond("overview", Map.of()));
        assertEquals(Boolean.TRUE, data.get("devMode"));
        assertEquals(DebugBridge.COMMANDS, data.get("commands"));
        assertTrue((Long) data.get("heapMaxBytes") > 0);
    }

    @Test
    void unknownCommandListsTheValidOnes() {
        Map<String, Object> data = data(bridge(new GlobalConfig()).respond("unknown", Map.of()));
        assertEquals(DebugBridge.COMMANDS, data.get("commands"));
    }

    @Test
    void configDiffIsEmptyOnDefaultsAndFlagsAChangedKey() throws Exception {
        GlobalConfig defaults = new GlobalConfig();
        assertEquals(0, DebugBridge.configDiff(defaults).get("differing"));

        GlobalConfig changed = new GlobalConfig();
        changed.getAgent().setIdentityDesk(false);
        Map<String, Object> diff = DebugBridge.configDiff(changed);
        assertEquals(1, diff.get("differing"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) diff.get("entries");
        Map<String, Object> first = entries.get(0); // differences sort first
        assertEquals("agent.identity-desk", first.get("key"));
        assertEquals("false", first.get("value"));
        assertEquals("true", first.get("default"));
        assertEquals(Boolean.TRUE, first.get("differs"));
    }

    @Test
    void everySectionAnswersWithoutItsCollaborators() {
        DebugBridge bridge = bridge(new GlobalConfig());
        for (String command : DebugBridge.COMMANDS) {
            Map<String, Object> data = data(bridge.respond(command, Map.of()));
            assertNotNull(data, command);
            assertNull(data.get("error"),
                    command + " must degrade gracefully, got: " + data.get("error"));
        }
    }

    @Test
    void basinSectionCarriesThePoolStats() {
        Map<String, Object> data = data(bridge(new GlobalConfig()).respond("basin", Map.of()));
        InMemoryArticlePool.BasinStats stats = (InMemoryArticlePool.BasinStats) data.get("stats");
        assertEquals(0, stats.size());
        assertEquals(25_000, stats.maxItems());
        assertFalse(stats.ageBuckets().isEmpty());
    }

    @Test
    void redditSectionReportsAnEmptyChainWithoutAFallbackSource() {
        Map<String, Object> data = data(bridge(new GlobalConfig()).respond("reddit", Map.of()));
        assertEquals(List.of(), data.get("chain"));
        assertNotNull(data.get("throttle"));
    }
}
