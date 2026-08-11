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
        double anchor = AgentConfig.weightsGbFor("gemma4:e4b");
        assertEquals(8192, AgentConfig.contextTokensFor(0, anchor));   // unprobeable → floor
        assertEquals(8192, AgentConfig.contextTokensFor(16 * gb, anchor));
        assertEquals(16384, AgentConfig.contextTokensFor(32 * gb, anchor));
        assertEquals(24576, AgentConfig.contextTokensFor(64 * gb, anchor));
        assertEquals(24576, AgentConfig.contextTokensFor(128 * gb, anchor));
    }

    @Test
    void agentConfig_shouldShrinkTheWindowForAHeavierModelOnTheSameMachine() {
        // The point of the second axis: same machine, bigger weights, smaller
        // window. The old RAM-only ladder handed a 21 GB model the same
        // window as a 9 GB one and drove the machine into swap (2026-08-10).
        long gb = 1L << 30;
        int light = AgentConfig.contextTokensFor(32 * gb, AgentConfig.weightsGbFor("gemma4:e2b"));
        int heavy = AgentConfig.contextTokensFor(32 * gb, AgentConfig.weightsGbFor("gemma4:26b"));
        assertTrue(heavy < light, "a heavier model must not get the lighter model's window");
        assertEquals(8192, heavy);
    }

    @Test
    void agentConfig_shouldSizeTheTopRungAgainstItsOwnWeightsNotTheAnchors() {
        // The top rung weighs 23 GB, not the anchor's 8.8. The tag has to be
        // KNOWN here, or the fallback silently prices a 23 GB resident set as a
        // 9 GB one. Pinned because this table and the launcher's catalog drift
        // without anything failing - the machine just starts swapping.
        long gb = 1L << 30;
        assertEquals(23.0, AgentConfig.weightsGbFor("nemotron-3.5-lightning:30b"));
        assertEquals(23.0, AgentConfig.weightsGbFor("nemotron-3.5-lightning:30b-mlx"));

        // 32 GB is the LOWEST machine the launcher offers this rung on, and it
        // is where the mispricing bites: weighed correctly there is no headroom
        // left at all and the window drops to the floor, while the anchor's
        // weight would have handed out 16384 and driven the machine into swap.
        double top = AgentConfig.weightsGbFor("nemotron-3.5-lightning:30b");
        double anchor = AgentConfig.weightsGbFor("gemma4:e4b");
        assertEquals(8192, AgentConfig.contextTokensFor(32 * gb, top));
        assertEquals(16384, AgentConfig.contextTokensFor(32 * gb, anchor));

        // On the 48 GB machine both land on the roomiest window - the weight
        // axis only separates them further down the ladder. Stated so nobody
        // "fixes" the table against a machine where it cannot show a difference.
        assertEquals(24576, AgentConfig.contextTokensFor(48 * gb, top));
        assertEquals(24576, AgentConfig.contextTokensFor(48 * gb, anchor));
    }

    @Test
    void agentConfig_shouldAcceptTheTopRungAsADeployedFamily() {
        // The launcher offers and installs this tag; if the runtime refuses the
        // family it falls back to a model that is no longer on disk.
        assertTrue(Model.isDeployedFamily("nemotron-3.5-lightning:30b"));
        assertTrue(Model.isDeployedFamily("nemotron-3.5-lightning:30b-mlx"));

        var config = new AgentConfig();
        config.setModelTag("nemotron-3.5-lightning:30b-mlx");
        assertEquals("nemotron-3.5-lightning:30b-mlx", config.resolveModelTag());
    }

    @Test
    void agentConfig_shouldGiveAnUnknownTagTheAnchorWeightNeverZero() {
        // Guessing small is the one error that reintroduces the swap collapse.
        assertEquals(AgentConfig.weightsGbFor("gemma4:e4b"),
                AgentConfig.weightsGbFor("something:unknown"));
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
