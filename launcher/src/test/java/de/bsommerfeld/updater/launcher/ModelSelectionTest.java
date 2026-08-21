package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void recommendedIsAlwaysMinPlusEight() {
        // The ONE RAM-bar rule (user decision 2026-08-13): min is the honest
        // floor, and "comfortable" begins exactly 8 GB above it — for every
        // tier, no hand-set exceptions. A hand-tuned recommended value is how
        // Nemotron ended up "comfortable" on the 48 GB machine it choked.
        for (ModelCatalog tier : ModelCatalog.values()) {
            assertEquals(tier.minRamGb() + 8, tier.recommendedRamGb(),
                    "recommended must be min+8: " + tier);
        }
    }

    @Test
    void recommendsByTotalRam() {
        // Below every comfortable bar the floor tier holds (min+8 puts even
        // the 3B's comfort bar at 14).
        assertEquals(ModelCatalog.GRANITE_3B, ModelCatalog.recommend(8));
        assertEquals(ModelCatalog.GRANITE_3B, ModelCatalog.recommend(12));
        assertEquals(ModelCatalog.GRANITE_3B, ModelCatalog.recommend(14));
        assertEquals(ModelCatalog.E2B, ModelCatalog.recommend(16));
        assertEquals(ModelCatalog.GRANITE_8B, ModelCatalog.recommend(18));
        assertEquals(ModelCatalog.E4B, ModelCatalog.recommend(20));
        assertEquals(ModelCatalog.E4B, ModelCatalog.recommend(24));
        assertEquals(ModelCatalog.E4B, ModelCatalog.recommend(32));
        assertEquals(ModelCatalog.B26, ModelCatalog.recommend(40));
        assertEquals(ModelCatalog.B26, ModelCatalog.recommend(48));
        // The ladder tops out at Nemotron on machines that actually carry it
        // (min 48 → comfortable from 56) - the 35B sits BEFORE it in the
        // ladder, so even where both grade comfortable the recommendation
        // stays Nemotron.
        assertEquals(ModelCatalog.NEMOTRON_LIGHTNING, ModelCatalog.recommend(56));
        assertEquals(ModelCatalog.NEMOTRON_LIGHTNING, ModelCatalog.recommend(64));
        assertEquals(ModelCatalog.NEMOTRON_LIGHTNING, ModelCatalog.recommend(128));
    }

    @Test
    void fortyEightGbMustNeverGetTheBigRungsRecommended() {
        // The exact regression measured 2026-08-13 on a 48 GB Apple-Silicon
        // machine: Nemotron active meant 46 of 48 GB in use and a visibly
        // slow machine, while the 26B sat at 42 of 48 GB and ran fine. The
        // old bars (32/48) made recommend(48) hand out Nemotron as the best
        // choice; that must stay dead. Same guarantee as the 35B's bars.
        assertNotEquals(ModelCatalog.NEMOTRON_LIGHTNING, ModelCatalog.recommend(48));
        assertNotEquals(ModelCatalog.QWEN_35B, ModelCatalog.recommend(48));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.NEMOTRON_LIGHTNING.fitFor(48));
        assertEquals(ModelCatalog.Fit.COMFORTABLE, ModelCatalog.NEMOTRON_LIGHTNING.fitFor(64));
    }

    @Test
    void unprobeableMachineGetsTheSafeDefaultNotTheFloor() {
        assertEquals(ModelCatalog.DEFAULT, ModelCatalog.recommend(0));
        assertEquals(ModelCatalog.DEFAULT, ModelCatalog.recommend(-1));
    }

    @Test
    void fitVerdictsGradePerTier() {
        assertEquals(ModelCatalog.Fit.COMFORTABLE, ModelCatalog.E4B.fitFor(20));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.E4B.fitFor(16));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.E4B.fitFor(12));
        assertEquals(ModelCatalog.Fit.TOO_LARGE, ModelCatalog.E4B.fitFor(8));
        assertEquals(ModelCatalog.Fit.TOO_LARGE, ModelCatalog.B26.fitFor(24));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.B26.fitFor(32));
        assertEquals(ModelCatalog.Fit.COMFORTABLE, ModelCatalog.B26.fitFor(40));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.GRANITE_3B.fitFor(8));
        assertEquals(ModelCatalog.Fit.COMFORTABLE, ModelCatalog.GRANITE_3B.fitFor(14));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.GRANITE_8B.fitFor(12));
    }

    @Test
    void qwenRamBarsEncodeTheMeasuredSwapCollapse() {
        // 48 GB is exactly the machine the 2026-08-11 measurement collapsed on
        // (~78 → ~18 tok/s sustained as the 21 GB resident set hit swap). The
        // rung is re-admitted, but 48 GB must grade TIGHT — never comfortable.
        assertEquals(ModelCatalog.Fit.TOO_LARGE, ModelCatalog.QWEN_35B.fitFor(40));
        assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.QWEN_35B.fitFor(48));
        assertEquals(ModelCatalog.Fit.COMFORTABLE, ModelCatalog.QWEN_35B.fitFor(56));
        assertEquals(ModelCatalog.Fit.COMFORTABLE, ModelCatalog.QWEN_35B.fitFor(64));
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
    void externalEndpointSkipsTheModelChoiceEntirely(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.endpoint-mode = \"remote\"");
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));

        assertFalse(result.managedAi());
        assertEquals("", result.effectiveTag(), "nothing is installed");
        assertTrue(result.userChosen(), "there is no choice left to ask about");
        // The recommendation file must NOT be refreshed for an install that
        // will not happen — a later UI would render it as advice.
        assertFalse(Files.exists(dir.resolve(ModelSelection.RECOMMENDATION_FILE)));
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
    void aWrittenBaseTagIsNeverReSuffixedToMlx(@TempDir Path dir) throws IOException {
        // The core of the "without MLX" lever: the choice screen writes the
        // BASE tag, and from there the tag must travel VERBATIM — config scan,
        // resolve, WSBG_REASONING_MODEL. The MLX suffix is only ever applied
        // to the DEFAULT tier when no user choice exists; a configured tag
        // that gets silently "completed" to -mlx would undo the lever exactly
        // where the user cannot see it. Runs on real hardware, so on an
        // Apple-Silicon machine this proves the platform default does NOT
        // override the choice (elsewhere it is trivially true).
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:e4b\"");
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir));
        assertEquals("gemma4:e4b", result.effectiveTag());
        assertTrue(result.userChosen());

        writeConfig(dir, "[agent]", "agent.model-tag = \"nemotron-3.5-lightning:30b\"");
        assertEquals("nemotron-3.5-lightning:30b",
                ModelSelection.resolve(dir, new SessionLog(dir)).effectiveTag());
    }

    @Test
    void installModelRunTakesATagFromANewerCatalogAtFaceValue(@TempDir Path dir) throws IOException {
        // The terminal's model picker and this launcher's catalog are separate
        // copies that CAN drift apart (the terminal updated, the staged
        // launcher jar did not). Measured 2026-08-21: a tag the terminal had
        // just written read as an unknown family here, the choice collapsed to
        // "nothing on record", and the first-run screen came back offering the
        // stale tier list instead of installing what was picked.
        writeConfig(dir, "[agent]", "agent.model-tag = \"tomorrow-model:9b\"");

        assertEquals("", ModelSelection.configuredModelTag(dir),
                "the ordinary start still gates hand-edited nonsense");

        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir), true);
        assertEquals("tomorrow-model:9b", result.effectiveTag());
        assertTrue(result.userChosen(), "a choice IS on record — never re-ask");
    }

    @Test
    void installModelRunLeavesAKnownTagExactlyAsItIs(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.model-tag = \"granite4.1:8b\"");
        ModelSelection.Result result = ModelSelection.resolve(dir, new SessionLog(dir), true);
        assertEquals("granite4.1:8b", result.effectiveTag());
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
    void qualityScaleStaysHonestAcrossFamilies() {
        // The ladder is ordered by the machine a tier needs, and quality is
        // deliberately NOT monotonic along it: the dense Granite rungs beat
        // the Gemma MoE beside them on quality per GB of machine.
        assertTrue(ModelCatalog.GRANITE_3B.quality() > ModelCatalog.E2B.quality(),
                "granite 3b outgrades e2b despite the smaller RAM bar");
        assertTrue(ModelCatalog.GRANITE_8B.quality() > ModelCatalog.E4B.quality(),
                "granite 8b (IFEval 87.1, BFCL 68.27) outgrades e4b");
        // Within a family the bigger sibling must still win.
        assertTrue(ModelCatalog.E4B.quality() > ModelCatalog.E2B.quality());
        assertTrue(ModelCatalog.B26.quality() > ModelCatalog.E4B.quality());
        assertTrue(ModelCatalog.GRANITE_8B.quality() > ModelCatalog.GRANITE_3B.quality());
        // The top rungs carry the ceiling.
        assertEquals(10, ModelCatalog.QWEN_35B.quality());
        assertEquals(10, ModelCatalog.NEMOTRON_LIGHTNING.quality());
    }

    @Test
    void speedScoresAreMeasuredNotDerivedFromActiveParams() {
        // Within the small MoE half, speed still follows active params.
        assertTrue(ModelCatalog.E2B.speed() > ModelCatalog.E4B.speed());
        // The dense Granite rungs have few enough active params that the
        // missing expert-skipping barely registers — the scores stay high.
        assertTrue(ModelCatalog.GRANITE_3B.speed() >= ModelCatalog.E4B.speed());
        assertTrue(ModelCatalog.GRANITE_8B.speed() >= ModelCatalog.E4B.speed());
        // The two big rungs carry MEASURED sustained values, not the "only 3B
        // active" derivation that once put Nemotron at 9: measured 2026-08-13
        // it ran SLOWER than the 26B on the machine class it was recommended
        // for (46 of 48 GB resident), and the 35B collapsed under swap on
        // 2026-08-11 (~78 → ~18 tok/s). Do not raise either without a new
        // measurement; the catalog's warning comments hold the details.
        assertEquals(5, ModelCatalog.QWEN_35B.speed());
        assertEquals(6, ModelCatalog.NEMOTRON_LIGHTNING.speed());
        assertTrue(ModelCatalog.B26.speed() > ModelCatalog.NEMOTRON_LIGHTNING.speed(),
                "the 26B held flat where Nemotron dragged — the scale must say so");
        assertTrue(ModelCatalog.NEMOTRON_LIGHTNING.speed() > ModelCatalog.QWEN_35B.speed());
        // Every rung BELOW the two measured-down big ones keeps the old rule.
        for (ModelCatalog tier : ModelCatalog.values()) {
            if (tier == ModelCatalog.QWEN_35B || tier == ModelCatalog.NEMOTRON_LIGHTNING) continue;
            assertTrue(tier.speed() >= ModelCatalog.B26.speed(),
                    "no small rung may be slower than the 26B: " + tier);
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
