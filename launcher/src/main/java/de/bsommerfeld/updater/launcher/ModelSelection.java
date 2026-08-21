package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Which model the setup script installs — read from {@code config.toml}, never
 * asked here.
 *
 * <p>
 * The launcher used to put a model-choice screen in front of every first
 * start. It does not any more: the picker lives in the terminal's settings,
 * where the machine is already running and the choice can be changed as often
 * as the user likes. A fresh install therefore gets
 * {@link ModelCatalog#DEFAULT} - the tier that fits the widest range of
 * machines - and the launcher's only job is to fetch whatever the config
 * names.
 *
 * <p>
 * The config read is the same tiny line-scan as {@link LauncherI18n} /
 * {@link LaunchArgs} — the launcher must stay lean and start even on a
 * half-written config.
 */
final class ModelSelection {

    /** Env var carrying the resolved tag into setup.sh / setup.ps1. */
    static final String MODEL_ENV = "WSBG_REASONING_MODEL";

    /**
     * Env var telling the setup scripts whether the AI runtime is ours to
     * install ({@code managed}) or the user's own machine somewhere else
     * ({@code remote}). The scripts read {@code config.toml} themselves as a
     * fallback for standalone runs, but the launcher always sets it - the two
     * must agree, so the launcher's answer is the one that travels.
     */
    static final String MODE_ENV = "WSBG_ENDPOINT_MODE";

    /**
     * @param effectiveTag the tag the setup script installs and the runtime uses
     *                     (empty with an external endpoint - nothing is installed)
     * @param managedAi    whether the AI runtime is ours to install. False means
     *                     the user pointed the terminal at their own server:
     *                     no binary, no model, nothing to fetch.
     */
    record Result(String effectiveTag, boolean managedAi) {
    }

    private ModelSelection() {
    }

    /** The ordinary start: an unknown {@code agent.model-tag} degrades to the default. */
    static Result resolve(Path appDir, SessionLog log) {
        return resolve(appDir, log, false);
    }

    /**
     * @param trustConfiguredTag take {@code agent.model-tag} at face value even
     *        when this catalog does not know its family. Set on the terminal's
     *        {@code --install-model} restart and ONLY there: the tag was
     *        written moments ago by the terminal's own model picker, so it is a
     *        real tier by construction - just possibly one from a NEWER catalog
     *        than this launcher jar carries. Without it an older launcher would
     *        quietly install the default instead of the tier that was picked,
     *        and the terminal would ask for the same restart again. The family
     *        gate stays in force for every other start, where the value really
     *        can be hand-edited nonsense.
     */
    static Result resolve(Path appDir, SessionLog log, boolean trustConfiguredTag) {
        // The hardware recommendation this launcher used to compute and persist
        // is gone with the choice screen it fed - drop the file rather than
        // leave stale advice lying beside a live config.
        try {
            Files.deleteIfExists(appDir.resolve("hardware-recommendation.json"));
        } catch (IOException ignored) {
        }

        if (!isManagedAi(appDir)) {
            log.log("AI runtime: external endpoint configured (agent.endpoint-mode=remote) "
                    + "- nothing to install");
            return new Result("", false);
        }

        HardwareProbe hw = HardwareProbe.probe();
        boolean mlx = hw.isAppleSilicon();

        String configured = configuredModelTag(appDir, trustConfiguredTag);
        if (trustConfiguredTag && !configured.isEmpty()
                && !ModelCatalog.isDeployedFamily(configured)) {
            log.log("Model tag " + configured + " is from a newer catalog than this launcher "
                    + "- installing it as asked (terminal requested the install)");
        }
        // No configured tag = the managed default TIER, platform-suffixed: the
        // MLX build is the standard on Apple Silicon, the base tag everywhere else.
        String effectiveTag = configured.isEmpty()
                ? ModelCatalog.DEFAULT.tagFor(mlx)
                : configured;

        // Logged, not acted on: when someone reports a slow terminal, the first
        // question is what machine it ran on.
        log.log("Hardware: " + hw.totalMemoryGb() + " GB RAM, " + hw.osName() + "/" + hw.osArch()
                + (mlx ? " (Apple Silicon — MLX builds)" : ""));
        log.log("Model: " + effectiveTag
                + (configured.isEmpty() ? " (managed default)" : " (from config.toml)"));

        return new Result(effectiveTag, true);
    }

    /**
     * Whether the AI runtime is ours to install - {@code agent.endpoint-mode}
     * from {@code config.toml}, anything but {@code remote} meaning yes.
     * Same tiny line-scan as {@link #configuredModelTag}: the launcher must
     * start even on a half-written config, and an unreadable file simply means
     * the managed default.
     */
    static boolean isManagedAi(Path appDir) {
        return !"remote".equals(configuredValue(appDir, "endpoint-mode"));
    }

    /**
     * Reads {@code agent.model-tag} from {@code config.toml}. Empty when the
     * file/key is missing or the value names a family this catalog does not
     * deploy — an unknown family must degrade to the default, never reach
     * {@code ollama pull} verbatim.
     */
    static String configuredModelTag(Path appDir) {
        return configuredModelTag(appDir, false);
    }

    /** @param trustAnyFamily skip the deployed-family gate - see {@link #resolve(Path, SessionLog, boolean)}. */
    static String configuredModelTag(Path appDir, boolean trustAnyFamily) {
        String value = configuredValue(appDir, "model-tag");
        if (trustAnyFamily) return value;
        return ModelCatalog.isDeployedFamily(value) ? value : "";
    }

    /**
     * Reads one {@code agent.*} key out of {@code config.toml}, lower-cased and
     * unquoted, or {@code ""}. jshepherd writes the fully-dotted key inside
     * {@code [agent]}; the bare form is accepted too so a hand-edited config
     * still works.
     */
    private static String configuredValue(Path appDir, String key) {
        Path configFile = appDir.resolve("config.toml");
        if (!Files.exists(configFile)) return "";
        try {
            for (String line : Files.readAllLines(configFile)) {
                String trimmed = line.strip();
                String rest;
                if (trimmed.startsWith("agent." + key)) {
                    rest = trimmed.substring(("agent." + key).length());
                } else if (trimmed.startsWith(key)) {
                    rest = trimmed.substring(key.length());
                } else {
                    continue;
                }
                // The '=' is what separates a key from a LONGER key that merely
                // shares its prefix - "endpoint-model" starts with
                // "endpoint-mode", so a prefix match alone would read the model
                // tag as the mode and quietly install a model the user opted out
                // of. Same trap for any future key pair.
                rest = rest.strip();
                if (!rest.startsWith("=")) continue;
                return rest.substring(1).strip()
                        .replace("\"", "").replace("'", "")
                        .toLowerCase(Locale.ROOT);
            }
        } catch (IOException ignored) {
        }
        return "";
    }
}
