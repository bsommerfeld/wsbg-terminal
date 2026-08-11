package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.api.ReleaseChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Which releases this install accepts — the backend behind the launcher's
 * channel-choice screen ({@link ChannelChoicePanel}).
 *
 * <p>
 * Reads {@code user.experimental-updates} from {@code config.toml} as a
 * tri-state: {@code "yes"} / {@code "no"} are answers, everything else
 * (missing key, missing file, empty value) means the question was never put.
 * That third state is the whole point — it is what makes the launcher ask
 * exactly once, on a fresh install and on the first start of an install that
 * predates the question alike. An unanswered install stays on
 * {@link ReleaseChannel#STABLE} until the user says otherwise, so nothing ever
 * lands on a pre-release by default.
 *
 * <p>
 * The config read is the same tiny line-scan as {@link LauncherI18n} /
 * {@link LaunchArgs} / {@link ModelSelection} — the launcher must stay lean and
 * start even on a half-written config.
 */
final class ChannelSelection {

    /** The config key, as jshepherd writes it inside {@code [user]}. */
    static final String CONFIG_KEY = "experimental-updates";

    /** The persisted answers. Anything else counts as unanswered. */
    static final String YES = "yes";
    static final String NO = "no";

    /**
     * @param channel    the channel to run this start on
     * @param userChosen whether that came from an answer on record rather than
     *                   from the safe default
     */
    record Result(ReleaseChannel channel, boolean userChosen) {
    }

    private ChannelSelection() {
    }

    static Result resolve(Path appDir, SessionLog log) {
        String configured = configuredAnswer(appDir);
        boolean userChosen = !configured.isEmpty();
        ReleaseChannel channel = YES.equals(configured)
                ? ReleaseChannel.EXPERIMENTAL
                : ReleaseChannel.STABLE;

        log.log("Release channel: " + channel
                + (userChosen ? " (user choice)" : " (no answer on record)"));
        return new Result(channel, userChosen);
    }

    /** The channel an answer maps to. */
    static ReleaseChannel channelOf(String answer) {
        return YES.equals(answer) ? ReleaseChannel.EXPERIMENTAL : ReleaseChannel.STABLE;
    }

    /**
     * Reads the answer from {@code config.toml}, or empty when the file, the
     * key, or the value is missing. A value that is neither {@code yes} nor
     * {@code no} counts as no answer at all — a typo must put the question
     * again, never silently open the experimental channel.
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
