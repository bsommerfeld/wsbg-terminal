package de.bsommerfeld.wsbg.terminal.fx;

import javafx.fxml.FXML;

/** Embeds {@link Leaf} twice, as tags in its markup. */
public final class Host extends FxmlNode<HostViewModel> {

    @FXML
    private Leaf first;
    @FXML
    private Leaf second;

    public Leaf first() {
        return first;
    }

    public Leaf second() {
        return second;
    }
}
