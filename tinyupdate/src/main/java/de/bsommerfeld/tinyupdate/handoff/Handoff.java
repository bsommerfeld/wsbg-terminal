package de.bsommerfeld.tinyupdate.handoff;

import de.bsommerfeld.tinyupdate.api.GitHubRepository;
import de.bsommerfeld.tinyupdate.api.ReleaseAssetNames;
import de.bsommerfeld.tinyupdate.api.ReleaseChannel;
import de.bsommerfeld.tinyupdate.api.TinyUpdateClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What an application hands its updater when it steps aside for an update:
 * which process to wait for, which install to update from which stream, what
 * to run once the files are in place, and how to start the application again.
 *
 * <h3>Why a separate process</h3>
 * A running application cannot replace its own files - Windows locks every
 * loaded jar, and swapping jars under a live JVM breaks it on every platform.
 * So the application starts its updater with a handoff, exits, and the
 * updater applies the update once the application's process is gone.
 *
 * <h3>The command line is the contract</h3>
 * The handoff travels as command-line arguments ({@link #toArguments()} on the
 * application's side, {@link #parse} on the updater's), both defined here, so
 * the two sides cannot drift apart. The relaunch command comes last, after
 * {@code --}, and is taken verbatim:
 *
 * <pre>
 * --wait 4242 --name "WSBG Terminal" --install /data/app
 * --repository owner/repo --channel STABLE --assets wsbg --platform macos-aarch64
 * --post-update bin/setup.sh
 * -- /Applications/WSBG Terminal.app/Contents/MacOS/WSBG Terminal
 * </pre>
 *
 * @param waitFor          the process the updater waits for before touching a file
 * @param applicationName  the application's name as the updater shows it
 * @param installDirectory the install the update is applied to
 * @param repository       the repository whose releases carry the stream
 * @param channel          which releases the install accepts
 * @param assetPrefix      the stream's asset prefix ({@link ReleaseAssetNames#of}), may be empty
 * @param platform         the stream's platform ({@link ReleaseAssetNames#of}), may be empty
 * @param postUpdate       a script, relative to the install, run once the
 *                         install matches the release; {@code null} for none
 * @param relaunch         the command that starts the application again
 */
public record Handoff(long waitFor, String applicationName, Path installDirectory,
        GitHubRepository repository, ReleaseChannel channel, String assetPrefix, String platform,
        String postUpdate, List<String> relaunch) {

    private static final String WAIT = "--wait";
    private static final String NAME = "--name";
    private static final String INSTALL = "--install";
    private static final String REPOSITORY = "--repository";
    private static final String API = "--api";
    private static final String CHANNEL = "--channel";
    private static final String ASSETS = "--assets";
    private static final String PLATFORM = "--platform";
    private static final String POST_UPDATE = "--post-update";
    private static final String RELAUNCH = "--";

    public Handoff {
        Objects.requireNonNull(applicationName, "applicationName");
        Objects.requireNonNull(installDirectory, "installDirectory");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(channel, "channel");
        assetPrefix = assetPrefix == null ? "" : assetPrefix;
        platform = platform == null ? "" : platform;
        relaunch = List.copyOf(relaunch);
        if (relaunch.isEmpty()) {
            throw new IllegalArgumentException("relaunch must name a command");
        }
    }

    /** The update client for the handed-off stream and install. */
    public TinyUpdateClient client() {
        return new TinyUpdateClient(repository, installDirectory, channel,
                ReleaseAssetNames.of(assetPrefix, platform));
    }

    /** The handoff as command-line arguments, the form {@link #parse} reads back. */
    public List<String> toArguments() {
        List<String> arguments = new ArrayList<>();
        arguments.addAll(List.of(WAIT, Long.toString(waitFor)));
        arguments.addAll(List.of(NAME, applicationName));
        arguments.addAll(List.of(INSTALL, installDirectory.toString()));
        arguments.addAll(List.of(REPOSITORY, repository.owner() + "/" + repository.repo()));
        arguments.addAll(List.of(API, repository.apiBase()));
        arguments.addAll(List.of(CHANNEL, channel.name()));

        /*
         * Empty values stay off the command line instead of travelling as ""
         * - Windows' argument quoting has a history of dropping empty
         * arguments, and an absent option parses back to the same empty value.
         */
        if (!assetPrefix.isEmpty()) {
            arguments.addAll(List.of(ASSETS, assetPrefix));
        }
        if (!platform.isEmpty()) {
            arguments.addAll(List.of(PLATFORM, platform));
        }
        if (postUpdate != null) {
            arguments.addAll(List.of(POST_UPDATE, postUpdate));
        }

        arguments.add(RELAUNCH);
        arguments.addAll(relaunch);
        return arguments;
    }

    /**
     * Reads a handoff back from its command-line form.
     *
     * @throws IllegalArgumentException for an unknown option, an option
     *                                  without its value, or a required one missing
     */
    public static Handoff parse(List<String> arguments) {
        Long waitFor = null;
        String name = null;
        Path install = null;
        String repository = null;
        String api = null;
        ReleaseChannel channel = ReleaseChannel.STABLE;
        String prefix = "";
        String platform = "";
        String postUpdate = null;
        List<String> relaunch = List.of();

        for (int index = 0; index < arguments.size(); index++) {
            String option = arguments.get(index);
            if (option.equals(RELAUNCH)) {
                relaunch = arguments.subList(index + 1, arguments.size());
                break;
            }
            if (index + 1 >= arguments.size()) {
                throw new IllegalArgumentException("Option " + option + " has no value");
            }
            String value = arguments.get(++index);
            switch (option) {
                case WAIT -> waitFor = parsePid(value);
                case NAME -> name = value;
                case INSTALL -> install = Path.of(value);
                case REPOSITORY -> repository = value;
                case API -> api = value;
                case CHANNEL -> channel = ReleaseChannel.valueOf(value);
                case ASSETS -> prefix = value;
                case PLATFORM -> platform = value;
                case POST_UPDATE -> postUpdate = value;
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }

        GitHubRepository slug = GitHubRepository.of(require(repository, REPOSITORY));
        return new Handoff(require(waitFor, WAIT), require(name, NAME), require(install, INSTALL),
                api == null ? slug : new GitHubRepository(slug.owner(), slug.repo(), api),
                channel, prefix, platform, postUpdate, relaunch);
    }

    /**
     * Starts the updater with this handoff and returns without waiting for it.
     * The caller exits right after - the updater waits for exactly that.
     *
     * @param updaterCommand the command that starts the updater; the handoff's
     *                       arguments are appended to it
     * @param log            where the updater's console output goes (appended);
     *                       it outlives the caller, so it must not write into a
     *                       pipe nobody reads any more
     */
    public Process start(List<String> updaterCommand, Path log) throws IOException {
        List<String> command = new ArrayList<>(updaterCommand);
        command.addAll(toArguments());
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    private static long parsePid(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a process id: " + value, e);
        }
    }

    private static <T> T require(T value, String option) {
        if (value == null) {
            throw new IllegalArgumentException("Missing option " + option);
        }
        return value;
    }
}
