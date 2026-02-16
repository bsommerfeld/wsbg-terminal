package de.bsommerfeld.wsbg.terminal.ui;

/**
 * Non-Application entry point for classpath-mode launches.
 *
 * <p>
 * JavaFX's Application launcher uses {@code Class.forName} on the module layer,
 * which cannot find classes on the unnamed module (classpath). This wrapper
 * delegates
 * to {@link WsbgTerminalApp#main} from a non-Application class, bypassing that
 * lookup.
 */
public final class AppMain {

    public static void main(String[] args) {
        WsbgTerminalApp.main(args);
    }
}
