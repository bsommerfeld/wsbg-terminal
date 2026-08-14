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
        // The top rung's effective resident set measured ~28 GB (2026-08-13,
        // 48 GB Apple Silicon: 46 of 48 GB in use = 12 GB host reserve +
        // ~6 GB KV + ~28 GB weights under MLX's high-water allocator), not
        // the anchor's 8.8 and not the 23 the disk size once suggested. The
        // tag has to be KNOWN here, or the fallback silently prices that set
        // as a 9 GB one. Pinned because this table and the launcher's catalog
        // drift without anything failing - the machine just starts swapping.
        long gb = 1L << 30;
        assertEquals(28.0, AgentConfig.weightsGbFor("nemotron-3.5-lightning:30b"));
        assertEquals(28.0, AgentConfig.weightsGbFor("nemotron-3.5-lightning:30b-mlx"));

        double top = AgentConfig.weightsGbFor("nemotron-3.5-lightning:30b");
        double anchor = AgentConfig.weightsGbFor("gemma4:e4b");
        assertEquals(8192, AgentConfig.contextTokensFor(32 * gb, top));
        assertEquals(16384, AgentConfig.contextTokensFor(32 * gb, anchor));

        // THE fix the measurement demanded: on the 48 GB machine the old
        // 23 GB pricing still handed this rung the roomiest window, and the
        // window itself deepened the memory pressure. Weighed honestly it
        // drops to the floor there; the anchor keeps the roomy window.
        assertEquals(8192, AgentConfig.contextTokensFor(48 * gb, top));
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
    void agentConfig_shouldAcceptTheGraniteAndQwenFamilies() {
        // Mirrors the launcher's catalog: both registers must list the new
        // families, or the launcher installs a tag the runtime then refuses
        // and silently degrades to a model that may not even be on disk.
        assertTrue(Model.isDeployedFamily("granite4.1:3b"));
        assertTrue(Model.isDeployedFamily("granite4.1:8b"));
        assertTrue(Model.isDeployedFamily("qwen3.6:35b"));
        assertTrue(Model.isDeployedFamily("qwen3.6:35b-mlx"));

        var config = new AgentConfig();
        config.setModelTag("granite4.1:8b");
        assertEquals("granite4.1:8b", config.resolveModelTag());
        config.setModelTag("qwen3.6:35b-mlx");
        assertEquals("qwen3.6:35b-mlx", config.resolveModelTag());
    }

    @Test
    void agentConfig_shouldWeighTheNewRungsAgainstTheirOwnWeights() {
        // Without catalog entries the context window is sized against the
        // 8.8 GB anchor — for the ~27 GB Qwen that is exactly the mispricing
        // that produced the 2026-08-11 swap collapse (~78 → ~18 tok/s).
        assertEquals(2.1, AgentConfig.weightsGbFor("granite4.1:3b"));
        assertEquals(5.3, AgentConfig.weightsGbFor("granite4.1:8b"));
        assertEquals(27.0, AgentConfig.weightsGbFor("qwen3.6:35b"));
        assertEquals(27.0, AgentConfig.weightsGbFor("qwen3.6:35b-mlx"));

        long gb = 1L << 30;
        double qwen = AgentConfig.weightsGbFor("qwen3.6:35b");
        double anchor = AgentConfig.weightsGbFor("gemma4:e4b");
        assertEquals(8192, AgentConfig.contextTokensFor(32 * gb, qwen));
        assertEquals(16384, AgentConfig.contextTokensFor(32 * gb, anchor));
        // The tiny Granite frees the window upward on the same machine.
        assertEquals(24576, AgentConfig.contextTokensFor(32 * gb,
                AgentConfig.weightsGbFor("granite4.1:3b")));
    }

    @Test
    void agentConfig_shouldDowngradeTheWindowAutomaticallyForExpensiveModels() {
        // The user's contract, verbatim: "24k mit Nemotron klappt halt nicht.
        // Aber vielleicht 8k mit Nemotron." Same machine, expensive model →
        // the window steps DOWN on its own; light model → it opens up. The
        // point is the automatic downgrade, not one number.
        long gb = 1L << 30;
        assertEquals(8192, AgentConfig.contextTokensFor(48 * gb,
                AgentConfig.weightsGbFor("nemotron-3.5-lightning:30b-mlx")));
        assertEquals(24576, AgentConfig.contextTokensFor(48 * gb,
                AgentConfig.weightsGbFor("gemma4:e4b-mlx")));

        // Honesty note (mirrors OllamaModelFactory/ChatGateway): the MLX
        // runner — the Apple-Silicon default — ignores num_ctx; there the
        // smaller window binds through the app's OWN prompt budgeting
        // (contextTokens() feeds every brief/prompt budget), not through the
        // runner. The downgrade is real either way; the enforcer differs.
    }

    @Test
    void agentConfig_shouldNeverGiveATightMachineAGenerousWindow() {
        // Ladder-wide invariant: at a tier's own MINIMUM (= a machine where
        // the tier grades TIGHT), the window must sit on the 8k floor. A
        // TIGHT machine with a roomy window is how both measured collapses
        // happened — the window itself fed the memory pressure.
        long gb = 1L << 30;
        for (var tier : de.bsommerfeld.updater.catalog.ModelCatalog.values()) {
            int window = AgentConfig.contextTokensFor(
                    tier.minRamGb() * gb, tier.residentGb());
            assertEquals(8192, window,
                    "TIGHT machine must stay on the floor: " + tier);
        }
    }

    @Test
    void agentConfig_shouldReturnAChosenBaseTagVerbatimNeverMlxSuffixed() {
        // The runtime half of the launcher's "without MLX" lever: a configured
        // BASE tag passes resolveModelTag() untouched. The Apple-Silicon MLX
        // suffix lives ONLY in the no-choice default branch — re-suffixing a
        // chosen tag here would silently undo the user's explicit decision.
        var config = new AgentConfig();
        config.setModelTag("gemma4:e4b");
        assertEquals("gemma4:e4b", config.resolveModelTag());
        config.setModelTag("qwen3.6:35b");
        assertEquals("qwen3.6:35b", config.resolveModelTag());
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
