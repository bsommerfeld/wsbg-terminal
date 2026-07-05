package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line + config inputs to the launch pipeline.
 *
 * <p>{@code --force-update} is consumed here (not forwarded to the terminal):
 * the terminal's in-app "update now" button relaunches the launcher with this
 * flag so a one-off update still runs while auto-update is off. Everything else
 * is forwarded verbatim.
 *
 * @param forceUpdate  whether {@code --force-update} was present
 * @param forwardArgs  the remaining args to hand to the terminal's {@code main}
 */
record LaunchArgs(boolean forceUpdate, String[] forwardArgs) {

    /** Splits the raw args into the force-update flag and the forwarded tail. */
    static LaunchArgs parse(String[] args) {
        boolean force = false;
        List<String> appArgs = new ArrayList<>();
        for (String a : args) {
            if ("--force-update".equals(a)) force = true;
            else appArgs.add(a);
        }
        return new LaunchArgs(force, appArgs.toArray(new String[0]));
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
