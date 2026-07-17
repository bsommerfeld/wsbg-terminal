package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Hardware check + model choice — the backend behind the launcher's
 * model-choice screen ({@link ModelChoicePanel}).
 *
 * <p>
 * Probes the machine ({@link HardwareProbe}), grades every gemma4 tier
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

    /** Machine-readable probe result for model-choice UIs (e.g. terminal settings). */
    static final String RECOMMENDATION_FILE = "hardware-recommendation.json";

    /** Only the one deployed family is installable — anything else is a typo. */
    private static final String MODEL_FAMILY = "gemma4:";

    /**
     * @param effectiveTag   the tag the setup script installs and the runtime uses
     * @param recommendedTag the hardware recommendation (platform-specific)
     * @param userChosen     whether effectiveTag came from config.toml
     * @param totalRamGb     probed machine RAM (0 = unprobeable)
     * @param appleSilicon   whether the MLX twins apply on this machine
     */
    record Result(String effectiveTag, String recommendedTag, boolean userChosen,
            long totalRamGb, boolean appleSilicon) {
    }

    private ModelSelection() {
    }

    static Result resolve(Path appDir, SessionLog log) {
        HardwareProbe hw = HardwareProbe.probe();
        long ramGb = hw.totalMemoryGb();
        boolean mlx = hw.isAppleSilicon();

        ModelCatalog recommended = ModelCatalog.recommend(ramGb);
        String recommendedTag = recommended.tagFor(mlx);

        String configured = configuredModelTag(appDir);
        boolean userChosen = !configured.isEmpty();
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

        return new Result(effectiveTag, recommendedTag, userChosen, ramGb, mlx);
    }

    /**
     * Reads {@code agent.model-tag} from {@code config.toml}. Empty when the
     * file/key is missing or the value is not a gemma4 tag — an unknown family
     * must degrade to the default, never reach {@code ollama pull} verbatim.
     */
    static String configuredModelTag(Path appDir) {
        Path configFile = appDir.resolve("config.toml");
        if (!Files.exists(configFile)) return "";
        try {
            for (String line : Files.readAllLines(configFile)) {
                String trimmed = line.strip();
                // jshepherd writes the fully-dotted key inside [agent]; accept
                // the bare form too so a hand-edited config still works.
                if (trimmed.startsWith("agent.model-tag") || trimmed.startsWith("model-tag")) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String value = trimmed.substring(eq + 1).strip()
                                .replace("\"", "").replace("'", "")
                                .toLowerCase(Locale.ROOT);
                        if (value.startsWith(MODEL_FAMILY)) return value;
                        return "";
                    }
                }
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
