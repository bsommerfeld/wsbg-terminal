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
        assertEquals(ModelCatalog.B12, ModelCatalog.recommend(24));
        assertEquals(ModelCatalog.B12, ModelCatalog.recommend(32));
        assertEquals(ModelCatalog.B26, ModelCatalog.recommend(40));
        assertEquals(ModelCatalog.B31, ModelCatalog.recommend(48));
        assertEquals(ModelCatalog.B31, ModelCatalog.recommend(128));
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
        assertEquals(ModelCatalog.Fit.TOO_LARGE, ModelCatalog.B31.fitFor(24));
    }

    @Test
    void appleSiliconGetsTheMlxTwin() {
        assertEquals("gemma4:e4b", ModelCatalog.E4B.tagFor(false));
        assertEquals("gemma4:e4b-mlx", ModelCatalog.E4B.tagFor(true));
        assertEquals("gemma4:31b-mlx", ModelCatalog.B31.tagFor(true));
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
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:12b-mlx\"");
        assertEquals("gemma4:12b-mlx", ModelSelection.configuredModelTag(dir));
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
    void resolveWithoutUserChoiceStaysOnTheDefaultTier(@TempDir Path dir) {
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));
        // The recommendation is advisory only — no silent TIER switch, ever.
        // The default tier is platform-suffixed (MLX standard on Apple Silicon),
        // so assert the tier, not one concrete tag.
        String expected = ModelCatalog.DEFAULT.tagFor(HardwareProbe.probe().isAppleSilicon());
        assertEquals(expected, result.effectiveTag());
        assertTrue(result.effectiveTag().startsWith("gemma4:e4b"));
        assertTrue(result.recommendedTag().startsWith("gemma4:"));
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
    }

    private static void writeConfig(Path dir, String... lines) throws IOException {
        Files.writeString(dir.resolve("config.toml"),
                String.join(System.lineSeparator(), lines));
    }
}
