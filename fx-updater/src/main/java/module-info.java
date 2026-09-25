/**
 * The updater: started by an application with a TinyUpdate handoff, it waits
 * for the application to exit, applies the update in a small window, and
 * starts the application again.
 */
module de.bsommerfeld.updater {
    requires javafx.controls;
    requires de.bsommerfeld.tinyupdate;

    exports de.bsommerfeld.updater;
}
