package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.WindowDragHandler;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.util.Map;

/**
 * Receives one-shot commands from the page (window controls, refresh
 * trigger). Inbound messages have shape {@code {type: "window", payload:
 * {command: "...", edge: "..."}}}. The optional {@code edge} carries the
 * dragged direction for {@code resize-start} (e.g. "se", "n").
 */
@Singleton
public final class CommandBridge {

    private final WindowDragHandler dragHandler;

    @Inject
    public CommandBridge(PushHub hub, WindowDragHandler dragHandler) {
        this.dragHandler = dragHandler;
        hub.on("window", this::onWindow);
    }

    private void onWindow(Map<String, Object> payload) {
        Object cmd = payload.get("command");
        if (!(cmd instanceof String s)) return;
        Object edge = payload.get("edge");
        dragHandler.handle(s, edge instanceof String e ? e : null);
    }
}
