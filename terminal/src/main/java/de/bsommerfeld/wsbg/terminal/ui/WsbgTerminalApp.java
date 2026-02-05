package de.bsommerfeld.wsbg.terminal.ui;

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
import de.bsommerfeld.wsbg.terminal.ui.view.dock.DockPanel;
import de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets.FinancialJuiceWidget;
import de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets.RedditHeadlineWidget;
import de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets.TickerChartWidget;
import de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets.WidgetRegistry;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX entry point. Sets up DI and launches the DockPanel-based
 * workstation inside an EXTENDED-style window.
 *
 * <p>
 * Uses {@code StageStyle.EXTENDED} for native window controls, resize,
 * and snap layouts on all platforms.
 */
public class WsbgTerminalApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(WsbgTerminalApp.class);

    private Injector injector;

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
            showWindow(primaryStage);
            LOG.info("WSBG-TERMINAL BEREIT. (MODE: {})", ApplicationMode.get());
        } catch (Throwable e) {
            LOG.error("Failed to start application", e);
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop() throws Exception {
        LOG.info("Stopping...");
        shutdownServices();
        super.stop();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);

        // Prevent JVM from exiting if launch() returns immediately under
        // -Djavafx.embed.singleThread=true
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
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

    /**
     * Builds an EXTENDED window with custom traffic light buttons and
     * the DockPanel as sole content. Uses custom HeaderButtonType
     * buttons because EXTENDED mode renders native buttons too small.
     */
    private void showWindow(Stage stage) {
        stage.initStyle(StageStyle.EXTENDED);

        ApplicationEventBus eventBus = injector.getInstance(ApplicationEventBus.class);
        GlobalConfig config = injector.getInstance(GlobalConfig.class);
        I18nService i18n = injector.getInstance(I18nService.class);
        AgentRepository agentRepository = injector.getInstance(AgentRepository.class);

        BorderPane root = TitleBarFactory.buildHeaderBar(stage, eventBus, config, i18n);

        WidgetRegistry.register(FinancialJuiceWidget.IDENTIFIER, () -> new FinancialJuiceWidget(i18n));
        WidgetRegistry.register(TickerChartWidget.IDENTIFIER, () -> new TickerChartWidget(eventBus, i18n));
        WidgetRegistry.register(RedditHeadlineWidget.IDENTIFIER, () -> new RedditHeadlineWidget(agentRepository, i18n));

        DockPanel dockPanel = new DockPanel();
        dockPanel.addWidget(new RedditHeadlineWidget(agentRepository, i18n));
        dockPanel.addWidget(new FinancialJuiceWidget(i18n));
        dockPanel.addWidget(new TickerChartWidget(eventBus, i18n));
        root.setCenter(dockPanel);

        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setWidth(1200);
        stage.setHeight(800);
        stage.show();
    }

    private void shutdownServices() {
        if (injector == null)
            return;
        try {
            injector.getInstance(RedditRepository.class).shutdown();
            injector.getInstance(AgentRepository.class).shutdown();
            injector.getInstance(OllamaServerManager.class).shutdown();
            injector.getInstance(UserSessionTracker.class).shutdown();
        } catch (Exception e) {
            LOG.warn("Failed to shutdown services: " + e.getMessage());
        }
    }
}
