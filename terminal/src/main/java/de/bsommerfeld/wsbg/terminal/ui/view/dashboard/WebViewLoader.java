package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Assembles the terminal WebView HTML by loading CSS, JS, and the
 * HTML shell from resource files and injecting runtime font URLs.
 */
final class WebViewLoader {

    private WebViewLoader() {
    }

    /**
     * Builds the complete HTML string for the terminal WebView,
     * injecting the given font-face CSS block into the template.
     */
    static String buildTerminalHtml(String fontFaceCss) {
        String css = readResource("/webview/terminal.css")
                .replace("{{FONT_FACES}}", fontFaceCss);
        String js = readResource("/webview/terminal.js");
        return readResource("/webview/terminal.html")
                .replace("{{CSS}}", css)
                .replace("{{JS}}", js);
    }

    private static String readResource(String path) {
        try (InputStream in = WebViewLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("WebView resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read WebView resource: " + path, e);
        }
    }
}
