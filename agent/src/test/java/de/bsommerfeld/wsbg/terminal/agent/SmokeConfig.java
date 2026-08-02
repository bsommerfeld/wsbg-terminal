package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.jshepherd.core.ConfigurationLoader;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The config the LIVE smokes run on — the very same {@code config.toml} the
 * app itself loads (mirrors {@code AppModule.loadConfig}).
 *
 * <p>A bare {@code new GlobalConfig()} carries only the compiled-in defaults,
 * so the user's {@code agent.model-tag} never reached the smokes: they resolved
 * to the managed default tier instead, and when that tag happened to not be
 * installed, {@code OllamaModelFactory}'s family fallback silently picked
 * whatever gemma4 build Ollama listed first. A smoke that cannot say WHICH
 * model produced its transcript is not a smoke — so every live smoke reads the
 * real config and therefore the real, chosen model.
 *
 * <p>No config file (a fresh checkout, CI) simply yields the defaults.
 */
final class SmokeConfig {

    private SmokeConfig() {
    }

    /** The app's {@code config.toml}, or the plain defaults when there is none. */
    static GlobalConfig load() {
        Path configPath = StorageUtils.getAppDataDir().resolve("config.toml");
        if (!Files.exists(configPath)) {
            System.out.println("[SMOKE] no config.toml at " + configPath + " — using defaults.");
            return new GlobalConfig();
        }
        GlobalConfig config = ConfigurationLoader.from(configPath).withComments()
                .load(GlobalConfig::new);
        System.out.println("[SMOKE] config: " + configPath
                + " — model " + config.getAgent().resolveModelTag()
                + ", num_ctx " + config.getAgent().resolveContextTokens());
        return config;
    }
}
