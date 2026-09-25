package de.bsommerfeld.wsbg.terminal.ui;

import java.net.URL;
import java.util.Arrays;
import java.util.List;

/** The application's stylesheets, in cascade order. */
public final class Stylesheets {

    private static final String[] FILES = {"tokens.css", "base.css", "titlebar.css", "canvas.css"};

    private Stylesheets() {
    }

    public static List<URL> urls() {
        return Arrays.stream(FILES)
                .map(f -> Stylesheets.class.getResource("/de/bsommerfeld/wsbg/terminal/css/" + f))
                .toList();
    }

    public static List<String> all() {
        return urls().stream().map(URL::toExternalForm).toList();
    }
}
