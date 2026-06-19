package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.WindowDragHandler;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.util.Map;

/**
 * Receives one-shot commands from the page (window controls, refresh
 * trigger). Inbound messages have shape {@code {type: "window", payload:
 * {command: "...", edge: "..."}}}. The optional {@code edge} carries the
 * dragged direction for {@code resize-start} (e.g. "se", "n").
 *
 * <p>Also handles {@code {type: "open-external", payload: {url}}}: the page
 * intercepts clicks on external anchors and routes them here, because the
 * off-screen Chromium swallows {@code target="_blank"} popups (no popup
 * window exists in OSR mode and popup interception proved unreliable live).
 * Only http(s) URLs that don't point back at the loopback are opened.
 */
@Singleton
public final class CommandBridge {

    private final WindowDragHandler dragHandler;

    @Inject
    public CommandBridge(PushHub hub, WindowDragHandler dragHandler) {
        this.dragHandler = dragHandler;
        hub.on("window", this::onWindow);
        hub.on("open-external", this::onOpenExternal);
    }

    private void onWindow(Map<String, Object> payload) {
        Object cmd = payload.get("command");
        if (!(cmd instanceof String s)) return;
        Object edge = payload.get("edge");
        dragHandler.handle(s, edge instanceof String e ? e : null);
    }

    private void onOpenExternal(Map<String, Object> payload) {
        Object url = payload.get("url");
        if (!(url instanceof String s)) return;
        if (!(s.startsWith("https://") || s.startsWith("http://"))) return;
        if (s.startsWith("http://127.0.0.1")) return;
        CefHost.openExternal(s);
    }
}
