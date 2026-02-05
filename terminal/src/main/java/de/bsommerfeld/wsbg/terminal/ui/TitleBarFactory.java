package de.bsommerfeld.wsbg.terminal.ui;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.OperatingSystem;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HeaderBar;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * Builds the HeaderBar for StageStyle.EXTENDED windows.
 *
 * <p>
 * The native macOS traffic light buttons are a fixed system-defined
 * size (~12pt). They cannot be resized by any API — this is consistent
 * across all macOS apps. Native SwiftUI apps (like Ollama) appear to
 * have larger buttons because they use an NSToolbar, which increases
 * the title bar height and visual padding around the buttons. JavaFX
 * does not expose NSToolbar integration, so we accept the standard
 * compact title bar appearance.
 */
@SuppressWarnings("deprecation")
final class TitleBarFactory {

    // macOS compact title bar standard (matches Discord, Terminal.app)
    private static final double MACOS_BUTTON_HEIGHT = 38;

    // Windows standard title bar height (matches Explorer, VS Code)
    private static final double WINDOWS_BUTTON_HEIGHT = 32;

    private TitleBarFactory() {
    }

    /**
     * Creates the HeaderBar with native system buttons, sets up
     * the Scene with a BorderPane root, and attaches the stylesheet.
     */
    static BorderPane buildHeaderBar(Stage stage, ApplicationEventBus eventBus,
            GlobalConfig config, I18nService i18n) {
        HeaderBar headerBar = new HeaderBar();

        // setPrefButtonHeight is platform-dependent — on macOS it has
        // no visible effect on the native traffic lights, but on
        // Windows it controls the actual caption button height.
        double buttonHeight = switch (OperatingSystem.current()) {
            case MACOS -> MACOS_BUTTON_HEIGHT;
            case WINDOWS -> WINDOWS_BUTTON_HEIGHT;
            case LINUX -> HeaderBar.USE_DEFAULT_SIZE;
        };
        HeaderBar.setPrefButtonHeight(stage, buttonHeight);

        BorderPane root = new BorderPane();
        root.setTop(headerBar);

        Scene scene = new Scene(root);
        scene.setFill(Color.web("#171717"));

        scene.getStylesheets().add(
                TitleBarFactory.class.getResource("/css/index.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("");

        return root;
    }
}
