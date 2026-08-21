package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the model ladder ({@link ModelCatalog}) and the config.toml model-tag
 * line-scan ({@link ModelSelection}) — the contract between the terminal's
 * model picker, which writes the tag, and the launcher, which installs it.
 */
class ModelSelectionTest {

    // ------------------------------------------------------------------
    // ModelCatalog: the RAM floors
    // ------------------------------------------------------------------

    @Test
    void theRamFloorsRiseWithTheLadder() {
        // The ladder is ordered by the machine a tier needs, and the settings'
        // picker renders it in that order. Nothing grades a machine against
        // these numbers any more, but a floor that fell below its predecessor
        // would put the ladder out of order with nothing to notice it.
        int previous = 0;
        for (ModelCatalog tier : ModelCatalog.values()) {
            assertTrue(tier.minRamGb() >= previous, "floors must not fall: " + tier);
            previous = tier.minRamGb();
        }
    }

    @Test
    void theTwoBigRungsKeepTheirMeasuredFloor() {
        // 48 GB is the machine BOTH measurements broke on: the 35B collapsed
        // from ~78 to ~18 tok/s into swap (2026-08-11), and Nemotron sat at
        // 46 of 48 GB and ran visibly slow (2026-08-13) where the 26B held
        // 42 of 48 GB and ran fine. 48 is their FLOOR, not their home - and
        // it is what pins AgentConfig's context window to the 8k floor there
        // (ConfigDefaultsTest holds that half).
        assertEquals(32, ModelCatalog.B26.minRamGb());
        assertEquals(48, ModelCatalog.QWEN_35B.minRamGb());
        assertEquals(48, ModelCatalog.NEMOTRON_LIGHTNING.minRamGb());
    }

    @Test
    void appleSiliconGetsTheMlxTwin() {
        assertEquals("gemma4:e4b", ModelCatalog.E4B.tagFor(false));
        assertEquals("gemma4:e4b-mlx", ModelCatalog.E4B.tagFor(true));
        assertEquals("gemma4:26b-mlx", ModelCatalog.B26.tagFor(true));
        // The MLX suffix is a platform rule, not a gemma4 rule - it holds
        // across families, so the top tiers carry it too.
        assertEquals("qwen3.6:35b-mlx", ModelCatalog.QWEN_35B.tagFor(true));
        assertEquals("nemotron-3.5-lightning:30b",
                ModelCatalog.NEMOTRON_LIGHTNING.tagFor(false));
        assertEquals("nemotron-3.5-lightning:30b-mlx",
                ModelCatalog.NEMOTRON_LIGHTNING.tagFor(true));
    }

    @Test
    void graniteHasNoMlxTwinAndMustNeverInventOne() {
        // The registry carries NO -mlx build for granite4.1 (checked against
        // ollama.com/library/granite4.1 on 2026-08-13). Appending the suffix
        // anyway would hand `ollama pull` a tag it cannot find, and the
        // install would fail on every Apple-Silicon machine.
        assertEquals("granite4.1:3b", ModelCatalog.GRANITE_3B.tagFor(true));
        assertEquals("granite4.1:3b", ModelCatalog.GRANITE_3B.tagFor(false));
        assertEquals("granite4.1:8b", ModelCatalog.GRANITE_8B.tagFor(true));
        assertEquals("granite4.1:8b", ModelCatalog.GRANITE_8B.tagFor(false));
        // No MLX build also means no separate MLX size.
        assertEquals(ModelCatalog.GRANITE_8B.diskGbFor(false),
                ModelCatalog.GRANITE_8B.diskGbFor(true));
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
    void defaultsToTheManagedRuntime(@TempDir Path dir) throws IOException {
        assertTrue(ModelSelection.isManagedAi(dir), "no config at all");

        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:e4b\"");
        assertTrue(ModelSelection.isManagedAi(dir), "config without the key");

        writeConfig(dir, "[agent]", "agent.endpoint-mode = \"managed\"");
        assertTrue(ModelSelection.isManagedAi(dir));
    }

    @Test
    void readsTheExternalEndpointMode(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.endpoint-mode = \"remote\"");
        assertFalse(ModelSelection.isManagedAi(dir));

