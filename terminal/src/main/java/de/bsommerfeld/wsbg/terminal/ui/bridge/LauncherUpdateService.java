package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Titlebar's amber "renew launcher" button — the one-time rescue path for
 * installed launcher hulls that predate launcher self-update. Deliberately
 * ISOLATED from {@link UpdateService} (the green button): the two indicators
 * never share state, wire topics, or DOM, so both can show at the same time.
 *
 * <p>The launcher hands its hull generation to the terminal via
 * {@code WSBG_LAUNCHER_GENERATION} (see {@code AppLauncher} in the launcher
 * module — bumped only in lockstep with {@link #REQUIRED_GENERATION}).
 * Launchers old enough to not set it count as generation 1. When the installed
 * hull is older than required, the button lights up; clicking it opens the
 * platform installer download in the OS browser — the officially signed,
 * elevation-capable path on every OS. No silent patching of the native
 * install: that would need elevation on Windows/Linux and would break the
 * codesign seal on macOS.
 *
 * <p>The state is fixed for the process lifetime (the hull can't change under
 * a running terminal), so there is no polling — one determination at startup,
 * pushed to every connecting client. Once hulls self-update their jar, this
 * button effectively never appears again.
 */
@Singleton
public final class LauncherUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(LauncherUpdateService.class);

    /**
     * The hull generation this terminal requires. Generation 2 = the first
     * hull that reports its generation at all. Bump ONLY together with the
     * value {@code AppLauncher} sets, and only for changes an installed
     * launcher cannot apply to itself (bundled runtime, packaging).
     */
    static final int REQUIRED_GENERATION = 2;

    private static final String DOWNLOAD_BASE =
            "https://github.com/bsommerfeld/wsbg-terminal/releases/latest/download/";
    private static final String RELEASES_PAGE =
            "https://github.com/bsommerfeld/wsbg-terminal/releases/latest";

    private final PushHub hub;
    private final boolean outdated;

    @Inject
    public LauncherUpdateService(PushHub hub) {
        this.hub = hub;
        this.outdated = isHullOutdated(
                System.getenv("WSBG_LAUNCHER_EXECUTABLE"),
                System.getenv("WSBG_LAUNCHER_GENERATION"));

        hub.on("launcher-update", this::onCommand);
        hub.onClientOpen(this::push);

        if (outdated) {
            LOG.info("Installed launcher hull is outdated (< generation {}) — amber renew button active.",
                    REQUIRED_GENERATION);
        }
    }

    private void onCommand(Map<String, Object> payload) {
        if (outdated && "get".equals(payload.get("command"))) {
            String url = installerUrl();
            LOG.info("User requested launcher renewal → opening {}", url);
            CefHost.openExternal(url);
        }
    }

    private void push() {
        hub.broadcast("launcher-update-available", Map.of("available", outdated));
    }

    /**
     * A hull is outdated when a launcher started us ({@code exe} set — dev
     * {@code run.sh} starts never nag) and its reported generation is below
     * {@link #REQUIRED_GENERATION}. Absent/garbled generation = 1: every
     * launcher shipped before the handshake existed.
     */
    static boolean isHullOutdated(String exe, String generation) {
        if (exe == null || exe.isBlank()) return false;
        int gen = 1;
        if (generation != null && !generation.isBlank()) {
            try {
                gen = Integer.parseInt(generation.trim());
            } catch (NumberFormatException ignored) {
                // unparseable → treat as pre-handshake hull
            }
        }
        return gen < REQUIRED_GENERATION;
    }

    /** The stable, version-less installer asset for this OS (see package.yml). */
    private static String installerUrl() {
        return installerUrl(System.getProperty("os.name", ""),
                Files.isRegularFile(Path.of("/usr/bin/dpkg")),
                Files.isRegularFile(Path.of("/usr/bin/rpm")));
    }

    /**
     * Package-private for testing. Linux picks .deb vs .rpm by the package
     * manager present; an exotic distro with neither falls back to the
     * releases page where the user picks manually.
     */
    static String installerUrl(String osName, boolean hasDpkg, boolean hasRpm) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return DOWNLOAD_BASE + "WSBG-Terminal-Windows.exe";
        if (os.contains("mac")) return DOWNLOAD_BASE + "WSBG-Terminal-macOS.dmg";
        if (hasDpkg) return DOWNLOAD_BASE + "WSBG-Terminal-Linux.deb";
        if (hasRpm) return DOWNLOAD_BASE + "WSBG-Terminal-Linux.rpm";
        return RELEASES_PAGE;
    }
}
