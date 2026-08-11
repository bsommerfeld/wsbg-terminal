package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the hardware→model ladder ({@link ModelCatalog}) and the config.toml
 * model-tag line-scan ({@link ModelSelection}) — the backend contract the
 * future model-choice UI builds on.
 */
class ModelSelectionTest {

    // ------------------------------------------------------------------
    // ModelCatalog: recommendation ladder
    // ------------------------------------------------------------------

    @Test
    void recommendsByTotalRam() {
        assertEquals(ModelCatalog.E2B, ModelCatalog.recommend(8));
        assertEquals(ModelCatalog.E2B, ModelCatalog.recommend(12));
        assertEquals(ModelCatalog.E4B, ModelCatalog.recommend(16));
        assertEquals(ModelCatalog.E4B, ModelCatalog.recommend(24));
        assertEquals(ModelCatalog.E4B, ModelCatalog.recommend(32));
        assertEquals(ModelCatalog.B26, ModelCatalog.recommend(40));
        assertEquals(ModelCatalog.NEMOTRON_LIGHTNING, ModelCatalog.recommend(48));
        // The ladder tops out at Nemotron - a bigger machine buys no bigger tier.
        assertEquals(ModelCatalog.NEMOTRON_LIGHTNING, ModelCatalog.recommend(64));
        assertEquals(ModelCatalog.NEMOTRON_LIGHTNING, ModelCatalog.recommend(128));
    }

    @Test
    void unprobeableMachineGetsTheSafeDefaultNotTheFloor() {
        assertEquals(ModelCatalog.DEFAULT, ModelCatalog.recommend(0));
        assertEquals(ModelCatalog.DEFAULT, ModelCatalog.recommend(-1));
    }