        writeConfig(dir, "[agent]", "endpoint-mode = \"remote\"");
        assertFalse(ModelSelection.isManagedAi(dir), "bare key form");
    }

    @Test
    void endpointModelDoesNotMasqueradeAsEndpointMode(@TempDir Path dir) throws IOException {
        // "endpoint-model" starts with "endpoint-mode". A prefix-only line scan
        // reads the model tag as the mode, decides "not remote", and hands the
        // user the multi-GB install they explicitly opted out of. The '=' is
        // what tells the two keys apart.
        writeConfig(dir, "[agent]",
                "agent.endpoint-model = \"qwen3:32b\"",
                "agent.endpoint-mode = \"remote\"");
        assertFalse(ModelSelection.isManagedAi(dir));
    }

    @Test
    void externalEndpointInstallsNothingAtAll(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.endpoint-mode = \"remote\"");
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));

        assertFalse(result.managedAi());
        assertEquals("", result.effectiveTag(), "nothing is installed");
    }

    @Test
    void honorsAnySiblingTierNotJustTheDefault(@TempDir Path dir) throws IOException {
        // The gate is family-level, not tag-level: every tier of a deployed
        // family passes, not only the anchor the default happens to name.
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:26b-mlx\"");
        assertEquals("gemma4:26b-mlx", ModelSelection.configuredModelTag(dir));
    }

    @Test
    void honorsTheNewFamiliesInBothDirections(@TempDir Path dir) throws IOException {
        // The launcher's gate and the runtime's Model.DEPLOYED_FAMILIES must
        // agree on granite4.1 and qwen3.6, or the launcher installs a tag the
        // runtime then refuses (launcher half pinned here).
        writeConfig(dir, "[agent]", "agent.model-tag = \"granite4.1:8b\"");
        assertEquals("granite4.1:8b", ModelSelection.configuredModelTag(dir));

        writeConfig(dir, "[agent]", "agent.model-tag = \"qwen3.6:35b-mlx\"");
        assertEquals("qwen3.6:35b-mlx", ModelSelection.configuredModelTag(dir));

        assertTrue(ModelCatalog.isDeployedFamily("granite4.1:3b"));
        assertTrue(ModelCatalog.isDeployedFamily("qwen3.6:35b"));
    }

    @Test
    void resolveWithoutAConfiguredTagInstallsTheDefaultTier(@TempDir Path dir) {
        // A fresh install gets ONE tier, never a machine-dependent pick: the
        // launcher asks nothing, so nobody is there to approve a swap. The
        // default is platform-suffixed (MLX standard on Apple Silicon), so
        // assert the tier, not one concrete tag.
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));
        String expected = ModelCatalog.DEFAULT.tagFor(HardwareProbe.probe().isAppleSilicon());
        assertEquals(expected, result.effectiveTag());
        assertTrue(result.effectiveTag().startsWith("gemma4:e4b"));
    }

    @Test
    void resolveHonorsTheUsersConfiguredTag(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:e2b\"");
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));
        assertEquals("gemma4:e2b", result.effectiveTag());
    }

    @Test
    void aWrittenBaseTagIsNeverReSuffixedToMlx(@TempDir Path dir) throws IOException {
        // The core of the "without MLX" lever: the settings picker can write
        // the BASE tag, and from there it must travel VERBATIM — config scan,
        // resolve, WSBG_REASONING_MODEL. The MLX suffix is only ever applied
        // to the DEFAULT tier when no tag is configured; a configured tag that
        // gets silently "completed" to -mlx would undo the lever exactly where
        // the user cannot see it. Runs on real hardware, so on an
        // Apple-Silicon machine this proves the platform default does NOT
        // override the choice (elsewhere it is trivially true).
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:e4b\"");
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));
        assertEquals("gemma4:e4b", result.effectiveTag());

        writeConfig(dir, "[agent]", "agent.model-tag = \"nemotron-3.5-lightning:30b\"");
        assertEquals("nemotron-3.5-lightning:30b",
                ModelSelection.resolve(dir, new SessionLog(dir)).effectiveTag());
    }

    @Test
    void installModelRunTakesATagFromANewerCatalogAtFaceValue(@TempDir Path dir) throws IOException {
        // The terminal's model picker and this launcher's catalog are separate
        // copies that CAN drift apart (the terminal updated, the staged
        // launcher jar did not). Measured 2026-08-21: a tag the terminal had
        // just written read as an unknown family here and the launcher quietly
        // installed the default instead - after which the terminal asks for
        // the very same restart again.
        writeConfig(dir, "[agent]", "agent.model-tag = \"tomorrow-model:9b\"");

        assertEquals("", ModelSelection.configuredModelTag(dir),
                "the ordinary start still gates hand-edited nonsense");

        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir), true);
        assertEquals("tomorrow-model:9b", result.effectiveTag());
    }

    @Test
    void installModelRunLeavesAKnownTagExactlyAsItIs(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.model-tag = \"granite4.1:8b\"");
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir), true);
        assertEquals("granite4.1:8b", result.effectiveTag());
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

    private static void writeConfig(Path dir, String... lines) throws IOException {
        Files.writeString(dir.resolve("config.toml"),
                String.join(System.lineSeparator(), lines));
    }
}
