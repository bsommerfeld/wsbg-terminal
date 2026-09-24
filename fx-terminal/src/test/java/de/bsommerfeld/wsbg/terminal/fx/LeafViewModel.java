package de.bsommerfeld.wsbg.terminal.fx;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class LeafViewModel implements ViewModel {

    private final StringProperty text = new SimpleStringProperty("leaf");

    public StringProperty text() {
        return text;
    }
}
