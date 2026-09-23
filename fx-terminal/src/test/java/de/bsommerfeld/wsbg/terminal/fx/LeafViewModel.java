package de.bsommerfeld.wsbg.terminal.fx;

import de.bsommerfeld.signals.Signal;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class LeafViewModel implements ViewModel {

    private final StringProperty text = new SimpleStringProperty("leaf");

    public StringProperty text() {
        return text;
    }

    @Signal
    void picked() {
    }
}
