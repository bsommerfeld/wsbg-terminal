package de.bsommerfeld.updater.launcher;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

/**
 * Swing/JOptionPane error surfaces for the launcher, kept out of the pipeline
 * class. The launcher must <strong>never crash silently</strong>: every failure
 * path either recovers or presents a visible dialog before exiting.
 */
final class LauncherDialogs {

    private LauncherDialogs() {
    }

    /**
     * Shows the launcher window with the error and presents a modal dialog,
     * then exits with code 1. Ensures the user is never left staring at a blank
     * screen.
     */
    static void handleFatalError(Path appDir, LauncherWindow window, SessionLog log, Throwable e) {
        // Log to file and stderr — the file may not exist yet, so stderr is the fallback
        String msg = "Fatal: " + e.getMessage();
        log.log(msg);
        log.logStackTrace(e);
        e.printStackTrace(System.err);

        LauncherI18n i18n = new LauncherI18n(appDir);
        SwingUtilities.invokeLater(() -> {
            window.setVisible(true);
            window.setStatus(i18n.get("Error") + ": " + e.getMessage());
            window.setProgress(0);
        });
        showErrorDialog(i18n.get("Launcher failed"), "WSBG Terminal - " + i18n.get("Error"), e);
        System.exit(1);
    }

    /**
     * Presents a Swing error dialog with the exception's stack trace, truncated
     * to 500 characters to avoid overflowing the dialog bounds.
     */
    static void showErrorDialog(String message, String title, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        JOptionPane.showMessageDialog(null,
                message + "\n\n" + trace.substring(0, Math.min(trace.length(), 500)),
                title,
                JOptionPane.ERROR_MESSAGE);
    }
}
