package de.bsommerfeld.wsbg.terminal.fx;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

public final class Leaf extends FxmlNode<LeafViewModel> {

    @FXML
    private Label text;

    @FXML
    private void initialize() {
        text.textProperty().bind(viewModel.text());
    }

    public Label label() {
        return text;
    }
}
