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
    void agentConfig_shouldDefaultContextTokensTo8192() {
        assertEquals(8192, new AgentConfig().getContextTokens());
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
