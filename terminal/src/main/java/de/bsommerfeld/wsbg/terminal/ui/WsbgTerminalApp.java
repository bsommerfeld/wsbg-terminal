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
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
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
    // private javafx.scene.layout.Region sidebarToggleIcon; // REMOVED
    // private boolean lastSearchHadResults = false; // REMOVED

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
                    HBox searchBox = new HBox(0); // Zero spacing
                    // searchBox.setPadding(new javafx.geometry.Insets(0)); // Handled by CSS now
                    searchBox.getStyleClass().add("title-search-box");
                    searchBox.setAlignment(Pos.CENTER_LEFT);

                    // STOP EVENT BUBBLING: Prevent click/drag on search bar from triggering Window
                    // Drag
                    // Using addEventHandler to coexist with LiquidGlass's handlers
                    searchBox.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            javafx.scene.input.MouseEvent::consume);
                    searchBox.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
                            javafx.scene.input.MouseEvent::consume);

                    TextField searchField = new TextField();
                    ResourceBundle bundle = ResourceBundle.getBundle("i18n.messages");
                    searchField.setPromptText(bundle.getString("ui.search.prompt"));
                    searchField.getStyleClass().add("title-search-field");
                    searchField.setStyle("-fx-background-insets: 0; -fx-padding: 0 0 0 10;"); // Zero insets, slight
                                                                                              // left pad for text

                    HBox.setHgrow(searchField, Priority.ALWAYS);

                    Button clearSearchBtn = new Button("x");
                    clearSearchBtn.getStyleClass().add("title-search-clear-btn");
                    clearSearchBtn.setVisible(false);
                    clearSearchBtn.managedProperty().bind(clearSearchBtn.visibleProperty());

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

                    // Mark clear button as invisible to LiquidGlass snap logic
                    // so the blob fills the entire searchBox instead of
                    // snapping to this tiny internal control.
                    // clearSearchBtn.getProperties().put("liquid-glass-ignore", Boolean.TRUE);
                    searchBox.getChildren().addAll(searchField, clearSearchBtn);

                    // --- 2. Locate and Clear Title Bar ---
                    Node titleBarNode = primaryStage.getScene().lookup(".title-bar");

                    if (titleBarNode instanceof javafx.scene.layout.Pane) {
                        javafx.scene.layout.Pane bar = (javafx.scene.layout.Pane) titleBarNode;

                        // NUCLEAR OPTION: Clear everything to remove "muddy" legacy controls
                        bar.getChildren().clear();

                        // DISABLE LIBRARY DRAG: Remove any default handlers that might be ignoring
                        // consume()
                        // or triggering on Cursor state. We will implement our own controlled drag.
                        bar.setOnMousePressed(null);
                        bar.setOnMouseDragged(null);

                        // --- 3. Create Custom Controls (Unconditionally) ---
                        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
                        HBox customControls = new HBox(8);
                        customControls.setAlignment(Pos.CENTER);
                        customControls.getStyleClass().addAll("macos-controls-box", isMac ? "os-mac" : "os-win-linux");

                        // STOP EVENT BUBBLING: Prevent Window Drag on traffic lights
                        customControls.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                javafx.scene.input.MouseEvent::consume);
                        customControls.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
                                javafx.scene.input.MouseEvent::consume);

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

                        // Listener to toggle icon state
                        primaryStage.maximizedProperty().addListener((obs, oldVal, newVal) -> {
                            if (newVal) {
                                if (!maxBtn.getStyleClass().contains("is-maximized")) {
                                    maxBtn.getStyleClass().add("is-maximized");
                                }
                            } else {
                                maxBtn.getStyleClass().remove("is-maximized");
                            }
                        });

                        if (isMac) {
                            customControls.getChildren().addAll(closeBtn, minBtn, maxBtn);
                        } else {
                            // Windows/Linux Standard: Min, Max, Close
                            customControls.getChildren().addAll(minBtn, maxBtn, closeBtn);
                        }

                        // --- 4. Setup Layout (Auto-Expanding Regions for Perfect Centering) ---
                        // isMac is already defined above

                        HBox mainLayout = new HBox();
                        mainLayout.getStyleClass().add("custom-title-bar-overlay");
                        mainLayout.setAlignment(Pos.CENTER);
                        mainLayout.setPickOnBounds(true); // Must be true to capture clicks on empty space
                        mainLayout.setBackground(
                                new Background(new BackgroundFill(Color.TRANSPARENT, CornerRadii.EMPTY, Insets.EMPTY)));
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

                        // Define Graph Toggle Button Creator
                        Function<Void, Button> createGraphToggle = (v) -> {
                            Button btn = new Button();
                            btn.getStyleClass().add("ascii-button");
                            Region icon = new Region();
                            icon.getStyleClass().add("icon-graph"); // Default State
                            btn.setGraphic(icon);
                            btn.setOnAction(e -> {
                                boolean isGraphActive = icon.getStyleClass().contains("icon-view-terminal");
                                icon.getStyleClass().clear();
                                if (isGraphActive) {
                                    icon.getStyleClass().add("icon-graph");
                                    // Stop blink when switching to terminal
                                    stopTerminalBlink();
                                } else {
                                    icon.getStyleClass().add("icon-view-terminal");
                                }
                                injector.getInstance(ApplicationEventBus.class)
                                        .post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ToggleGraphViewEvent());
                            });
                            return btn;
                        };

                        // Define Toggle Button Creator - REMOVED per user request

                        // Define Power Button Creator
                        Function<Void, Button> createPowerBtn = (v) -> {
                            Button btn = new Button();
                            btn.getStyleClass().add("ascii-button");
                            Region icon = new Region();

                            // Get initial state
                            GlobalConfig config = injector.getInstance(GlobalConfig.class);
                            boolean isPower = config.getAgent().isPowerMode();

                            icon.getStyleClass().add(isPower ? "icon-power-active" : "icon-power");
                            btn.setGraphic(icon);

                            btn.setOnAction(e -> {
                                boolean currentState = config.getAgent().isPowerMode();
                                boolean newState = !currentState;
                                config.getAgent().setPowerMode(newState);

                                icon.getStyleClass().clear();
                                icon.getStyleClass().add(newState ? "icon-power-active" : "icon-power");

                                try {
                                    config.save();
                                } catch (Exception ex) {
                                    LOG.error("Failed to persist power mode", ex);
                                }
                                LOG.info("Power Mode toggled to: " + newState);
                            });
                            return btn;
                        };

                        // Utility Controls (state buttons only: Power + Graph Toggle)
                        HBox utilityControls = new HBox(0);
                        utilityControls.getStyleClass().add("utility-controls-box");
                        utilityControls.setAlignment(Pos.CENTER);

                        // STOP EVENT BUBBLING
                        utilityControls.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                javafx.scene.input.MouseEvent::consume);
                        utilityControls.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
                                javafx.scene.input.MouseEvent::consume);

                        Button broomBtn = createBroom.apply(null);
                        Button graphBtn = createGraphToggle.apply(null);
                        this.graphToggleButton = graphBtn;
                        Button powerBtn = createPowerBtn.apply(null);

                        utilityControls.getChildren().addAll(powerBtn, graphBtn);

                        // Broom in its own container, placed next to traffic lights
                        HBox broomContainer = new HBox(0);
                        broomContainer.getStyleClass().add("utility-controls-box");
                        broomContainer.setAlignment(Pos.CENTER);
                        // STOP EVENT BUBBLING
                        broomContainer.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                javafx.scene.input.MouseEvent::consume);
                        broomContainer.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
                                javafx.scene.input.MouseEvent::consume);
                        broomContainer.getChildren().add(broomBtn);

                        if (isMac) {
                            // Mac: [Traffic Lights + Broom] Left, [State Buttons] Right
                            HBox leftWrapper = new HBox(16, customControls, broomContainer);
                            leftWrapper.setAlignment(Pos.CENTER_LEFT);
                            HBox.setMargin(leftWrapper, new Insets(0, 0, 0, 13));
                            leftContainer.getChildren().add(leftWrapper);

                            HBox.setMargin(utilityControls, new Insets(0, 4, 0, 0));
                            rightContainer.getChildren().add(utilityControls);

                        } else {
                            // Non-Mac: [State Buttons] Left, [Broom + Traffic Lights] Right
                            HBox.setMargin(utilityControls, new Insets(0, 0, 0, 10));
                            leftContainer.getChildren().add(utilityControls);

                            HBox rightWrapper = new HBox(6, broomContainer, customControls);
                            rightWrapper.setAlignment(Pos.CENTER_RIGHT);
                            rightContainer.getChildren().add(rightWrapper);
                        }

                        // SEARCH CONTAINER (Center, static width)
                        // searchBox is already configured above
                        searchBox.setMaxWidth(450);

                        // ASSEMBLE: [Left(Grow)] [Search] [Right(Grow)]
                        mainLayout.getChildren().addAll(leftContainer, searchBox, rightContainer);

                        // iPadOS-style cursor magnetism on interactive containers
                        de.bsommerfeld.wsbg.terminal.ui.view.LiquidGlass.apply(broomContainer);
                        de.bsommerfeld.wsbg.terminal.ui.view.LiquidGlass.apply(utilityControls);
                        de.bsommerfeld.wsbg.terminal.ui.view.LiquidGlass.apply(searchBox);

                        // MANUAL WINDOW DRAG: Re-implement dragging on the layout container.
                        // Since child controls (searchBox, etc) CONSUME events, this handler
                        // will only run when clicking the empty space between controls.
                        final double[] dragDelta = new double[2];
                        mainLayout.setOnMousePressed(event -> {
                            // Only drag if clicking the container itself or spacing
                            if (event.getTarget() == mainLayout || event.getTarget() == leftContainer
                                    || event.getTarget() == rightContainer) {
                                dragDelta[0] = primaryStage.getX() - event.getScreenX();
                                dragDelta[1] = primaryStage.getY() - event.getScreenY();
                                // CONSUME to prevent bubbling to 'bar' (which triggers Library cursor logic)
                                event.consume();
                            }
                        });
                        mainLayout.setOnMouseDragged(event -> {
                            if (event.getTarget() == mainLayout || event.getTarget() == leftContainer
                                    || event.getTarget() == rightContainer) {
                                primaryStage.setX(event.getScreenX() + dragDelta[0]);
                                primaryStage.setY(event.getScreenY() + dragDelta[1]);
                                // CONSUME to prevent bubbling
                                event.consume();
                            }
                        });

                        // ULTIMATE BYPASS STRATEGY:
                        // 1. Make the original TitleBar a "Ghost": Invisible (but reserving space) and
                        // MouseTransparent.
                        bar.setMouseTransparent(true);
                        bar.setOpacity(0); // Invisible but takes up layout space

                        // 2. Inject mainLayout into an Overlay Layer on top of the WindowShell root.
                        // This completely decouples our controls from the Library's TitleBar logic.
                        Parent sceneRoot = primaryStage.getScene().getRoot();
                        if (sceneRoot instanceof javafx.scene.layout.StackPane shellRoot) {
                            // Container to position mainLayout at the top
                            javafx.scene.layout.VBox overlayContainer = new javafx.scene.layout.VBox();
                            overlayContainer.setPickOnBounds(false); // Let clicks pass through empty areas below
                                                                     // titlebar
                            overlayContainer.setAlignment(Pos.TOP_CENTER);

                            // mainLayout should capture its own events (set in definition)
                            // and allow pass-through for dragging where defined.
                            overlayContainer.getChildren().add(mainLayout);

                            // Ensure overlay is on top
                            shellRoot.getChildren().add(overlayContainer);
                        } else {
                            // Fallback if structure isn't as expected (unlikely with this library)
                            LOG.warn("Could not find Shell Root for Overlay. Falling back to direct injection.");
                            bar.setMouseTransparent(false);
                            bar.setOpacity(1);
                            bar.getChildren().add(mainLayout);
                        }

                        primaryStage.setTitle("");

                    } else {
                        LOG.warn("TitleBar not found or unknown type");
                    }
                } catch (Exception e) {
                    LOG.error("Failed to inject search bar", e);
                }
            });

            // "Upside Down" Fix Execution:
            // The platform runLater above does the layout. We need to modify how mainLayout
            // is added.
            // Rewriting the lambda body to implement the Overlay Strategy.

            LOG.info("WSBG-TERMINAL BEREIT. (MODE: {})",
                    de.bsommerfeld.wsbg.terminal.core.config.ApplicationMode.get());

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

        // Ensure Reddit Repository saves everything and shuts down executor
        if (injector != null) {
            try {
                de.bsommerfeld.wsbg.terminal.db.RedditRepository repo = injector
                        .getInstance(de.bsommerfeld.wsbg.terminal.db.RedditRepository.class);
                repo.shutdown();
            } catch (Exception e) {
                LOG.warn("Failed to shutdown RedditRepository: " + e.getMessage());
            }
        }

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
        // Highlighting logic removed
    }

    @com.google.common.eventbus.Subscribe
    public void onToggleRedditPanel(
            de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ToggleRedditPanelEvent event) {
        // Logic removed
    }

    // --- Terminal Blink (Triggered when graph sidebar fires AI analysis) ---
    private Button graphToggleButton;
    private javafx.animation.FadeTransition terminalBlinkAnim;

    @com.google.common.eventbus.Subscribe
    public void onTerminalBlink(
            de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TerminalBlinkEvent event) {
        javafx.application.Platform.runLater(() -> {
            if (event.active) {
                startTerminalBlink();
            } else {
                stopTerminalBlink();
            }
        });
    }

    private void startTerminalBlink() {
        if (graphToggleButton == null)
            return;
        graphToggleButton.getGraphic().getStyleClass().add("icon-terminal-blink");

        if (terminalBlinkAnim != null)
            terminalBlinkAnim.stop();
        terminalBlinkAnim = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(500), graphToggleButton);
        terminalBlinkAnim.setFromValue(1.0);
        terminalBlinkAnim.setToValue(0.3);
        terminalBlinkAnim.setCycleCount(javafx.animation.Animation.INDEFINITE);
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
