package de.bsommerfeld.wsbg.terminal;

import de.bsommerfeld.wsbg.terminal.chrome.Shell;
import de.bsommerfeld.wsbg.terminal.chrome.TitleBar;
import de.bsommerfeld.wsbg.terminal.dashboard.Dashboard;
import de.bsommerfeld.wsbg.terminal.fx.Fx;
import de.bsommerfeld.wsbg.terminal.intro.Intro;
import de.bsommerfeld.wsbg.terminal.ui.Fonts;
import de.bsommerfeld.wsbg.terminal.ui.Stylesheets;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.Locale;

/**
 * The terminal window: an {@link StageStyle#EXTENDED extended} stage whose client
 * area runs up into the title bar, and the {@link Shell}: our own
 * {@link TitleBar} in that strip, and below it the view the
 * {@link TerminalViewRegister} shows - the {@link Dashboard} first. The
 * {@link Intro} plays over all of it on startup.
 */
public final class TerminalApp extends Application {

    /** The frame colour, also the scene fill so a resize never flashes white. */
    private static final Color FRAME_COLOR = Color.web("#323130");

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 820;
    private static final int MIN_WIDTH = 800;
    private static final int MIN_HEIGHT = 600;

    /**
     * {@code -Dwsbg.intro=false} starts straight into the shell - for the shell
     * snapshot; {@code -Dwsbg.intro=loop} plays the intro over and over, to
     * look at it.
     */
    private static final String INTRO_PROPERTY = "wsbg.intro";

    /**
     * {@code -Dwsbg.intro.ending=settle|dive} picks how the intro hands over.
     */
    private static final String ENDING_PROPERTY = "wsbg.intro.ending";

    /**
     * The window icon in every size .script/build-icons.py writes; each platform
     * takes the one closest to what it draws (title bar, taskbar, Alt+Tab). The
     * macOS Dock ignores these - there it is the installed bundle's icon.
     */
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

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

        Shell shell = new Shell(stage);
        stage.setScene(shellScene(stage, shell));
        stage.show();
    }

    private static void decorate(Stage stage) {
        stage.initStyle(StageStyle.EXTENDED);
        stage.setTitle("WSBG Terminal");
        for (int size : ICON_SIZES) {
            stage.getIcons().add(new Image(TerminalApp.class.getResource("icon/AppIcon-" + size + ".png").toExternalForm()));
        }

        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        /*
         * The system-provided window buttons sit in a strip as tall as our title
         * bar, and take the dark scheme regardless of the OS appearance.
         */
        HeaderBar.setSystemButtonHeight(stage, TitleBar.HEIGHT);
        HeaderBar.setSystemColorScheme(stage, ColorScheme.DARK);
    }

    private static Scene shellScene(Stage stage, Shell shell) {
        TerminalViewRegister views = new TerminalViewRegister(shell::show);
        views.init();
        views.show(Dashboard.class);

        StackPane root = new StackPane(shell);
        if (wantsIntro()) {
            root.getChildren().add(intro(stage, root, shell));
        }

        Scene scene = new Scene(root, WIDTH, HEIGHT, FRAME_COLOR);
        scene.getStylesheets().addAll(Stylesheets.all());
        return scene;
    }

    /** Not with reduced motion - an intro made of motion has nothing to offer there. */
    private static boolean wantsIntro() {
        return !"false".equals(System.getProperty(INTRO_PROPERTY))
                && !Platform.getPreferences().isReducedMotion();
    }

    /** The intro, with the system window buttons away while it covers the title bar. */
    private static Intro intro(Stage stage, StackPane root, Shell shell) {
        Intro intro = new Intro(shell, ending());
        HeaderBar.setSystemButtonHeight(stage, 0);
        intro.setOnFinished(() -> {
            HeaderBar.setSystemButtonHeight(stage, TitleBar.HEIGHT);
            if ("loop".equals(System.getProperty(INTRO_PROPERTY))) {
                PauseTransition pause = new PauseTransition(Duration.seconds(1));
                pause.setOnFinished(_ -> {
                    Intro again = intro(stage, root, shell);
                    root.getChildren().add(again);
                    again.play();
                });
                pause.play();
            }
        });
        stage.setOnShown(_ -> intro.play());
        return intro;
    }

    private static Intro.Ending ending() {
        return Intro.Ending.valueOf(System.getProperty(ENDING_PROPERTY, "settle").toUpperCase(Locale.ROOT));
    }
}
