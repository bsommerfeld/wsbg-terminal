package de.bsommerfeld.updater;

import de.bsommerfeld.tinyupdate.api.UpdatePhase;
import de.bsommerfeld.tinyupdate.api.UpdateProgress;
import javafx.application.Platform;
import javafx.css.CssParser;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the updater window in its states at 2x into
 * {@code target/updater-<state>.png} and fails on stylesheet errors. Opt-in:
 * {@code UPDATER_SNAPSHOT=true}. The native window buttons are not part of
 * the images - they are the scene graph, not the screen.
 */
@Tag("visual")
@EnabledIfEnvironmentVariable(named = "UPDATER_SNAPSHOT", matches = "true")
class UpdaterSnapshotIT {

    @Test
    void rendersEveryState() throws Exception {
        Locale.setDefault(Locale.GERMANY);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CssParser.errorsProperty().clear();

        List<UpdateStatus> states = List.of(
                new UpdateStatus.Waiting(),
                new UpdateStatus.Updating(UpdateProgress.download(UpdatePhase.DOWNLOADING_UPDATE.token(), 1, 2, 0.42, 3_400_000)),
                new UpdateStatus.Failed(UpdateStatus.Failure.UPDATE, "Hash mismatch after update: lib/fx-terminal-1.2.0.jar"));

        CompletableFuture<Stage> shown = new CompletableFuture<>();
        CompletableFuture<UpdaterView> built = new CompletableFuture<>();
        Platform.runLater(() -> {
            UpdaterView view = new UpdaterView(Messages.forDefaultLocale(), "WSBG Terminal", () -> { }, () -> { });
            Scene scene = new Scene(view, 440, 200, Color.web("#2b2b2b"));
            scene.getStylesheets().add(UpdaterApp.class.getResource("updater.css").toExternalForm());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            built.complete(view);
            shown.complete(stage);
        });
        Stage stage = shown.get(10, TimeUnit.SECONDS);
        UpdaterView view = built.get();

        for (UpdateStatus state : states) {
            Platform.runLater(() -> view.show(state));
            Thread.sleep(400);
            CompletableFuture<WritableImage> image = new CompletableFuture<>();
            Platform.runLater(() -> {
                SnapshotParameters params = new SnapshotParameters();
                params.setTransform(Transform.scale(2, 2));
                image.complete(stage.getScene().getRoot().snapshot(params, null));
            });
            String name = state.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            Png.write(image.get(10, TimeUnit.SECONDS), Path.of("target", "updater-" + name + ".png"));
        }
        Platform.runLater(stage::hide);

        assertEquals(0, CssParser.errorsProperty().size(), CssParser.errorsProperty().toString());
    }
}
