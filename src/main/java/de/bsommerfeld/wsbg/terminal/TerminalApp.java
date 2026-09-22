package de.bsommerfeld.wsbg.terminal;

import de.bsommerfeld.wsbg.terminal.chrome.TitleBar;
import de.bsommerfeld.wsbg.terminal.ui.Fonts;
import de.bsommerfeld.wsbg.terminal.ui.Stylesheets;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * The terminal window: an {@link StageStyle#EXTENDED extended} stage whose client
 * area runs up into the title bar, our own {@link TitleBar} in that strip, and
 * the content ground below it.
 */
public final class TerminalApp extends Application {

    /** The frame colour, also the scene fill so a resize never flashes white. */
    private static final Color FRAME = Color.web("#323130");

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Fonts.load();

        stage.initStyle(StageStyle.EXTENDED);
        stage.setTitle("WSBG Terminal");
        stage.getIcons().add(new Image(TerminalApp.class.getResource("icon/AppIcon-256.png").toExternalForm()));
        // The system-provided window buttons sit in a strip as tall as our title
        // bar, and take the dark scheme regardless of the OS appearance.
        HeaderBar.setSystemButtonHeight(stage, TitleBar.HEIGHT);
        HeaderBar.setSystemColorScheme(stage, ColorScheme.DARK);

        BorderPane root = new BorderPane();
        root.setTop(new TitleBar());
        root.setCenter(ground());

        Scene scene = new Scene(root, 1280, 820, FRAME);
        scene.getStylesheets().addAll(Stylesheets.all());
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.show();
    }

    /**
     * The content ground: 5px of frame around one empty island. Widgets will
     * take the island's place.
     */
    private static StackPane ground() {
        StackPane island = new StackPane();
        island.getStyleClass().add("island");
        StackPane main = new StackPane(island);
        main.getStyleClass().add("main");
        return main;
    }
}
