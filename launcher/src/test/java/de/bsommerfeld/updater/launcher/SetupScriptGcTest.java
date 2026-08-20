package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the REAL model-GC / safety-net functions from {@code .script/setup.sh}
 * (extracted verbatim, never a copy) against fixtures: the desired-only keep
 * decision, the completeness probe behind "repair the configured model", and
 * the absolute path boundary that protects the user's private
 * {@code ~/.ollama} store.
 *
 * <p>Honesty about scope: this pins the decision PRIMITIVES the script is
 * built from. The surrounding orchestration (the pull loop, the pass ordering,
 * everything needing a live {@code ollama} binary) is not unit-testable here,
 * and {@code setup.ps1} cannot be executed at all on this side — it mirrors
 * these functions by review, guarded by the shared output-line contract in
 * {@link EnvironmentSetupTest}.
 */
class SetupScriptGcTest {

    private static Path scriptPath;

    @BeforeAll
    static void locateScript() {
        // Surefire runs with the module dir as cwd; the scripts live one up.
        scriptPath = Path.of("..", ".script", "setup.sh").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(scriptPath), "setup.sh not found");
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));
    }

    // ------------------------------------------------------------------
    // keep_model: the GC keep decision
    // ------------------------------------------------------------------

    @Test
    void onlyTheDesiredTagSurvivesTheCleanup() throws Exception {
        // The reconcile contract: exactly the configured (desired) tag stays,
        // everything else in the isolated store is an Altlast — case matters
        // not, siblings and foreign families alike go.
        String out = bash(
                fn("keep_model"),
                "DESIRED_MODELS=(\"gemma4:e4b-mlx\")",
                verdict("gemma4:e4b-mlx"),       // the configured tag itself
                verdict("GEMMA4:E4B-MLX"),       // case-insensitive match
                verdict("gemma4:26b"),           // sibling size — not desired
                verdict("embeddinggemma:latest"),// the classic Altlast
                verdict("llama3:8b"));           // foreign family
        assertEquals(List.of(
                "KEEP gemma4:e4b-mlx",
                "KEEP GEMMA4:E4B-MLX",
                "DROP gemma4:26b",
                "DROP embeddinggemma:latest",
                "DROP llama3:8b"), out.lines().toList());
    }

    // ------------------------------------------------------------------
    // config_endpoint_mode: the standalone-run fallback
    // ------------------------------------------------------------------

    @Test
    void readsTheEndpointModeFromConfigToml(@TempDir Path dir) throws Exception {
        // Without the launcher there is no WSBG_ENDPOINT_MODE, so the script
        // reads the key itself. Defaulting to "managed" instead would hand a
        // remote user the multi-GB install they opted out of.
        assertEquals("remote", mode(dir, "[agent]", "agent.endpoint-mode = \"remote\""));
        assertEquals("remote", mode(dir, "[agent]", "endpoint-mode = \"remote\""));
        assertEquals("managed", mode(dir, "[agent]", "agent.endpoint-mode = \"managed\""));
        assertEquals("", mode(dir, "[agent]", "agent.model-tag = \"gemma4:e4b\""));
    }

    @Test
    void endpointModelIsNotMistakenForEndpointMode(@TempDir Path dir) throws Exception {
        // "endpoint-model" starts with "endpoint-mode" — a prefix-only match
        // would read the model tag as the mode and silently install. The '='
        // in the pattern is what separates them. (Same trap as the launcher's
        // Java line scan, pinned on both sides.)
        assertEquals("remote", mode(dir, "[agent]",
                "agent.endpoint-model = \"qwen3:32b\"",
                "agent.endpoint-mode = \"remote\""));
    }

    /** Runs the script's real config_endpoint_mode against a written config.toml. */
    private static String mode(Path dir, String... configLines) throws Exception {
        Path cfg = dir.resolve("config.toml");
        Files.write(cfg, List.of(configLines));
        return bash(fn("config_endpoint_mode"),
                "CONFIG_FILE='" + cfg + "'",
                "config_endpoint_mode").strip();
    }

    // ------------------------------------------------------------------
    // safe_store_rm: the absolute boundary
    // ------------------------------------------------------------------

    @Test
    void deletesOnlyFilesProvablyInsideTheStore(@TempDir Path dir) throws Exception {
        Path store = Files.createDirectories(dir.resolve("appdata/ollama/models"));
        Path inside = Files.writeString(store.resolve("blobs-victim"), "x");
        // A file OUTSIDE the store — stands in for the user's ~/.ollama.
        Path outside = Files.writeString(dir.resolve("users-private-model"), "x");

        // The root is canonicalized exactly as the script's call site does
        // (STORE_ROOT=$(cd "$AI_MODELS" && pwd -P)) — on macOS the temp dir
        // itself sits behind the /var -> /private/var symlink.
        String script = fn("safe_store_rm") + stubWarn()
                + canonRoot(store)
                + "safe_store_rm '" + inside + "' \"$STORE_ROOT\" && echo RM_INSIDE_OK\n"
                + "safe_store_rm '" + outside + "' \"$STORE_ROOT\" || echo RM_OUTSIDE_REFUSED\n";
        String out = bash(script);

        assertTrue(out.contains("RM_INSIDE_OK"));
        assertFalse(Files.exists(inside), "inside-store file must be deleted");
        assertTrue(out.contains("RM_OUTSIDE_REFUSED"));
        assertTrue(Files.exists(outside),
                "a file outside the store must NEVER be deleted");
    }

    @Test
    void symlinkEscapeIsResolvedAndRefused(@TempDir Path dir) throws Exception {
        // A symlinked directory INSIDE the store pointing OUTSIDE: the naive
        // string prefix would pass, only canonicalization catches it. This is
        // exactly the ~/.ollama escape the boundary exists to close.
        Path store = Files.createDirectories(dir.resolve("appdata/ollama/models"));
        Path privateDir = Files.createDirectories(dir.resolve("private-ollama"));
        Path privateModel = Files.writeString(privateDir.resolve("model.bin"), "x");
        Path link = store.resolve("blobs");
        Files.createSymbolicLink(link, privateDir);

        String out = bash(fn("safe_store_rm") + stubWarn()
                + canonRoot(store)
                + "safe_store_rm '" + link.resolve("model.bin") + "' \"$STORE_ROOT\""
                + " || echo REFUSED\n");

        assertTrue(out.contains("REFUSED"));
        assertTrue(Files.exists(privateModel),
                "a symlink escape must never reach the user's files");
    }

    @Test
    void emptyRootRefusesInsteadOfDeleting(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("f"), "x");
        String out = bash(fn("safe_store_rm") + stubWarn()
                + "safe_store_rm '" + f + "' '' || echo REFUSED\n");
        assertTrue(out.contains("REFUSED"));
        assertTrue(Files.exists(f), "an empty root must refuse, never delete");
    }

    // ------------------------------------------------------------------
    // model_complete: the primitive behind "repair the configured model"
    // ------------------------------------------------------------------

    @Test
    void modelCompleteDemandsManifestAndEveryBlob(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("models");
        String digest = "a".repeat(64);
        Path manifest = store.resolve("manifests/registry.ollama.ai/library/gemma4/e4b");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{\"layers\":[{\"digest\":\"sha256:" + digest + "\"}]}");
        Path blobs = Files.createDirectories(store.resolve("blobs"));

        String probe = fn("manifest_file") + fn("model_complete")
                + "AI_MODELS='" + store + "'\n"
                + "model_complete 'gemma4:e4b' && echo COMPLETE || echo INCOMPLETE\n"
                + "model_complete 'gemma4:26b' && echo COMPLETE || echo INCOMPLETE\n";

        // Blob missing: the entry LOOKS installed ('ollama list' would show
        // it) but cannot load — the state the repair path exists for.
        assertEquals(List.of("INCOMPLETE", "INCOMPLETE"), bash(probe).lines().toList());

        Files.writeString(blobs.resolve("sha256-" + digest), "weights");
        assertEquals(List.of("COMPLETE", "INCOMPLETE"), bash(probe).lines().toList());
    }

    // ------------------------------------------------------------------
    // plumbing: extract the REAL function bodies and run them
    // ------------------------------------------------------------------

    /** The verbatim body of a top-level {@code name() { ... }} in setup.sh. */
    private static String fn(String name) throws IOException {
        List<String> lines = Files.readAllLines(scriptPath);
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String line : lines) {
            if (!in && line.startsWith(name + "() {")) in = true;
            if (in) {
                sb.append(line).append('\n');
                if (line.equals("}")) break;
            }
        }
        assertTrue(sb.length() > 0, "function not found in setup.sh: " + name);
        return sb.toString();
    }

    private static String verdict(String tag) {
        return "keep_model '" + tag + "' && echo 'KEEP " + tag + "' || echo 'DROP " + tag + "'";
    }

    private static String stubWarn() {
        return "warn() { echo \"WARN: $1\"; }\n";
    }

    /** Canonicalizes the store root the way the script's call site does. */
    private static String canonRoot(Path store) {
        return "STORE_ROOT=$(cd '" + store + "' && pwd -P)\n";
    }

    private static String bash(String... parts) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", String.join("\n", parts))
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "bash probe timed out");
        return out;
    }
}
