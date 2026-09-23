package de.bsommerfeld.wsbg.terminal.ui;

import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;

/**
 * Registers the bundled fonts with the toolkit before the first scene is built.
 * The files are static instances, so each weight is its own family name and the
 * stylesheets address them as such: "JetBrains Mono", "JetBrains Mono Medium",
 * "JetBrains Mono SemiBold", "Inter", "Inter SemiBold".
 */
public final class Fonts {

    private static final String[] FILES = {
            "JetBrainsMono-Regular.ttf",
            "JetBrainsMono-Medium.ttf",
            "JetBrainsMono-SemiBold.ttf",
            "Inter-Regular.ttf",
            "Inter-SemiBold.ttf",
    };

    private Fonts() {
    }

    public static void load() {
        for (String file : FILES) {
            try (InputStream in = Fonts.class.getResourceAsStream("/de/bsommerfeld/wsbg/terminal/fonts/" + file)) {
                if (in == null || Font.loadFont(in, 16) == null) {
                    System.err.println("[fonts] not loaded: " + file);
                }
            } catch (IOException e) {
                System.err.println("[fonts] not loaded: " + file + " - " + e);
            }
        }
    }
}
