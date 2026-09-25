package de.bsommerfeld.wsbg.terminal;

import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Plays the startup intro in a real window and writes what the scene shows
 * every {@value #STEP_MS} ms to {@code target/intro/} - a filmstrip for the eye,
 * named by the ms since the window opened. Opt-in: {@code INTRO_SNAPSHOT=true};
 * {@code INTRO_ENDING=settle|dive} films that ending.
 */
@Tag("visual")
@EnabledIfEnvironmentVariable(named = "INTRO_SNAPSHOT", matches = "true")
class IntroSnapshotIT {

    private static final int STEP_MS = 100;
    private static final int LENGTH_MS = 5600;

    @Test
    void filmsTheIntro() throws Exception {
        String ending = System.getenv("INTRO_ENDING");
        if (ending != null) {
            System.setProperty("wsbg.intro.ending", ending);
        }
        FxToolkit.boot();

        CompletableFuture<Stage> shown = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            new TerminalApp().start(stage);
            shown.complete(stage);
        });
        Stage stage = shown.get(10, TimeUnit.SECONDS);
        long opened = System.nanoTime();

        List<CompletableFuture<Void>> frames = new ArrayList<>();
        for (int at = 0; at <= LENGTH_MS; at += STEP_MS) {
            long wait = opened + at * 1_000_000L - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000L);
            }
            CompletableFuture<WritableImage> image = new CompletableFuture<>();
            Platform.runLater(() -> image.complete(stage.getScene().getRoot().snapshot(new SnapshotParameters(), null)));
            WritableImage frame = image.get(10, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - opened) / 1_000_000L;
            frames.add(CompletableFuture.runAsync(() -> {
                try {
                    Png.write(frame, Path.of("target", "intro", String.format("%05d.png", ms)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        CompletableFuture.allOf(frames.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        System.out.println("[intro] frames in " + Path.of("target", "intro").toAbsolutePath());
        Platform.runLater(stage::hide);
    }
}
