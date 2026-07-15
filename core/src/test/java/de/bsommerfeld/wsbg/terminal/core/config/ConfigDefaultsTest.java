package de.bsommerfeld.wsbg.terminal.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigDefaultsTest {

    @Test
    void globalConfig_shouldInitializeWithDefaults() {
        var config = new GlobalConfig();

        assertNotNull(config.getAgent());
        assertNotNull(config.getReddit());
        assertNotNull(config.getHeadlines());
        assertNotNull(config.getUser());
    }

    @Test
    void agentConfig_shouldDefaultToReasoningPower() {
        var config = new AgentConfig();
        assertEquals("REASONING_POWER", config.getEditorialModel());
        assertEquals(Model.REASONING_POWER, config.resolveEditorialModel());
    }

    @Test
    void agentConfig_shouldResolveContextTokensAutomatically() {
        // Fully automatic (no config knob): the window scales with the
        // machine's memory and never falls below the 8k end-user floor.
        assertTrue(new AgentConfig().resolveContextTokens() >= 8192);
    }

    @Test
    void agentConfig_shouldScaleAutoContextWindowByMemoryTier() {
        long gb = 1L << 30;
        assertEquals(8192, AgentConfig.contextTokensFor(0));       // unprobeable → floor
        assertEquals(8192, AgentConfig.contextTokensFor(16 * gb));
        assertEquals(16384, AgentConfig.contextTokensFor(32 * gb));
        assertEquals(16384, AgentConfig.contextTokensFor(48 * gb));
        assertEquals(24576, AgentConfig.contextTokensFor(64 * gb));
        assertEquals(24576, AgentConfig.contextTokensFor(128 * gb));
    }

    @Test
    void agentConfig_shouldDegradeStaleMlxConfigToReasoningPower() {
        var config = new AgentConfig();
        config.setEditorialModel("REASONING_POWER_MLX"); // no longer a valid enum
        assertEquals(Model.REASONING_POWER, config.resolveEditorialModel());
    }

    @Test
    void agentConfig_shouldDegradeRemovedAgentModelToReasoningPower() {
        var config = new AgentConfig();
        config.setEditorialModel("REASONING_AGENT_POWER"); // removed from the enum
        assertEquals(Model.REASONING_POWER, config.resolveEditorialModel());
    }

    @Test
    void agentConfig_shouldFallBackOnUnknownEditorialModel() {
        var config = new AgentConfig();
        config.setEditorialModel("NONSENSE");
        assertEquals(Model.REASONING_POWER, config.resolveEditorialModel());
    }

    @Test
    void redditConfig_shouldHaveReasonableDefaults() {
        var config = new RedditConfig();

        assertEquals(List.of("wallstreetbetsGER"), config.getSubreddits());
        // 180 s scan cadence — deliberate, keeps the anonymous Reddit JSON
        // endpoint inside its ~100 req / 10 min soft limit (see RedditScraper).
        assertEquals(180, config.getUpdateIntervalSeconds());
        assertEquals(6, config.getDataRetentionHours());
        assertEquals(0.15, config.getRateLimitRequestsPerSecond(), 0.001);
        assertEquals(5.0, config.getRateLimitBurst(), 0.001);
    }

    @Test
    void headlineConfig_shouldDefaultToEnabled() {
        var config = new HeadlineConfig();

        assertTrue(config.isEnabled());
    }

    @Test
    void userConfig_shouldDefaultToGerman() {
        var config = new UserConfig();
        assertEquals("de", config.getLanguage());
    }

    @Test
    void globalConfig_shouldProvideIndependentSubconfigs() {
        var config = new GlobalConfig();

        // Mutating agent config should not affect other configs
        config.getAgent().setEditorialModel("REASONING_POWER");
        assertEquals("REASONING_POWER", config.getAgent().getEditorialModel());
        assertNotNull(config.getReddit());
    }
}
