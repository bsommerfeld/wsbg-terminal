package de.bsommerfeld.wsbg.terminal.dashboard;

import de.bsommerfeld.wsbg.terminal.canvas.DesktopCanvas;
import de.bsommerfeld.wsbg.terminal.fx.FxmlNode;
import de.bsommerfeld.wsbg.terminal.fx.View;
import javafx.fxml.FXML;

/**
 * The view in the shell's frame: the {@link DesktopCanvas}, the widgets on it.
 */
@View
public final class Dashboard extends FxmlNode<DashboardViewModel> {

    @FXML
    private DesktopCanvas canvas;

    @FXML
    private void initialize() {
        canvas.getWidgets().setAll(viewModel.widgets());
    }
}
