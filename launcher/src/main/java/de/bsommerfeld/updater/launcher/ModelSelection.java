package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Hardware check + model choice — the backend behind the launcher's
 * model-choice screen ({@link ModelChoicePanel}).
 *
 * <p>
 * Probes the machine ({@link HardwareProbe}), grades every model tier
 * ({@link ModelCatalog}), and resolves the tag the setup script should install:
 * the user's explicit choice from {@code config.toml} ({@code agent.model-tag})
 * when present, else the managed default. <strong>The recommendation itself is
 * advisory only</strong> — it is computed, logged, and persisted to
 * {@code hardware-recommendation.json} for UIs to render, but it never
 * silently switches an install; only a user decision (the config key) does.
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
     * Machine-readable probe result — a diagnostic record of what THIS
     * launcher run saw and resolved. NOT a data feed for other UIs: a file
     * written "whenever the launcher last ran" goes stale the moment the app
     * updates without a launcher start (measured 2026-08-13, when a settings
     * UI briefly rendered it and missed three fresh catalog tiers); anything
     * that needs the ladder reads the compiled-in
     * {@code de.bsommerfeld.updater.catalog.ModelCatalog} instead.
     */
    static final String RECOMMENDATION_FILE = "hardware-recommendation.json";

    /**
     * @param effectiveTag   the tag the setup script installs and the runtime uses
     *                       (empty with an external endpoint - nothing is installed)
     * @param recommendedTag the hardware recommendation (platform-specific)
     * @param userChosen     whether effectiveTag came from config.toml
     * @param totalRamGb     probed machine RAM (0 = unprobeable, and with an
     *                       external endpoint: unprobed, because it decides nothing)
     * @param appleSilicon   whether the MLX twins apply on this machine
     * @param managedAi      whether the AI runtime is ours to install. False means
     *                       the user pointed the terminal at their own server:
     *                       no binary, no model, no model-choice screen - THIS
     *                       machine's memory then says nothing about what runs.
     */
    record Result(String effectiveTag, String recommendedTag, boolean userChosen,
            long totalRamGb, boolean appleSilicon, boolean managedAi) {
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
     *        than this launcher jar carries. Without this the choice would read
     *        as "nothing on record" and the first-run screen would come back,
     *        offering the short, stale tier list instead of installing what was
     *        asked for. The family gate stays in force for every other start,
     *        where the value really can be hand-edited nonsense.
     */
    static Result resolve(Path appDir, SessionLog log, boolean trustConfiguredTag) {
        if (!isManagedAi(appDir)) {
            // Nothing to grade and nothing to recommend: no model of ours goes
            // on this machine. The probe is skipped rather than run-and-ignored,
            // and hardware-recommendation.json is deliberately NOT refreshed -
            // a recommendation for an install that will not happen is a lie a
            // later UI would happily render.
            log.log("AI runtime: external endpoint configured (agent.endpoint-mode=remote) "
                    + "- skipping hardware check, model choice and model install");
            return new Result("", "", true, 0, false, false);
        }

        HardwareProbe hw = HardwareProbe.probe();
        long ramGb = hw.totalMemoryGb();
        boolean mlx = hw.isAppleSilicon();

        ModelCatalog recommended = ModelCatalog.recommend(ramGb);
        String recommendedTag = recommended.tagFor(mlx);

        String configured = configuredModelTag(appDir, trustConfiguredTag);
        boolean userChosen = !configured.isEmpty();
        if (trustConfiguredTag && !ModelCatalog.isDeployedFamily(configured) && userChosen) {
            log.log("Model tag " + configured + " is from a newer catalog than this launcher "
                    + "- installing it as asked (terminal requested the install)");
        }
        // No user choice = the managed default TIER, platform-suffixed: the MLX
        // build is the standard on Apple Silicon, the base tag everywhere else.
        // The recommendation may point at a bigger tier but never auto-applies.
        String effectiveTag = userChosen ? configured
                : ModelCatalog.DEFAULT.tagFor(mlx);

        log.log("Hardware: " + ramGb + " GB RAM, " + hw.osName() + "/" + hw.osArch()
                + (mlx ? " (Apple Silicon — MLX builds)" : ""));
        log.log("Model recommendation: " + recommendedTag
                + " — effective: " + effectiveTag
                + (userChosen ? " (user choice)" : " (managed default)"));

        writeRecommendationFile(appDir, hw, ramGb, mlx, recommendedTag, configured, effectiveTag, log);

        return new Result(effectiveTag, recommendedTag, userChosen, ramGb, mlx, true);
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

    /**
     * Persists the probe + per-tier verdicts as JSON beside the config, so the
     * model-choice UIs (the launcher screen today, terminal settings later)
     * only have to render it. Regenerated every launcher run — never stale, never load-bearing:
     * a write failure is logged and ignored.
     */
    private static void writeRecommendationFile(Path appDir, HardwareProbe hw, long ramGb,
            boolean mlx, String recommendedTag, String configuredTag, String effectiveTag,
            SessionLog log) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\n");
        json.append("  \"totalMemoryGb\": ").append(ramGb).append(",\n");
        json.append("  \"os\": \"").append(escape(hw.osName())).append("\",\n");
        json.append("  \"arch\": \"").append(escape(hw.osArch())).append("\",\n");
        json.append("  \"appleSilicon\": ").append(mlx).append(",\n");
        json.append("  \"recommendedTag\": \"").append(recommendedTag).append("\",\n");
        json.append("  \"configuredTag\": \"").append(escape(configuredTag)).append("\",\n");
        json.append("  \"effectiveTag\": \"").append(escape(effectiveTag)).append("\",\n");
        json.append("  \"models\": [\n");
        ModelCatalog[] tiers = ModelCatalog.values();
        for (int i = 0; i < tiers.length; i++) {
            ModelCatalog tier = tiers[i];
            json.append("    {\"tag\": \"").append(tier.tagFor(mlx))
                    .append("\", \"name\": \"").append(escape(tier.displayName()))
                    .append("\", \"diskGb\": ").append(tier.diskGbFor(mlx))
                    .append(", \"minRamGb\": ").append(tier.minRamGb())
                    .append(", \"recommendedRamGb\": ").append(tier.recommendedRamGb())
                    .append(", \"quality\": ").append(tier.quality())
                    .append(", \"speed\": ").append(tier.speed())
                    .append(", \"fit\": \"").append(tier.fitFor(ramGb))
                    .append("\", \"recommended\": ").append(tier.tagFor(mlx).equals(recommendedTag))
                    .append('}').append(i < tiers.length - 1 ? "," : "").append('\n');
        }
        json.append("  ]\n}\n");

        try {
            Files.writeString(appDir.resolve(RECOMMENDATION_FILE), json.toString());
        } catch (IOException e) {
            log.log("Could not write " + RECOMMENDATION_FILE + ": " + e.getMessage());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
