package de.bsommerfeld.wsbg.terminal;

import javafx.application.Platform;
import javafx.css.CssParser;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HeaderBar;
import javafx.scene.text.Font;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens the shell in a real window, renders the scene at 2x into
 * {@code target/shell.png} and fails on any stylesheet error the toolkit
 * reported while applying it. Opt-in: {@code SHELL_SNAPSHOT=true} (see
 * .script/snapshot.sh). The native window buttons are not part of the image -
 * it is the scene graph, not the screen.
 */
@Tag("visual")
@EnabledIfEnvironmentVariable(named = "SHELL_SNAPSHOT", matches = "true")
class ShellSnapshotIT {

    @Test
    void rendersTheShell() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(10, TimeUnit.SECONDS), "toolkit did not start");
        var errors = CssParser.errorsProperty();
        errors.clear();

        CompletableFuture<Stage> shown = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            new TerminalApp().start(stage);
            shown.complete(stage);
        });
        Stage stage = shown.get(10, TimeUnit.SECONDS);
        Thread.sleep(1500);

        CompletableFuture<WritableImage> image = new CompletableFuture<>();
        Platform.runLater(() -> {
            System.out.println("[snapshot] fonts: " + Font.getFamilies().stream()
                    .filter(f -> f.startsWith("Inter") || f.startsWith("JetBrains")).toList());
            System.out.println("[snapshot] left system inset: " + HeaderBar.getLeftSystemInset(stage)
                    + ", right: " + HeaderBar.getRightSystemInset(stage)
                    + ", system min height: " + HeaderBar.getSystemMinHeight(stage));
            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(Transform.scale(2, 2));
            image.complete(stage.getScene().getRoot().snapshot(params, null));
        });
        Path out = Path.of("target", "shell.png");
        Png.write(image.get(10, TimeUnit.SECONDS), out);
        System.out.println("[snapshot] written " + out.toAbsolutePath());
        Platform.runLater(Platform::exit);

        assertEquals(0, errors.size(), errors.toString());
    }
}
