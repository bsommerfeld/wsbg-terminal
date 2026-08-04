package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin {@link java.awt.Desktop} helpers — open a URL in the OS browser or reveal
 * a folder in the OS file manager. Extracted from {@link CefHost}: unrelated to
 * the JCEF runtime, they lived there only for proximity. {@code CefHost} keeps
 * public static delegators so existing call sites stay byte-stable.
 */
final class DesktopLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopLauncher.class);

    private DesktopLauncher() {}

    /** Opens {@code url} in the OS default browser; failures are logged, never thrown. */
    static void openExternal(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                LOG.info("Opening external URL in OS browser: {}", url);
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                LOG.warn("Desktop BROWSE action unsupported — cannot open {}", url);
            }
        } catch (Exception e) {
            LOG.warn("Failed to open external URL {}: {}", url, e.getMessage());
        }
    }

    /**
     * Opens {@code file} in whatever application the OS associates with it
     * (Preview / Acrobat / Evince for a PDF). A missing file is skipped rather
     * than handed to the OS; failures are logged, never thrown.
     */
    static void openFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                LOG.warn("Not opening {} — file does not exist", file);
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                LOG.info("Opening file in the OS default application: {}", file);
                Desktop.getDesktop().open(file.toFile());
            } else {
                LOG.warn("Desktop OPEN action unsupported — cannot open {}", file);
            }
        } catch (Exception e) {
            LOG.warn("Failed to open file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Reveals {@code dir} in the OS file manager (Finder / Explorer / Nautilus);
     * the directory is created first if it's missing so the OPEN action never
     * fails on a fresh install. Failures are logged, never thrown.
     */
    static void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                LOG.info("Opening folder in OS file manager: {}", dir);
                Desktop.getDesktop().open(dir.toFile());
            } else {
                LOG.warn("Desktop OPEN action unsupported — cannot open {}", dir);
            }
        } catch (Exception e) {
            LOG.warn("Failed to open folder {}: {}", dir, e.getMessage());
        }
    }
}
