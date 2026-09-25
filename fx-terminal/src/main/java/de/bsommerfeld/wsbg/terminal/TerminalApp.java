package de.bsommerfeld.wsbg.terminal;

import de.bsommerfeld.wsbg.terminal.chrome.Shell;
import de.bsommerfeld.wsbg.terminal.chrome.TitleBar;
import de.bsommerfeld.wsbg.terminal.dashboard.Dashboard;
import de.bsommerfeld.wsbg.terminal.fx.Fx;
import de.bsommerfeld.wsbg.terminal.ui.Fonts;
import de.bsommerfeld.wsbg.terminal.ui.Stylesheets;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.HeaderBar;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * The terminal window: an {@link StageStyle#EXTENDED extended} stage whose client
 * area runs up into the title bar, and the {@link Shell}: our own
 * {@link TitleBar} in that strip, and below it the view the
 * {@link TerminalViewRegister} shows - the {@link Dashboard} first.
 */
public final class TerminalApp extends Application {

    /** The frame colour, also the scene fill so a resize never flashes white. */
    private static final Color FRAME_COLOR = Color.web("#323130");

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 820;
    private static final int MIN_WIDTH = 800;
    private static final int MIN_HEIGHT = 600;

    static void main(String[] args) {
        launch(args);
    }

    /** Runs before {@link #start}; a test that calls {@code start} itself boots the injector on its own. */
    @Override
    public void init() {
        Fx.init();
    }

    @Override
    public void start(Stage stage) {
        Fonts.load();
        decorate(stage);

        stage.setScene(shellScene(stage));
        stage.show();
    }

    private static void decorate(Stage stage) {
        stage.initStyle(StageStyle.EXTENDED);
        stage.setTitle("WSBG Terminal");
        stage.getIcons().add(new Image(TerminalApp.class.getResource("icon/AppIcon-256.png").toExternalForm()));

        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        /*
         * The system-provided window buttons sit in a strip as tall as our title
         * bar, and take the dark scheme regardless of the OS appearance.
         */
        HeaderBar.setSystemButtonHeight(stage, TitleBar.HEIGHT);
        HeaderBar.setSystemColorScheme(stage, ColorScheme.DARK);
    }

    private static Scene shellScene(Stage stage) {
        Shell root = new Shell(stage);

        TerminalViewRegister views = new TerminalViewRegister(root::show);
        views.init();
        views.show(Dashboard.class);

        Scene scene = new Scene(root, WIDTH, HEIGHT, FRAME_COLOR);
        scene.getStylesheets().addAll(Stylesheets.all());
        return scene;
    }
}
