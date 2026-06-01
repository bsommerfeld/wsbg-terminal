package de.bsommerfeld.wsbg.terminal.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Window;

/**
 * Windows-only native title-bar tweaks via the Desktop Window Manager
 * (DWM). On Win/Linux the frame keeps its native OS decoration (real
 * min/max/close buttons, native drag + resize + Aero snap); the only
 * thing the OS won't do on its own is paint that title bar to match our
 * dark UI. {@code DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)}
 * flips the non-client area to the dark theme on Windows 10 (build
 * 17763+) and Windows 11.
 *
 * <p>
 * Everything here is best-effort: any failure (non-Windows host, older
 * Windows, JNA unavailable, AWT peer not yet realised) is swallowed and
 * the window simply keeps the default light title bar. There is no
 * equivalent for the macOS path — that stays on the {@code apple.awt.*}
 * client properties in {@link BrowserWindow}.
 */
final class WindowsChrome {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsChrome.class);

    /**
     * {@code DWMWA_USE_IMMERSIVE_DARK_MODE}. The attribute id moved between
     * Windows 10 builds: 19 on 1809–1909, 20 from 2004 onward (and Win11).
     * We try the modern value first, then the legacy one — setting an
     * unknown attribute just returns a non-zero HRESULT and is harmless.
     */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY = 19;

    private WindowsChrome() {}

    /** dwmapi.dll binding — only the one call we need. */
    private interface Dwmapi extends StdCallLibrary {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);

        int DwmSetWindowAttribute(Pointer hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
    }

    /**
     * Switches {@code window}'s native title bar to dark mode. No-op on any
     * platform other than Windows. Must be called after the window's native
     * peer exists (i.e. after {@code setVisible(true)} / {@code addNotify()}),
     * otherwise the HWND can't be resolved.
     */
    static void applyDarkTitleBar(Window window) {
        if (!isWindows()) return;
        try {
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd == null) {
                LOG.debug("No native window handle yet; skipping dark title bar.");
                return;
            }
            IntByReference enabled = new IntByReference(1); // TRUE
            int hr = Dwmapi.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, enabled, Integer.BYTES);
            if (hr != 0) {
                // Older Windows 10 build — retry with the legacy attribute id.
                Dwmapi.INSTANCE.DwmSetWindowAttribute(
                        hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY, enabled, Integer.BYTES);
            }
            LOG.info("Applied dark title bar to native window chrome.");
        } catch (Throwable t) {
            // Never fatal: degrade to the default (light) Windows title bar.
            LOG.debug("Could not apply dark title bar: {}", t.toString());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
