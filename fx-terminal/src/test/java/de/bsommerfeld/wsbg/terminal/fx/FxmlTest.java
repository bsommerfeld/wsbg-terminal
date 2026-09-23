package de.bsommerfeld.wsbg.terminal.fx;

import de.bsommerfeld.wsbg.terminal.FxToolkit;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxmlTest {

    @BeforeAll
    static void boot() throws InterruptedException {
        FxToolkit.boot();
    }

    @Test
    void componentsEmbedAsTagsEachWithItsOwnModel() throws Exception {
        Host host = FxToolkit.onFxThread(Host::new);
        assertNotSame(host.first(), host.second());
        assertNotSame(host.first().viewModel(), host.second().viewModel());
        assertEquals("leaf", host.first().label().getText());
        assertTrue(host.getStyleClass().contains("host"), host.getStyleClass().toString());
        assertTrue(host.first().getStyleClass().contains("leaf"));
        assertTrue(host.first().getStylesheets().getFirst().endsWith("Leaf.css"));
        assertTrue(host.getStylesheets().isEmpty(), "Host has no stylesheet");
    }

    @Test
    void viewsAreOneInstance() throws Exception {
        Screen first = FxToolkit.onFxThread(() -> Fx.injector().getInstance(Screen.class));
        Screen second = FxToolkit.onFxThread(() -> Fx.injector().getInstance(Screen.class));
        assertSame(first, second);
    }

    @Test
    void componentsAreNewEachTime() throws Exception {
        Leaf first = FxToolkit.onFxThread(() -> Fx.injector().getInstance(Leaf.class));
        Leaf second = FxToolkit.onFxThread(() -> Fx.injector().getInstance(Leaf.class));
        assertNotSame(first, second);
    }

    @Test
    void modelHearsAttachAndDetach() throws Exception {
        Screen screen = FxToolkit.onFxThread(() -> Fx.injector().getInstance(Screen.class));
        int attachedBefore = screen.viewModel().attached();
        FxToolkit.onFxThread(() -> {
            Scene scene = new Scene(screen);
            scene.setRoot(new StackPane());
            return null;
        });
        assertEquals(attachedBefore + 1, screen.viewModel().attached());
        assertEquals(attachedBefore + 1, screen.viewModel().detached());
    }

    @Test
    void modelTypeIsReadThroughIntermediateClasses() throws Exception {
        Layered layered = FxToolkit.onFxThread(Layered::new);
        assertInstanceOf(LeafViewModel.class, layered.viewModel());
    }

    @Test
    void aComponentsSignalReachesTheNodeThatEmbedsIt() throws Exception {
        Host host = FxToolkit.onFxThread(Host::new);
        FxToolkit.onFxThread(() -> {
            host.first().viewModel().picked();
            host.second().viewModel().picked();
            return null;
        });
        assertEquals(2, host.viewModel().leavesPicked());
    }

    @Test
    void aSignalNobodyBindsFails() throws Exception {
        Leaf alone = FxToolkit.onFxThread(Leaf::new);
        var e = assertThrows(IllegalStateException.class, () -> FxToolkit.onFxThread(() -> {
            alone.viewModel().picked();
            return null;
        }));
        assertTrue(e.getMessage().contains("LeafViewModel#picked"), e.getMessage());
    }

    @Test
    void refusesANodeWithoutMarkup() {
        var e = assertThrows(IllegalStateException.class, () -> FxToolkit.onFxThread(Orphan::new));
        assertTrue(e.getMessage().contains("Orphan.fxml"), e.getMessage());
    }

    @Test
    void refusesMarkupThatNamesAController() {
        var e = assertThrows(IllegalStateException.class, () -> FxToolkit.onFxThread(Claimed::new));
        assertTrue(e.getMessage().contains("fx:controller"), e.getMessage());
    }

    @Test
    void refusesARawModelType() {
        var e = assertThrows(IllegalStateException.class, () -> FxToolkit.onFxThread(Vague::new));
        assertTrue(e.getMessage().contains("extends FxmlNode<"), e.getMessage());
    }

    static final class Orphan extends FxmlNode<LeafViewModel> {
    }

    /** Its markup carries fx:controller. */
    static final class Claimed extends FxmlNode<LeafViewModel> {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class Vague extends FxmlNode {
    }
}
