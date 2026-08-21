package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line + config inputs to the launch pipeline.
 *
 * <p>Both flags are consumed here (never forwarded to the terminal); the
 * terminal sets them when one of its own buttons restarts us:
 * <ul>
 *   <li>{@code --force-update} — "update now", so a one-off update still runs
 *       while auto-update is off;</li>
 *   <li>{@code --install-model} — "Jetzt neu starten" after a model change.
 *       The terminal has just written {@code agent.model-tag} and quit for the
 *       one reason it cannot handle itself: fetching that model. The flag says
 *       so out loud, so the run installs it and nothing else - no first-run
 *       question gets asked again, and the tag is taken at face value even if
 *       this launcher's catalog is older than the terminal's.</li>
 * </ul>
 * Everything else is forwarded verbatim.
 *
 * @param forceUpdate  whether {@code --force-update} was present
 * @param installModel whether {@code --install-model} was present
 * @param forwardArgs  the remaining args to hand to the terminal's {@code main}
 */
record LaunchArgs(boolean forceUpdate, boolean installModel, String[] forwardArgs) {

    /** The terminal's flag for a restart whose only purpose is the model pull. */
    static final String INSTALL_MODEL_FLAG = "--install-model";

    /** Splits the raw args into the launcher's own flags and the forwarded tail. */
    static LaunchArgs parse(String[] args) {
        boolean force = false;
        boolean installModel = false;
        List<String> appArgs = new ArrayList<>();
        for (String a : args) {
            if ("--force-update".equals(a)) force = true;
            else if (INSTALL_MODEL_FLAG.equals(a)) installModel = true;
            else appArgs.add(a);
        }
        return new LaunchArgs(force, installModel, appArgs.toArray(new String[0]));
    }

    /**
     * Reads {@code user.auto-update} from {@code config.toml} — opt-out, so a
     * missing key/file means {@code true}. Deliberately a tiny line scan (like
     * {@link LauncherI18n}) rather than pulling in the config library: the
     * launcher must stay lean and start even if the config is half-written.
     */
    static boolean configAutoUpdate(Path appDir) {
        Path configFile = appDir.resolve("config.toml");
        if (!Files.exists(configFile)) return true;
        try {
            for (String line : Files.readAllLines(configFile)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("auto-update")) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String value = trimmed.substring(eq + 1).strip()
                                .replace("\"", "").replace("'", "");
                        return !"false".equalsIgnoreCase(value);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return true;
    }
}
