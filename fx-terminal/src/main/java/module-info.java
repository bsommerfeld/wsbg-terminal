/*
 * An open module: every package is open for reflection, because FXMLLoader
 * fills @FXML fields and Guice calls constructors in all of them, and a list
 * of `opens` per package would only ever be maintained late.
 */
open module de.bsommerfeld.wsbg.terminal {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.guice;
    requires de.bsommerfeld.signals;

    exports de.bsommerfeld.wsbg.terminal;
}
