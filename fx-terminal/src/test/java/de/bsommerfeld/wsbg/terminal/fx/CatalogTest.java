package de.bsommerfeld.wsbg.terminal.fx;

import de.bsommerfeld.wsbg.terminal.FxToolkit;
import de.bsommerfeld.wsbg.terminal.ui.Stylesheets;
import javafx.css.CssParser;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds every node in the application, puts it into a scene and
 * applies its CSS on top of the application's stylesheets: a missing FXML, a broken stylesheet or a model the injector
 * cannot build fails here, not at the first click.
 */
class CatalogTest {

    @Test
    void everyNodeLoads() throws Exception {
        FxToolkit.boot();
        List<Class<? extends FxmlNode<?>>> nodes = Catalog.all();
        assertTrue(nodes.stream().anyMatch(t -> t.getSimpleName().equals("Dashboard")), "catalog: " + nodes);

        var errors = CssParser.errorsProperty();
        for (Class<? extends FxmlNode<?>> type : nodes) {
            long started = System.nanoTime();
            FxToolkit.onFxThread(() -> {
                errors.clear();
                FxmlNode<?> node = Fx.injector().getInstance(type);
                Scene scene = new Scene(node);
                scene.getStylesheets().addAll(Stylesheets.all());
                node.applyCss();
                scene.setRoot(new StackPane());
                return null;
            });
            System.out.printf("[fxml] %-24s %6.1f ms%n", type.getSimpleName(), (System.nanoTime() - started) / 1e6);
            assertEquals(0, errors.size(), type.getSimpleName() + ": " + errors);
        }
    }
}
