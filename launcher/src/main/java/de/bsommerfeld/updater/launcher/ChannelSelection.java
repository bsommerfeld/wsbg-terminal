package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.api.ReleaseChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Which releases this install accepts — read from {@code config.toml}
 * ({@code user.experimental-updates}), never asked here.
 *
 * <p>
 * The launcher used to put the question itself on a first start. It does not
 * any more: the switch lives in the terminal's settings, which is where a
 * user goes to change their mind anyway, and an install that has never been
 * near that switch belongs on {@link ReleaseChannel#STABLE} - nothing ever
 * lands on a pre-release without being asked for it.
 *
 * <p>
 * The config read is the same tiny line-scan as {@link LauncherI18n} /
 * {@link LaunchArgs} / {@link ModelSelection} — the launcher must stay lean and
 * start even on a half-written config.
 */
final class ChannelSelection {

    /** The config key, as jshepherd writes it inside {@code [user]}. */
    static final String CONFIG_KEY = "experimental-updates";

    /** The persisted answers. Anything else counts as unanswered, i.e. stable. */
    static final String YES = "yes";
    static final String NO = "no";

    private ChannelSelection() {
    }

    static ReleaseChannel resolve(Path appDir, SessionLog log) {
        String configured = configuredAnswer(appDir);
        ReleaseChannel channel = YES.equals(configured)
                ? ReleaseChannel.EXPERIMENTAL
                : ReleaseChannel.STABLE;

        log.log("Release channel: " + channel
                + (configured.isEmpty() ? " (no answer on record)" : " (user choice)"));
        return channel;
    }

    /**
     * Reads the answer from {@code config.toml}, or empty when the file, the
     * key, or the value is missing. A value that is neither {@code yes} nor
     * {@code no} counts as no answer at all — a typo must never silently open
     * the experimental channel.
     */
    static String configuredAnswer(Path appDir) {
        Path configFile = appDir.resolve("config.toml");
        if (!Files.exists(configFile)) return "";
        try {
            for (String line : Files.readAllLines(configFile)) {
                String trimmed = line.strip();
                // jshepherd writes the bare key inside [user]; accept the
                // fully-dotted form too so a hand-edited config still matches.
                if (trimmed.startsWith(CONFIG_KEY) || trimmed.startsWith("user." + CONFIG_KEY)) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String value = trimmed.substring(eq + 1).strip()
                                .replace("\"", "").replace("'", "")
                                .toLowerCase(Locale.ROOT);
                        return YES.equals(value) || NO.equals(value) ? value : "";
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }
}
