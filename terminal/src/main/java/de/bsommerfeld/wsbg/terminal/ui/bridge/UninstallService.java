package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.ui.AppMain;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Settings view's "Deinstallieren": removes the whole install — launcher,
 * app data (config, Ollama store, JCEF cache, headline archive), shortcuts —
 * in one click, on every OS.
 *
 * <p>Inbound: {@code {type:"uninstall", payload:{command:"apply"}}}. The page
 * arms the button with a second-click confirm, so arriving here IS the
 * confirmation.
 *
 * <p>The actual removal always runs DETACHED, after this process has fully
 * exited — while we live, our own shutdown (session snapshots) and the CEF
 * teardown (cache flush) would re-create files inside the data directory and
 * leave residue behind. So the flow is: stop services → spawn the detached
 * cleanup (which sleeps first) → tear CEF down → exit → cleanup runs against
 * a dead install ({@link AppMain#uninstallAndExit}).
 *
 * <p>Per platform:
 * <ul>
 *   <li><b>Windows</b> — the launcher is a per-machine MSI, so the clean path is
 *       {@code msiexec /x{ProductCode}} (UAC prompt): the installer removes the
 *       program + shortcuts and its WsbgCleanAppData action wipes the data dir.
 *       The product code is looked up in the registry uninstall keys (it changes
 *       per version). No MSI found (dev run) → fallback: wipe the data dir and
 *       show a "cleaned up, uninstall via your OS" message box.</li>
 *   <li><b>macOS</b> — no package manager: the script wipes the data dir and
 *       deletes the app bundle (resolved from {@code WSBG_LAUNCHER_EXECUTABLE},
 *       which points inside the .app). No bundle known (dev run) → fallback:
 *       wipe + dialog.</li>
 *   <li><b>Linux</b> — the script asks PolicyKit ({@code pkexec}) to remove the
 *       {@code wsbg-terminal} package via whatever package manager exists
 *       (apt-get/dnf/zypper/yum), then wipes the data dir either way. If the
 *       package survived (prompt dismissed, no polkit, dev run), a best-effort
 *       zenity/kdialog/notify-send tells the user the data is cleaned and the
 *       package can be removed with the package manager.</li>
 * </ul>
 */
@Singleton
public final class UninstallService {

    private static final Logger LOG = LoggerFactory.getLogger(UninstallService.class);

    /** Debian/rpm package name jpackage derives from the app name "WSBG Terminal". */
    private static final String LINUX_PACKAGE = "wsbg-terminal";

    /** Registry product-code lookup: value data to search + the {GUID} key line. */
    private static final String WINDOWS_DISPLAY_NAME = "WSBG Terminal";
    private static final Pattern GUID_KEY_LINE =
            Pattern.compile("\\\\(\\{[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\\})\\s*$");

    private final GlobalConfig config;

    @Inject
    public UninstallService(GlobalConfig config, PushHub hub) {
        this.config = config;
        hub.on("uninstall", this::onCommand);
    }

    private void onCommand(Map<String, Object> payload) {
        if (!"apply".equals(payload.get("command"))) return;
        try {
            List<String> detached = buildDetachedCleanup();
            LOG.info("User requested uninstall — shutting down and handing off to: {}", detached);
            AppMain.uninstallAndExit(detached);
        } catch (Exception e) {
            LOG.error("Uninstall failed to start: {}", e.getMessage());
        }
    }

    /** The OS-specific cleanup command that runs after this process has exited. */
    private List<String> buildDetachedCleanup() {
        String os = System.getProperty("os.name", "").toLowerCase();
        Path dataDir = StorageUtils.getAppDataDir();
        if (os.contains("win")) return windowsCleanup(dataDir);
        if (os.contains("mac")) return macCleanup(dataDir);
        return linuxCleanup(dataDir);
    }

    private List<String> windowsCleanup(Path dataDir) {
        String productCode = findWindowsProductCode();
        // ping is the console-less delay (timeout.exe refuses detached runs);
        // ~5s so the CEF teardown has finished flushing its cache before the
        // MSI's data wipe (or the rd) runs against the dead install.
        String delay = "ping -n 6 127.0.0.1 >nul";
        if (productCode != null) {
            // /qb: basic progress only — the in-app button already confirmed.
            return List.of("cmd", "/c",
                    delay + " & msiexec /x" + productCode + " /qb");
        }
        // Fallback (dev run / MSI not found): wipe the data dir ourselves and
        // say so — the user removes whatever remains via the OS.
        String msgBox = "powershell -NoProfile -Command \"Add-Type -AssemblyName System.Windows.Forms;"
                + "[System.Windows.Forms.MessageBox]::Show('" + cleanedUpMessage() + "','WSBG Terminal')\"";
        return List.of("cmd", "/c",
                delay + " & rd /s /q \"" + dataDir + "\" & " + msgBox);
    }

    private List<String> macCleanup(Path dataDir) {
        Path appBundle = resolveMacAppBundle();
        String wipeData = "rm -rf " + shellQuote(dataDir.toString());
        if (appBundle != null) {
            return List.of("/bin/sh", "-c",
                    "sleep 3; " + wipeData + "; rm -rf " + shellQuote(appBundle.toString()));
        }
        // Fallback (dev run): wipe + tell the user the rest is theirs.
        return List.of("/bin/sh", "-c",
                "sleep 3; " + wipeData + "; osascript -e 'display dialog \""
                        + cleanedUpMessage() + "\" with title \"WSBG Terminal\" buttons {\"OK\"} default button 1'");
    }

    private List<String> linuxCleanup(Path dataDir) {
        String msg = cleanedUpMessage();
        String script = "sleep 3\n"
                // PolicyKit GUI prompt; postrm/%postun wipes the data dir on success.
                + "if command -v pkexec >/dev/null 2>&1; then\n"
                + "  if command -v apt-get >/dev/null 2>&1; then pkexec apt-get remove -y " + LINUX_PACKAGE + "\n"
                + "  elif command -v dnf >/dev/null 2>&1; then pkexec dnf remove -y " + LINUX_PACKAGE + "\n"
                + "  elif command -v zypper >/dev/null 2>&1; then pkexec zypper --non-interactive remove " + LINUX_PACKAGE + "\n"
                + "  elif command -v yum >/dev/null 2>&1; then pkexec yum remove -y " + LINUX_PACKAGE + "\n"
                + "  fi\n"
                + "fi\n"
                // Wipe the data dir either way (idempotent after a postrm wipe).
                + "rm -rf " + shellQuote(dataDir.toString()) + "\n"
                // Package survived (prompt dismissed / no polkit / dev run) →
                // best-effort dialog: data is clean, remove the package yourself.
                + "if [ -d /opt/" + LINUX_PACKAGE + " ]; then\n"
                + "  zenity --info --title 'WSBG Terminal' --text \"" + msg + "\" 2>/dev/null"
                + " || kdialog --title 'WSBG Terminal' --msgbox \"" + msg + "\" 2>/dev/null"
                + " || notify-send 'WSBG Terminal' \"" + msg + "\" 2>/dev/null\n"
                + "fi\n";
        return List.of("/bin/sh", "-c", script);
    }

    /**
     * Finds the installed MSI's product code by scanning the registry uninstall
     * keys for the app's display name — the code changes with every version, so
     * it can never be hardcoded. Checks per-machine (64-bit + WOW64) and
     * per-user hives. Null when nothing is installed (dev run).
     */
    private static String findWindowsProductCode() {
        String[] roots = {
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        };
        for (String root : roots) {
            try {
                Process p = new ProcessBuilder("reg", "query", root,
                        "/s", "/f", WINDOWS_DISPLAY_NAME, "/d")
                        .redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Matcher m = GUID_KEY_LINE.matcher(line.trim());
                        if (m.find()) {
                            p.destroy();
                            return m.group(1);
                        }
                    }
                }
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.debug("reg query {} failed: {}", root, e.getMessage());
            }
        }
        return null;
    }

    /**
     * The installed .app bundle, derived from the launcher executable path the
     * launcher hands us ({@code …/WSBG Terminal.app/Contents/MacOS/…}). Null in
     * a dev run (no launcher).
     */
    private static Path resolveMacAppBundle() {
        String exe = System.getenv("WSBG_LAUNCHER_EXECUTABLE");
        if (exe == null || exe.isBlank()) return null;
        Path p = Path.of(exe).toAbsolutePath();
        while (p != null && !p.getFileName().toString().endsWith(".app")) {
            p = p.getParent();
        }
        return p != null && Files.isDirectory(p) ? p : null;
    }

    /**
     * The fallback-path dialog text, in the app's configured language (the same
     * live source the headlines read; the front-end i18n layer can't reach a
     * post-exit OS dialog). No apostrophes — the string is embedded in shell,
     * osascript and PowerShell quoting.
     */
    private String cleanedUpMessage() {
        boolean de = !"en".equalsIgnoreCase(config.getUser().getLanguage());
        return de
                ? "Bereinigt - alle Daten wurden entfernt. Du kannst die App jetzt rückstandsfrei über dein System deinstallieren."
                : "Cleaned up - all data has been removed. You can now uninstall the app from your system without leftovers.";
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
