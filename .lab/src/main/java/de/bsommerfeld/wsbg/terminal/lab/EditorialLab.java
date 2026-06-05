package de.bsommerfeld.wsbg.terminal.lab;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point for the editorial harness. Opens a small native window
 * ({@link LabWindow}): one text field per Reddit thread link, <b>+</b> to add
 * more, and <b>Los</b> to run the whole real pipeline (clustering + the editorial
 * AI) over the links and watch the trace. The process lives as long as the window
 * is open, so Ollama and the loaded models stay warm between runs; closing the
 * window shuts Ollama down.
 */
public final class EditorialLab {

    public static void main(String[] args) {
        // Nicer on macOS: real app name in the menu bar / dock.
        System.setProperty("apple.awt.application.name", "editorial-lab");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to the cross-platform L&F
        }

        SwingUtilities.invokeLater(() -> {
            LabWindow window = new LabWindow();
            window.setVisible(true);
            window.boot();
        });
    }

    private EditorialLab() {}
}
