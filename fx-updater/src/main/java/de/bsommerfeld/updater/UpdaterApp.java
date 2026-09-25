package de.bsommerfeld.updater;

import de.bsommerfeld.tinyupdate.handoff.Handoff;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.HeaderBar;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.time.Duration;

/**
 * The updater application. Its command line is a {@link Handoff}; it shows
 * one small window, runs an {@link UpdateRun} behind it, and exits once the
 * application it updated is running again. A failed run stays on screen
 * until it is tried again or closed.
 */
public final class UpdaterApp extends Application {

    /** How long the application gets to exit after it handed off. */
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(60);

    private static final double WIDTH = 440;
    private static final double HEIGHT = 200;
    private static final Color BACKGROUND = Color.web("#2b2b2b");

    private Handoff handoff;
    private UpdaterView view;

    static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Messages messages = Messages.forDefaultLocale();

        Handoff parsed;
        try {
            parsed = Handoff.parse(getParameters().getRaw());
        } catch (IllegalArgumentException e) {
            System.err.println("[updater] Invalid handoff " + getParameters().getRaw() + ": " + e.getMessage());
            parsed = null;
        }
        handoff = parsed;

        String name = handoff == null ? "" : handoff.applicationName();
        view = new UpdaterView(messages, name, this::runInBackground, Platform::exit);

        stage.initStyle(StageStyle.EXTENDED);
        stage.setTitle(name);
        stage.setResizable(false);
        HeaderBar.setSystemColorScheme(stage, ColorScheme.DARK);

        Scene scene = new Scene(view, WIDTH, HEIGHT, BACKGROUND);
        scene.getStylesheets().add(UpdaterApp.class.getResource("updater.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        if (handoff == null) {
            view.showInvalid();
        } else {
            runInBackground();
        }
    }

    /** One pass off the FX thread; the window follows it and closes once the application runs again. */
    private void runInBackground() {
        Thread.ofVirtual().name("update-run").start(() -> {
            UpdateRun run = new UpdateRun(handoff, EXIT_TIMEOUT, status -> Platform.runLater(() -> view.show(status)));
            if (run.run()) {
                Platform.runLater(Platform::exit);
            }
        });
    }
}
