package de.bsommerfeld.wsbg.terminal.ui;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Guice;
import com.google.inject.Injector;
import de.bsommerfeld.wsbg.terminal.core.config.ApplicationMode;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.agent.OllamaServerManager;
import de.bsommerfeld.wsbg.terminal.ui.config.AppModule;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;


/**
 * JavaFX entry point. Sets up DI, loads the dashboard view into an
 * extended-style window with a custom {@code HeaderBar}, and subscribes
 * to {@link UiEvents.TerminalBlinkEvent} for the graph toggle pulse.
 *
 * <p>
 * Uses {@code StageStyle.EXTENDED} for native window controls, resize,
 * and snap layouts on all platforms. The custom header bar overlay from
 * jfx-frameless is replaced by the native {@code HeaderBar} API.
 */
public class WsbgTerminalApp extends Application {


    private static final Logger LOG = LoggerFactory.getLogger(WsbgTerminalApp.class);

    private Injector injector;
    private Button graphToggleButton;
    private FadeTransition terminalBlinkAnim;

    @Override
    public void init() {
        LOG.info("Initializing...");
        loadFont("/fonts/FiraCode-Regular.ttf");
        loadFont("/fonts/FiraCode-Bold.ttf");
        loadFont("/fonts/FiraCode-Retina.ttf");

        this.injector = Guice.createInjector(new AppModule());
    }

    @Override
    public void start(Stage primaryStage) {
        LOG.info("Starting UI...");
        primaryStage.setTitle("WSBG TERMINAL");
        primaryStage.getIcons().add(new Image(
                getClass().getResourceAsStream("/images/app-icon.png")));

        try {
            Parent content = loadDashboard();
            showWindow(primaryStage, content);

            LOG.info("WSBG-TERMINAL BEREIT. (MODE: {})", ApplicationMode.get());

            injector.getInstance(ApplicationEventBus.class).register(this);
        } catch (IOException e) {
            LOG.error("Failed to load Dashboard View", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop() throws Exception {
        LOG.info("Stopping...");
        shutdownRepository();
        super.stop();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }

    // ── Terminal Blink ──────────────────────────────────────────────

    /** Starts or stops the graph-toggle blink animation on the FX thread. */
    @Subscribe
    public void onTerminalBlink(UiEvents.TerminalBlinkEvent event) {
        Platform.runLater(() -> {
            if (event.active()) {
                startTerminalBlink();
            } else {
                stopTerminalBlink();
            }
        });
    }

    // ── Private helpers ─────────────────────────────────────────────

    private void loadFont(String path) {
        try {
            Font font = Font.loadFont(getClass().getResourceAsStream(path), 10);
            if (font == null) {
                LOG.warn("Failed to load font: {}", path);
            } else {
                LOG.info("Loaded font: Name='{}', Family='{}'", font.getName(), font.getFamily());
            }
        } catch (Exception e) {
            LOG.error("Error loading font: " + path, e);
        }
    }

    private Parent loadDashboard() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/DashboardView.fxml"));
        loader.setControllerFactory(injector::getInstance);
        return loader.load();
    }

    /**
     * Builds the window using StageStyle.EXTENDED — native window controls,
     * resize, and snap layouts on all platforms. The HeaderBar is injected
     * by TitleBarFactory into leading/center/trailing slots.
     */
    private void showWindow(Stage stage, Parent content) {
        stage.initStyle(StageStyle.EXTENDED);

        ApplicationEventBus eventBus = injector.getInstance(ApplicationEventBus.class);
        GlobalConfig config = injector.getInstance(GlobalConfig.class);
        I18nService i18n = injector.getInstance(I18nService.class);

        this.graphToggleButton = TitleBarFactory.buildHeaderBar(stage, eventBus, config, i18n);

        BorderPane root = (BorderPane) stage.getScene().getRoot();
        root.setCenter(content);

        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setWidth(1200);
        stage.setHeight(800);
        stage.show();
    }

    private void shutdownRepository() {
        if (injector == null)
            return;
        try {
            injector.getInstance(RedditRepository.class).shutdown();
            injector.getInstance(AgentRepository.class).shutdown();
            injector.getInstance(OllamaServerManager.class).shutdown();
        } catch (Exception e) {
            LOG.warn("Failed to shutdown repositories: " + e.getMessage());
        }
    }

    private void startTerminalBlink() {
        if (graphToggleButton == null)
            return;

        graphToggleButton.getGraphic().getStyleClass().add("icon-terminal-blink");

        if (terminalBlinkAnim != null)
            terminalBlinkAnim.stop();

        terminalBlinkAnim = new FadeTransition(Duration.millis(500), graphToggleButton);
        terminalBlinkAnim.setFromValue(1.0);
        terminalBlinkAnim.setToValue(0.3);
        terminalBlinkAnim.setCycleCount(Animation.INDEFINITE);
        terminalBlinkAnim.setAutoReverse(true);
        terminalBlinkAnim.play();
    }

    private void stopTerminalBlink() {
        if (terminalBlinkAnim != null) {
            terminalBlinkAnim.stop();
            terminalBlinkAnim = null;
        }
        if (graphToggleButton != null) {
            graphToggleButton.setOpacity(1.0);
            graphToggleButton.getGraphic().getStyleClass().remove("icon-terminal-blink");
        }
    }
}
