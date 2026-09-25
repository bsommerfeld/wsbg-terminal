package de.bsommerfeld.wsbg.terminal.dashboard;

import de.bsommerfeld.wsbg.terminal.canvas.WidgetSpec;
import de.bsommerfeld.wsbg.terminal.fx.ViewModel;

import java.util.List;

/** The dashboard's state: the widget layout. Placeholders until the real widgets arrive. */
public final class DashboardViewModel implements ViewModel {

    private final List<WidgetSpec> widgets = List.of(
            new WidgetSpec("a", 96, 128, 288, 160, 2),
            new WidgetSpec("b", 416, 128, 224, 224, 3),
            new WidgetSpec("c", 96, 320, 288, 128, 1),
            new WidgetSpec("d", 704, 96, 256, 160, 2),
            new WidgetSpec("e", 704, 288, 160, 160, 3),
            new WidgetSpec("f", 896, 288, 128, 96, 1));

    public List<WidgetSpec> widgets() {
        return widgets;
    }
}
