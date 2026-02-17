package de.bsommerfeld.wsbg.terminal.ui;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Guice;
import com.google.inject.Injector;
import de.bsommerfeld.jfx.frameless.WindowShellBuilder;
import de.bsommerfeld.wsbg.terminal.core.config.ApplicationMode;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.ui.config.AppModule;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JavaFX entry point. Sets up DI, loads the dashboard view into a
 * frameless {@link de.bsommerfeld.jfx.frameless.WindowShell}, and
 * injects the custom title bar overlay.
 *
 * <p>
 * Also subscribes to {@link UiEvents.TerminalBlinkEvent} to
 * pulse the graph toggle button when an AI analysis is in progress.
 */
public class WsbgTerminalApp extends Application {

    static {
        Path logDir = StorageUtils.getLogsDir("wsbg-terminal");
        try {
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            System.setProperty("LOG_DIR", logDir.toAbsolutePath().toString());
        } catch (Exception e) {
            System.err.println("Failed to create log directory: " + logDir);
            e.printStackTrace();
        }
    }

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
            Parent root = loadDashboard();
            showWindow(primaryStage, root);
            injectTitleBar(primaryStage);

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

    private void showWindow(Stage stage, Parent content) {
        WindowShellBuilder
                .create(stage)
                .defaultTitleBar()
                .resizable()
                .minSize(800, 600)
                .size(1200, 800)
                .cornerRadius(16)
                .content(content)
                .stylesheet(getClass().getResource("/css/index.css").toExternalForm())
                .build()
                .show();
    }

    private void injectTitleBar(Stage stage) {
        ApplicationEventBus eventBus = injector.getInstance(ApplicationEventBus.class);
        GlobalConfig config = injector.getInstance(GlobalConfig.class);

        Platform.runLater(() -> {
            try {
                this.graphToggleButton = TitleBarFactory.inject(stage, eventBus, config);
            } catch (Exception e) {
                LOG.error("Failed to inject title bar", e);
            }
        });
    }

    private void shutdownRepository() {
        if (injector == null)
            return;
        try {
            injector.getInstance(RedditRepository.class).shutdown();
        } catch (Exception e) {
            LOG.warn("Failed to shutdown RedditRepository: " + e.getMessage());
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
