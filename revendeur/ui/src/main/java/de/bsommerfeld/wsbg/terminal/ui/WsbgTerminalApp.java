package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Guice;
import com.google.inject.Injector;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import javafx.scene.layout.Pane;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.SearchEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.SearchNextEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ClearTerminalEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.jfx.frameless.WindowShell;
import de.bsommerfeld.jfx.frameless.WindowShellBuilder;
import de.bsommerfeld.wsbg.terminal.ui.config.AppModule;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import java.util.function.Function;
import javafx.scene.layout.Region;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ToggleRedditPanelEvent;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import java.nio.file.Files;
import java.nio.file.Paths;

public class WsbgTerminalApp extends Application {

    static {
        // Initialize Logging Directory via StorageUtils
        java.nio.file.Path logDir = StorageUtils.getLogsDir("wsbg-terminal");
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
    private double xOffset = 0;
    private double yOffset = 0;
    private javafx.scene.layout.Region sidebarToggleIcon;
    private boolean lastSearchHadResults = false;

    @Override
    public void init() throws Exception {
        LOG.info("Initializing...");
        // Load custom fonts
        loadFont("/fonts/FiraCode-Regular.ttf");
        loadFont("/fonts/FiraCode-Bold.ttf");
        loadFont("/fonts/FiraCode-Retina.ttf");

        // Initialize Guice Injector
        // Initialize Guice Injector
        this.injector = Guice.createInjector(new AppModule());
    }

    private void loadFont(String path) {
        try {
            javafx.scene.text.Font font = javafx.scene.text.Font.loadFont(getClass().getResourceAsStream(path), 10);
            if (font == null) {
                LOG.warn("Failed to load font: {}", path);
            } else {
                LOG.info("Loaded font: Name='{}', Family='{}'", font.getName(), font.getFamily());
            }
        } catch (Exception e) {
            LOG.error("Error loading font: " + path, e);
        }
    }

    @Override
    public void start(Stage primaryStage) {
        LOG.info("Starting UI...");
        primaryStage.setTitle("WSBG TERMINAL");

        try {
            // Load FXML
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/DashboardView.fxml"));
            loader.setControllerFactory(injector::getInstance);
            Parent root = loader.load();

            ApplicationEventBus eventBus = injector
                    .getInstance(ApplicationEventBus.class);

            // Initialize WindowShell with JFX-Frameless
            WindowShell window = WindowShellBuilder
                    .create(primaryStage)
                    .defaultTitleBar()
                    .resizable()
                    .minSize(800, 600)
                    .size(1200, 800)
                    .cornerRadius(16)
                    .content(root)
                    .stylesheet(getClass().getResource("/css/index.css").toExternalForm())
                    .build();

            window.show();

            // Hack: Inject ASCII Clear Button into Title Bar
            // Inject Search Bar & Clear Button using proper TitleBar Layout if possible,
            // otherwise use a clean overlay approach that doesn't duplicate buttons.
            Platform.runLater(() -> {
                try {
                    // --- 1. Create Search Box ---
                    HBox searchBox = new HBox();
                    searchBox.getStyleClass().add("title-search-box");
                    searchBox.setAlignment(Pos.CENTER_LEFT);

                    TextField searchField = new TextField();
                    ResourceBundle bundle = ResourceBundle.getBundle("i18n.messages");
                    searchField.setPromptText(bundle.getString("ui.search.prompt"));
                    searchField.getStyleClass().add("title-search-field");

                    HBox.setHgrow(searchField, Priority.ALWAYS);

                    Button clearSearchBtn = new Button("x");
                    clearSearchBtn.getStyleClass().add("title-search-clear-btn");
                    clearSearchBtn.setVisible(false);

                    // Search Logic
                    searchField.textProperty().addListener((obs, oldVal, newVal) -> {
                        boolean hasText = newVal != null && !newVal.isEmpty();
                        clearSearchBtn.setVisible(hasText);
                        injector.getInstance(ApplicationEventBus.class)
                                .post(new SearchEvent(newVal));
                    });

                    // Enter Key -> Next Result
                    searchField.setOnAction(e -> {
                        injector.getInstance(ApplicationEventBus.class)
                                .post(new SearchNextEvent());
                    });

                    clearSearchBtn.setOnAction(e -> {
                        searchField.clear();
                    });

                    searchBox.getChildren().addAll(searchField, clearSearchBtn);

                    // --- 2. Locate and Clear Title Bar ---
                    Node titleBarNode = primaryStage.getScene().lookup(".title-bar");

                    if (titleBarNode instanceof javafx.scene.layout.Pane) {
                        javafx.scene.layout.Pane bar = (javafx.scene.layout.Pane) titleBarNode;

                        // NUCLEAR OPTION: Clear everything to remove "muddy" legacy controls
                        bar.getChildren().clear();

                        // --- 3. Create Custom Controls (Unconditionally) ---
                        HBox customControls = new HBox(8);
                        customControls.setAlignment(Pos.CENTER);
                        customControls.getStyleClass().add("macos-controls-box");

                        Function<String, Button> createBtn = (type) -> {
                            Button btn = new Button();
                            btn.getStyleClass().addAll("custom-traffic-light", type);
                            Region icon = new Region();
                            icon.getStyleClass().add("traffic-light-icon");
                            icon.setMouseTransparent(true);
                            btn.setGraphic(icon);
                            return btn;
                        };

                        Button closeBtn = createBtn.apply("close");
                        closeBtn.setOnAction(e -> primaryStage.close());
                        Button minBtn = createBtn.apply("minimize");
                        minBtn.setOnAction(e -> primaryStage.setIconified(true));
                        Button maxBtn = createBtn.apply("maximize");
                        maxBtn.setOnAction(e -> primaryStage.setMaximized(!primaryStage.isMaximized()));

                        customControls.getChildren().addAll(closeBtn, minBtn, maxBtn);

                        // --- 4. Setup Layout (Auto-Expanding Regions for Perfect Centering) ---
                        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");

                        HBox mainLayout = new HBox();
                        mainLayout.setAlignment(Pos.CENTER);
                        mainLayout.setPickOnBounds(false);
                        // Bind directly to bar size
                        mainLayout.prefWidthProperty().bind(bar.widthProperty());
                        mainLayout.prefHeightProperty().bind(bar.heightProperty());

                        // LEFT CONTAINER (Grows to push center)
                        HBox leftContainer = new HBox();
                        leftContainer.setAlignment(Pos.CENTER_LEFT);
                        leftContainer.setMinWidth(0);
                        leftContainer.setPrefWidth(1); // Force minimal pref so grow logic dominates
                        leftContainer.setPickOnBounds(false);
                        HBox.setHgrow(leftContainer, Priority.ALWAYS);

                        // RIGHT CONTAINER (Grows to push center)
                        HBox rightContainer = new HBox();
                        rightContainer.setAlignment(Pos.CENTER_RIGHT);
                        rightContainer.setMinWidth(0);
                        rightContainer.setPrefWidth(1); // Force minimal pref so grow logic dominates
                        rightContainer.setPickOnBounds(false);
                        HBox.setHgrow(rightContainer, Priority.ALWAYS);

                        // Define Broom Button Creator
                        Function<Void, Button> createBroom = (v) -> {
                            Button btn = new Button();
                            btn.getStyleClass().add("ascii-button");
                            Region broomIcon = new Region();
                            broomIcon.getStyleClass().add("icon-broom");
                            btn.setGraphic(broomIcon);
                            btn.setOnAction(e -> {
                                injector.getInstance(ApplicationEventBus.class).post(new ClearTerminalEvent());
                            });
                            return btn;
                        };

                        // Define Toggle Button Creator
                        Function<Injector, Button> createToggleBtn = (inj) -> {
                            GlobalConfig config = inj.getInstance(GlobalConfig.class);
                            Button btn = new Button();
                            btn.getStyleClass().add("ascii-button");
                            Region icon = new Region();

                            // Initial State
                            boolean isVisible = config.isRedditListVisible();
                            // If Visible -> Show Close Icon (>)
                            // If Hidden -> Show Open Icon (<)
                            icon.getStyleClass().add(isVisible ? "icon-panel-close" : "icon-panel-open");
                            // Capture reference for highlighting logic
                            this.sidebarToggleIcon = icon;
                            btn.setGraphic(icon);

                            btn.setOnAction(e -> {
                                boolean newState = !config.isRedditListVisible();
                                config.setRedditListVisible(newState);

                                // Update Icon
                                icon.getStyleClass().clear();
                                icon.getStyleClass().add(newState ? "icon-panel-close" : "icon-panel-open");

                                // Post Event
                                inj.getInstance(ApplicationEventBus.class).post(new ToggleRedditPanelEvent(newState));

                                // Save Config
                                try {
                                    config.save();
                                } catch (Exception ex) {
                                    LOG.error("Failed to save config", ex);
                                }
                            });
                            return btn;
                        };

                        // Create Utility Controls Wrapper (Broom + Toggle)
                        HBox utilityControls = new HBox(0);
                        utilityControls.getStyleClass().add("utility-controls-box");
                        utilityControls.setAlignment(Pos.CENTER);

                        Button broomBtn = createBroom.apply(null);
                        Button toggleBtn = createToggleBtn.apply(injector);

                        utilityControls.getChildren().addAll(broomBtn, toggleBtn);

                        if (isMac) {
                            // Mac: Traffic Lights Left (with Margin), Utilities Right
                            HBox leftWrapper = new HBox(customControls);
                            HBox.setMargin(leftWrapper, new Insets(0, 0, 0, 13)); // Standard macOS left margin
                            leftContainer.getChildren().add(leftWrapper);

                            // Right margin for window edge - reduced
                            HBox.setMargin(utilityControls, new Insets(0, 4, 0, 0));
                            rightContainer.getChildren().add(utilityControls);

                        } else {
                            // Non-Mac: Utilities Left, Traffic Lights Right

                            // Left margin
                            HBox.setMargin(utilityControls, new Insets(0, 0, 0, 10));
                            leftContainer.getChildren().add(utilityControls);

                            rightContainer.getChildren().add(customControls);
                        }

                        // SEARCH CONTAINER (Center, static width)
                        // searchBox is already configured above
                        searchBox.setMaxWidth(450);

                        // ASSEMBLE: [Left(Grow)] [Search] [Right(Grow)]
                        mainLayout.getChildren().addAll(leftContainer, searchBox, rightContainer);

                        // Add to Bar
                        bar.getChildren().add(mainLayout);
                        primaryStage.setTitle("");

                    } else {
                        LOG.warn("TitleBar not found or unknown type");
                    }
                } catch (Exception e) {
                    LOG.error("Failed to inject search bar", e);
                }
            });

            LOG.info("WSBG-TERMINAL BEREIT.");

            // Register App for Events (Icon Highlighting)
            eventBus.register(this);

        } catch (IOException e) {
            LOG.error("Failed to load Dashboard View", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop() throws Exception {
        LOG.info("Stopping...");
        super.stop();
        System.exit(0);
    }

    private void ensureStyleClass(Node node, String styleClass) {
        if (!node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    @com.google.common.eventbus.Subscribe
    public void onRedditSearchResults(
            de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.RedditSearchResultsEvent event) {
        javafx.application.Platform.runLater(() -> {
            this.lastSearchHadResults = event.hasResults;
            updateSidebarIconHighlight();
        });
    }

    @com.google.common.eventbus.Subscribe
    public void onToggleRedditPanel(
            de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ToggleRedditPanelEvent event) {
        javafx.application.Platform.runLater(() -> {
            // Event provides new visibility state
            updateSidebarIconHighlight();
        });
    }

    private void updateSidebarIconHighlight() {
        if (sidebarToggleIcon == null || injector == null)
            return;

        GlobalConfig config = injector.getInstance(GlobalConfig.class);
        boolean isClosed = !config.isRedditListVisible();

        // Condition: Closed AND Has Results -> Apply Highlight
        if (isClosed && lastSearchHadResults) {
            if (!sidebarToggleIcon.getStyleClass().contains("icon-search-match")) {
                sidebarToggleIcon.getStyleClass().add("icon-search-match");
            }
        } else {
            sidebarToggleIcon.getStyleClass().remove("icon-search-match");
        }
    }
}