    @Test
    void fitVerdictsGradePerTier() {
        assertEquals(ModelCatalog.Fit.COMFORTABLE, ModelCatalog.E4B.fitFor(16));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.E4B.fitFor(12));
        assertEquals(ModelCatalog.Fit.TOO_LARGE, ModelCatalog.E4B.fitFor(8));
        assertEquals(ModelCatalog.Fit.TOO_LARGE, ModelCatalog.B26.fitFor(24));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.B26.fitFor(32));
        assertEquals(ModelCatalog.Fit.COMFORTABLE, ModelCatalog.B26.fitFor(40));
    }

    @Test
    void appleSiliconGetsTheMlxTwin() {
        assertEquals("gemma4:e4b", ModelCatalog.E4B.tagFor(false));
        assertEquals("gemma4:e4b-mlx", ModelCatalog.E4B.tagFor(true));
        assertEquals("gemma4:26b-mlx", ModelCatalog.B26.tagFor(true));
        // The MLX suffix is a platform rule, not a gemma4 rule - it holds
        // across families, so the top tier carries it too.
        assertEquals("nemotron-3.5-lightning:30b",
                ModelCatalog.NEMOTRON_LIGHTNING.tagFor(false));
        assertEquals("nemotron-3.5-lightning:30b-mlx",
                ModelCatalog.NEMOTRON_LIGHTNING.tagFor(true));
    }

    // ------------------------------------------------------------------
    // ModelSelection: config line-scan + resolution
    // ------------------------------------------------------------------

    @Test
    void missingConfigMeansNoUserChoice(@TempDir Path dir) {
        assertEquals("", ModelSelection.configuredModelTag(dir));
    }

    @Test
    void readsConfiguredTagFromConfigToml(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:e2b-mlx\"");
        assertEquals("gemma4:e2b-mlx", ModelSelection.configuredModelTag(dir));
    }

    @Test
    void acceptsTheBareKeyFormToo(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "model-tag = \"gemma4:26b\"");
        assertEquals("gemma4:26b", ModelSelection.configuredModelTag(dir));
    }

    @Test
    void rejectsForeignFamilyAndBlankValues(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.model-tag = \"llama3:8b\"");
        assertEquals("", ModelSelection.configuredModelTag(dir));

        writeConfig(dir, "[agent]", "agent.model-tag = \"\"");
        assertEquals("", ModelSelection.configuredModelTag(dir));
    }

    @Test
    void honorsAnySiblingTierNotJustTheDefault(@TempDir Path dir) throws IOException {
        // The gate is family-level, not tag-level: every tier of a deployed
        // family passes, not only the anchor the default happens to name.
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:26b-mlx\"");
        assertEquals("gemma4:26b-mlx", ModelSelection.configuredModelTag(dir));
    }

    @Test
    void resolveWithoutUserChoiceStaysOnTheDefaultTier(@TempDir Path dir) {
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));
        // The recommendation is advisory only — no silent TIER switch, ever.
        // The default tier is platform-suffixed (MLX standard on Apple Silicon),
        // so assert the tier, not one concrete tag.
        String expected = ModelCatalog.DEFAULT.tagFor(HardwareProbe.probe().isAppleSilicon());
        assertEquals(expected, result.effectiveTag());
        assertTrue(result.effectiveTag().startsWith("gemma4:e4b"));
        assertTrue(ModelCatalog.isDeployedFamily(result.recommendedTag()));
        assertTrue(Files.exists(dir.resolve(ModelSelection.RECOMMENDATION_FILE)));
    }

    @Test
    void resolveHonorsTheUsersConfiguredTag(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:e2b\"");
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));
        assertEquals("gemma4:e2b", result.effectiveTag());
        assertTrue(result.userChosen());
    }

    @Test
    void recommendationFileCarriesEveryTier(@TempDir Path dir) throws IOException {
        ModelSelection.resolve(dir, new SessionLog(dir));
        String json = Files.readString(dir.resolve(ModelSelection.RECOMMENDATION_FILE));
        for (ModelCatalog tier : ModelCatalog.values()) {
            assertTrue(json.contains(tier.tagFor(false)) || json.contains(tier.tagFor(true)),
                    "missing tier in recommendation file: " + tier);
        }
        assertTrue(json.contains("\"recommendedTag\""));
        assertTrue(json.contains("\"fit\""));
        assertTrue(json.contains("\"quality\""));
        assertTrue(json.contains("\"speed\""));
        // The name travels with the tier - a UI reading this file must never
        // have to reconstruct a label from the package coordinate.
        for (ModelCatalog tier : ModelCatalog.values()) {
            assertTrue(json.contains(tier.displayName()),
                    "missing display name in recommendation file: " + tier);
        }
    }

    @Test
    void everyTierCarriesItsOwnName() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ModelCatalog tier : ModelCatalog.values()) {
            String name = tier.displayName();
            assertTrue(name != null && !name.isBlank(), "unnamed tier: " + tier);
            // A name is a label, never the package coordinate.
            assertTrue(!name.contains(":"), "name must not be the tag: " + tier);
            assertTrue(names.add(name), "duplicate tier name: " + name);
        }
    }

    // ------------------------------------------------------------------
    // Quality/speed scales: the non-technical parameter translation
    // ------------------------------------------------------------------

    @Test
    void qualityClimbsTheLadderMonotonically() {
        ModelCatalog[] tiers = ModelCatalog.values();
        for (int i = 1; i < tiers.length; i++) {
            assertTrue(tiers[i].quality() > tiers[i - 1].quality(),
                    "quality must climb with the tier: " + tiers[i]);
        }
    }

    @Test
    void speedFollowsActiveParamsNotSize() {
        // The non-obvious fact the scale exists to convey: the BIGGEST tier
        // (30B MoE, 3B active) outruns the 26B below it (4B active), because
        // speed follows active parameters, not parameter count.
        assertTrue(ModelCatalog.NEMOTRON_LIGHTNING.speed() > ModelCatalog.B26.speed());
        assertTrue(ModelCatalog.E2B.speed() > ModelCatalog.E4B.speed());
        // No rung may be slower than the 26B: a tier that costs more machine
        // AND more waiting than its neighbours has no place on the ladder
        // (the rule that struck the dense 12B and the 35B on 2026-08-11).
        for (ModelCatalog tier : ModelCatalog.values()) {
            assertTrue(tier.speed() >= ModelCatalog.B26.speed(),
                    "no tier may be slower than the 26B: " + tier);
        }
    }

    // ------------------------------------------------------------------
    // ModelConfigWriter: persisting the UI choice
    // ------------------------------------------------------------------

    @Test
    void writerReplacesAnExistingKeyInPlace(@TempDir Path dir) throws IOException {
        writeConfig(dir, "# comment stays", "[agent]",
                "agent.model-tag = \"\"", "agent.identity-desk = true");
        assertTrue(ModelConfigWriter.write(dir, "gemma4:26b-mlx", new SessionLog(dir)));
        String config = Files.readString(dir.resolve("config.toml"));
        assertTrue(config.contains("agent.model-tag = \"gemma4:26b-mlx\""));
        assertTrue(config.contains("# comment stays"));
        assertTrue(config.contains("agent.identity-desk = true"));
        assertEquals("gemma4:26b-mlx", ModelSelection.configuredModelTag(dir));
    }

    @Test
    void writerInsertsUnderTheAgentSectionWhenTheKeyIsMissing(@TempDir Path dir)
            throws IOException {
        writeConfig(dir, "[agent]", "agent.identity-desk = true", "", "[user]",
                "language = \"de\"");
        assertTrue(ModelConfigWriter.write(dir, "gemma4:e2b", new SessionLog(dir)));
        assertEquals("gemma4:e2b", ModelSelection.configuredModelTag(dir));
        String config = Files.readString(dir.resolve("config.toml"));
        // Must land inside [agent], never under a later section.
        assertTrue(config.indexOf("agent.model-tag") < config.indexOf("[user]"));
    }

    @Test
    void writerCreatesAMinimalConfigWhenNoneExists(@TempDir Path dir) {
        assertTrue(ModelConfigWriter.write(dir, "gemma4:e4b-mlx", new SessionLog(dir)));
        assertEquals("gemma4:e4b-mlx", ModelSelection.configuredModelTag(dir));
    }

    private static void writeConfig(Path dir, String... lines) throws IOException {
        Files.writeString(dir.resolve("config.toml"),
                String.join(System.lineSeparator(), lines));
    }
}
