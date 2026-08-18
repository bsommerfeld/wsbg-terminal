package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.agent.SubjectRegistry;
import de.bsommerfeld.wsbg.terminal.agent.SubjectUnit;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
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
        return bridge(config, null);
    }

    private static DebugBridge bridge(GlobalConfig config, SubjectRegistry registry) {
        return new DebugBridge(new PushHub(), config, null, null, null,
                new InMemoryArticlePool(), null, null, registry, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @Test
    void commandSanitizerNeverEchoesRawInputIntoTheType() {
        assertEquals("sources", DebugBridge.sanitizeCommand("  Sources "));
        assertEquals("subjects", DebugBridge.sanitizeCommand("subjects"));
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
    @SuppressWarnings("unchecked")
    void overviewMeasuresThisProcessAgainstTheMachineAndTheAppDataDir() {
        Map<String, Object> data = data(bridge(new GlobalConfig()).respond("overview", Map.of()));

        Map<String, Object> memory = (Map<String, Object>) data.get("memory");
        List<Map<String, Object>> processes = (List<Map<String, Object>>) memory.get("processes");
        assertEquals(1, processes.size(), "no Ollama manager — only this JVM is listed");
        assertEquals(ProcessHandle.current().pid(), processes.get(0).get("pid"));
        if (Boolean.TRUE.equals(memory.get("available"))) {
            // RSS, not heap: the JVM's footprint is always the larger number.
            assertTrue((Long) memory.get("terminalRssBytes") > (Long) data.get("heapUsedBytes"));
        }

        Map<String, Object> storage = (Map<String, Object>) data.get("storage");
        assertNotNull(storage.get("path"));
        assertNotNull(storage.get("entries"));
        assertTrue((Long) storage.get("totalBytes") >= 0);
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

    // ---- subjects -------------------------------------------------------

    private static SubjectRegistry registryWithUnits() {
        SubjectRegistry registry = new SubjectRegistry();
        SubjectUnit gemini = registry.findOrCreate("GEMINI-USD", "Gemini");
        gemini.updateResolved("Gemini", "GEMINI-USD",
                new MarketSnapshot("GEMINI25963-USD", 0.004, 0.004, 0.0,
                        Double.NaN, Double.NaN, -1, Double.NaN, Double.NaN,
                        "USD", "CCC", 0, List.of()),
                null);
        gemini.addEvidence(new SubjectUnit.EvidenceRef("t3_a", "t1_a", "Gemini 3 ist raus",
                "reddit", java.time.Instant.now().getEpochSecond()));
        registry.markDirty("GEMINI-USD");

        SubjectUnit iren = registry.findOrCreate("IREN", "IREN Limited");
        iren.updateResolved("IREN Limited", "IREN",
                new MarketSnapshot("IRE.MI", 2.1, 2.0, 5.0,
                        Double.NaN, Double.NaN, -1, Double.NaN, Double.NaN,
                        "EUR", "Milan", 0, List.of()),
                null);
        iren.noteIsin("IT0003027817");
        iren.addHeadline("IREN springt", "BULLISH");

        registry.findOrCreate("name:hopium", "Hopium");
        return registry;
    }

    @Test
    void subjectsSectionAnswersEmptyWithoutARegistry() {
        Map<String, Object> data = data(bridge(new GlobalConfig()).respond("subjects", Map.of()));
        assertEquals(0, data.get("total"));
        assertEquals(List.of(), data.get("units"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void subjectsSectionShowsIdentityRowDirtyFlagAndInterestingFirstOrder() {
        SubjectRegistry registry = registryWithUnits();
        Map<String, Object> data = data(bridge(new GlobalConfig(), registry)
                .respond("subjects", Map.of()));
        assertEquals(3, data.get("total"));
        assertEquals(1, data.get("dirtyCount"));
        assertEquals(2, data.get("instrumentCount"));

        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        assertEquals(3, units.size());

        // The dirty unit sorts first — it's what the pipeline is about to act on.
        Map<String, Object> first = units.get(0);
        assertEquals("GEMINI-USD", first.get("id"));
        assertEquals(Boolean.TRUE, first.get("dirty"));
        assertEquals(Boolean.TRUE, first.get("uncomposedEvidence"));
        // The identity row that makes the junk-coin resolve visible at a glance:
        // Yahoo's ACTUAL symbol + exchange/currency next to the canonical name.
        Map<String, Object> market = (Map<String, Object>) first.get("market");
        assertEquals("GEMINI25963-USD", market.get("symbol"));
        assertEquals("CCC", market.get("exchange"));

        // The IREN case: ticker looks right, ISIN + venue betray the wrong paper.
        Map<String, Object> iren = units.stream()
                .filter(u -> "IREN".equals(u.get("id"))).findFirst().orElseThrow();
        assertEquals("IT0003027817", iren.get("isin"));
        Map<String, Object> irenMarket = (Map<String, Object>) iren.get("market");
        assertEquals("IRE.MI", irenMarket.get("symbol"));
        assertEquals("EUR", irenMarket.get("currency"));
        Map<String, Object> lastHeadline = (Map<String, Object>) iren.get("lastHeadline");
        assertEquals("IREN springt", lastHeadline.get("text"));
        assertEquals("BULLISH", lastHeadline.get("sentiment"));
        assertEquals(1, iren.get("headlineCount"));

        // A never-resolved theme unit degrades to nulls, not errors.
        Map<String, Object> theme = units.stream()
                .filter(u -> "name:hopium".equals(u.get("id"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, theme.get("instrument"));
        assertNull(theme.get("market"));
        assertNull(theme.get("lastHeadline"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void subjectsLimitCapsAfterSortingWhileTotalsCountEverything() {
        Map<String, Object> data = data(bridge(new GlobalConfig(), registryWithUnits())
                .respond("subjects", Map.of("limit", 1)));
        assertEquals(3, data.get("total"));
        assertEquals(1, data.get("shown"));
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        assertEquals("GEMINI-USD", units.get(0).get("id"), "the dirty unit survives the cap");
    }

    @Test
    void subjectsDebugReadNeverDrainsTheDirtySet() {
        // THE non-negotiable: a debug read must not swallow the pipeline's
        // pending compose. After any number of debug reads, drainDirty() still
        // hands the pipeline its full dirty set.
        SubjectRegistry registry = registryWithUnits();
        DebugBridge bridge = bridge(new GlobalConfig(), registry);
        bridge.respond("subjects", Map.of());
        bridge.respond("subjects", Map.of());
        assertEquals(java.util.Set.of("GEMINI-USD"), registry.drainDirty(),
                "a debug read drained the dirty set — the headline would never come");
    }

    @Test
    void redditSectionReportsAnEmptyChainWithoutAFallbackSource() {
        Map<String, Object> data = data(bridge(new GlobalConfig()).respond("reddit", Map.of()));
        assertEquals(List.of(), data.get("chain"));
        assertNotNull(data.get("throttle"));
    }
}
