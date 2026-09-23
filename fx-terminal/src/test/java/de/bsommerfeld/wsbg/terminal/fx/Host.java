package de.bsommerfeld.wsbg.terminal.fx;

import javafx.fxml.FXML;

/** Embeds {@link Leaf} twice, as tags in its markup, and hears when one is picked. */
public final class Host extends FxmlNode<HostViewModel> {

    @FXML
    private Leaf first;
    @FXML
    private Leaf second;

    @FXML
    private void initialize() {
        signals().bind(LeafViewModelSignals.picked(), viewModel::leafPicked);
    }

    public Leaf first() {
        return first;
    }

    public Leaf second() {
        return second;
    }
}
