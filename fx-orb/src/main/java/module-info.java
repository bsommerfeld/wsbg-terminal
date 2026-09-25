/*
 * The orb renders through OpenGL via the FFM API, so the module needs native
 * access (--enable-native-access=de.bsommerfeld.wsbg.orb).
 */
module de.bsommerfeld.wsbg.orb {
    requires javafx.controls;

    exports de.bsommerfeld.wsbg.orb;
}
