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
    void agentConfig_shouldDefaultToPowerModeOff() {
        var config = new AgentConfig();
        assertFalse(config.isPowerMode());
    }

    @Test
    void agentConfig_shouldSupportPowerModeToggle() {
        var config = new AgentConfig();
        config.setPowerMode(true);
        assertTrue(config.isPowerMode());

        config.setPowerMode(false);
        assertFalse(config.isPowerMode());
    }

    @Test
    void redditConfig_shouldHaveReasonableDefaults() {
        var config = new RedditConfig();

        assertEquals(List.of("wallstreetbetsGER"), config.getSubreddits());
        assertEquals(60, config.getUpdateIntervalSeconds());
        assertEquals(6, config.getDataRetentionHours());
        assertEquals(10.0, config.getSignificanceThreshold(), 0.001);
        assertEquals(60, config.getInvestigationTtlMinutes());
        assertEquals(0.55, config.getSimilarityThreshold(), 0.001);
    }

    @Test
    void headlineConfig_shouldDefaultToEnabledAndShowAll() {
        var config = new HeadlineConfig();

        assertTrue(config.isEnabled());
        assertTrue(config.isShowAll());
        assertNotNull(config.getTopics());
        assertTrue(config.getTopics().isEmpty());
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
        config.getAgent().setPowerMode(true);
        assertTrue(config.getAgent().isPowerMode());
        assertNotNull(config.getReddit());
    }
}
