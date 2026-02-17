package de.bsommerfeld.wsbg.terminal.ui;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.ClearTerminalEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.SearchEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.SearchNextEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.ToggleGraphViewEvent;
import de.bsommerfeld.wsbg.terminal.ui.view.LiquidGlass;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ResourceBundle;

/**
 * Builds and injects the custom title bar overlay that replaces the
 * default WindowShell header.
 *
 * <p>
 * Layout (all platforms, only traffic lights position varies):
 *
 * <pre>
 * macOS:         [ traffic lights | broom ]  [ ── search ── ]  [ power | graph ]
 * Windows/Linux: [ broom ]  [ ── search ── ]  [ power | graph | traffic lights ]
 * </pre>
 *
 * <p>
 * The overlay is placed on top of the hidden original title bar via
 * a {@link StackPane} so it receives mouse events while the underlying
 * bar still drives the frameless window‑drag logic.
 */
final class TitleBarFactory {

    private static final Logger LOG = LoggerFactory.getLogger(TitleBarFactory.class);

    private TitleBarFactory() {
    }

    /**
     * Injects the title bar into the running stage. Must be called on the
     * JavaFX Application Thread after the scene is realized.
     *
     * @return the graph toggle button for blink animation, or null on failure
     */
    static Button inject(Stage stage, ApplicationEventBus eventBus, GlobalConfig config) {
        Node titleBarNode = stage.getScene().lookup(".title-bar");

        if (!(titleBarNode instanceof Pane bar)) {
            LOG.warn("TitleBar not found or unknown type");
            return null;
        }

        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");

        HBox searchBox = buildSearchBox(eventBus);
        HBox trafficLights = buildTrafficLights(stage, isMac);
        HBox utilityControls = buildUtilityControls(eventBus, config);
        HBox broomContainer = buildBroomButton(eventBus);

        Button graphBtn = (Button) utilityControls.getChildren().get(1);

        // Disable the entire broom container when graph is active — clearing
        // the terminal while viewing the graph has no visible effect. Disabling
        // at container level (not just the button) so Liquid Glass ignores it.
        BooleanProperty graphActive = new SimpleBooleanProperty(false);
        broomContainer.disableProperty().bind(graphActive);
        eventBus.register(new Object() {
            @Subscribe
            @SuppressWarnings("unused")
            public void onToggle(ToggleGraphViewEvent event) {
                Platform.runLater(() -> graphActive.set(!graphActive.get()));
            }
        });

        HBox mainLayout = assembleLayout(bar, searchBox, trafficLights, utilityControls, broomContainer, isMac);

        LiquidGlass.apply(broomContainer);
        LiquidGlass.apply(utilityControls);
        LiquidGlass.apply(searchBox);

        installDragHandler(stage, mainLayout);
        overlayOntoShell(stage, bar, mainLayout);

        stage.setTitle("");
        return graphBtn;
    }

    // ── Component builders ──────────────────────────────────────────

    private static HBox buildSearchBox(ApplicationEventBus eventBus) {
        HBox box = new HBox(0);
        box.getStyleClass().add("title-search-box");
        box.setAlignment(Pos.CENTER_LEFT);
        consumeDragEvents(box);

        ResourceBundle bundle = ResourceBundle.getBundle("i18n.messages");

        TextField field = new TextField();
        field.setPromptText(bundle.getString("ui.search.prompt"));
        field.getStyleClass().add("title-search-field");
        HBox.setHgrow(field, Priority.ALWAYS);

        Button clear = new Button("x");
        clear.getStyleClass().add("title-search-clear-btn");
        clear.setVisible(false);
        clear.managedProperty().bind(clear.visibleProperty());

        field.textProperty().addListener((obs, oldVal, newVal) -> {
            clear.setVisible(newVal != null && !newVal.isEmpty());
            eventBus.post(new SearchEvent(newVal));
        });
        field.setOnAction(e -> eventBus.post(new SearchNextEvent()));
        clear.setOnAction(e -> field.clear());

        box.getChildren().addAll(field, clear);
        return box;
    }

    private static HBox buildTrafficLights(Stage stage, boolean isMac) {
        HBox controls = new HBox(8);
        controls.setAlignment(Pos.CENTER);
        controls.getStyleClass().addAll("macos-controls-box", isMac ? "os-mac" : "os-win-linux");
        consumeDragEvents(controls);

        Button closeBtn = trafficLightButton("close");
        closeBtn.setOnAction(e -> stage.close());

        Button minBtn = trafficLightButton("minimize");
        minBtn.setOnAction(e -> stage.setIconified(true));

        Button maxBtn = trafficLightButton("maximize");
        maxBtn.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));

        stage.maximizedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                if (!maxBtn.getStyleClass().contains("is-maximized"))
                    maxBtn.getStyleClass().add("is-maximized");
            } else {
                maxBtn.getStyleClass().remove("is-maximized");
            }
        });

        if (isMac) {
            controls.getChildren().addAll(closeBtn, minBtn, maxBtn);
        } else {
            controls.getChildren().addAll(minBtn, maxBtn, closeBtn);
        }
        return controls;
    }

    private static HBox buildUtilityControls(ApplicationEventBus eventBus, GlobalConfig config) {
        HBox controls = new HBox(0);
        controls.getStyleClass().add("utility-controls-box");
        controls.setAlignment(Pos.CENTER);
        consumeDragEvents(controls);

        Button powerBtn = iconButton(
                config.getAgent().isPowerMode() ? "icon-power-active" : "icon-power");
        powerBtn.setOnAction(e -> {
            boolean next = !config.getAgent().isPowerMode();
            config.getAgent().setPowerMode(next);
            Region icon = (Region) powerBtn.getGraphic();
            icon.getStyleClass().clear();
            icon.getStyleClass().add(next ? "icon-power-active" : "icon-power");
            try {
                config.save();
            } catch (Exception ex) {
                LOG.error("Failed to persist power mode", ex);
            }
        });

        Button graphBtn = iconButton("icon-graph");
        graphBtn.setOnAction(e -> {
            Region icon = (Region) graphBtn.getGraphic();
            boolean isGraphActive = icon.getStyleClass().contains("icon-view-terminal");
            icon.getStyleClass().clear();
            icon.getStyleClass().add(isGraphActive ? "icon-graph" : "icon-view-terminal");
            eventBus.post(new ToggleGraphViewEvent());
        });

        controls.getChildren().addAll(powerBtn, graphBtn);
        return controls;
    }

    private static HBox buildBroomButton(ApplicationEventBus eventBus) {
        HBox container = new HBox(0);
        container.getStyleClass().add("utility-controls-box");
        container.setAlignment(Pos.CENTER);
        consumeDragEvents(container);

        Button btn = iconButton("icon-broom");
        btn.setOnAction(e -> eventBus.post(new ClearTerminalEvent()));

        container.getChildren().add(btn);
        return container;
    }

    // ── Layout assembly ─────────────────────────────────────────────

    /**
     * Unified layout: broom always left, power|graph always right.
     * Only the native traffic-light position differs per OS —
     * outermost left on macOS, outermost right on Windows/Linux.
     */
    private static HBox assembleLayout(Pane bar, HBox searchBox, HBox trafficLights,
            HBox utilityControls, HBox broomContainer, boolean isMac) {
        bar.getChildren().clear();
        bar.setOnMousePressed(null);
        bar.setOnMouseDragged(null);

        HBox left = growContainer(Pos.CENTER_LEFT);
        HBox right = growContainer(Pos.CENTER_RIGHT);

        if (isMac) {
            HBox leftWrapper = new HBox(16, trafficLights, broomContainer);
            leftWrapper.setAlignment(Pos.CENTER_LEFT);
            HBox.setMargin(leftWrapper, new Insets(0, 0, 0, 13));
            left.getChildren().add(leftWrapper);
        } else {
            HBox.setMargin(broomContainer, new Insets(0, 0, 0, 10));
            left.getChildren().add(broomContainer);
        }

        if (isMac) {
            HBox.setMargin(utilityControls, new Insets(0, 4, 0, 0));
            right.getChildren().add(utilityControls);
        } else {
            HBox rightWrapper = new HBox(16, utilityControls, trafficLights);
            rightWrapper.setAlignment(Pos.CENTER_RIGHT);
            right.getChildren().add(rightWrapper);
        }

        searchBox.setMaxWidth(450);

        HBox layout = new HBox();
        layout.getStyleClass().add("custom-title-bar-overlay");
        layout.setAlignment(Pos.CENTER);
        layout.setPickOnBounds(true);
        layout.setBackground(new Background(new BackgroundFill(Color.TRANSPARENT, CornerRadii.EMPTY, Insets.EMPTY)));
        layout.prefWidthProperty().bind(bar.widthProperty());
        layout.prefHeightProperty().bind(bar.heightProperty());
        layout.getChildren().addAll(left, searchBox, right);

        return layout;
    }

    /**
     * Ghosts the original title bar and overlays the custom layout.
     * Falls back to direct injection if the scene root is not a StackPane.
     */
    private static void overlayOntoShell(Stage stage, Pane bar, HBox layout) {
        bar.setMouseTransparent(true);
        bar.setOpacity(0);

        Parent root = stage.getScene().getRoot();
        if (root instanceof StackPane shell) {
            VBox overlay = new VBox();
            overlay.setPickOnBounds(false);
            overlay.setAlignment(Pos.TOP_CENTER);
            overlay.getChildren().add(layout);
            shell.getChildren().add(overlay);
        } else {
            LOG.warn("Shell root is not StackPane — falling back to direct injection");
            bar.setMouseTransparent(false);
            bar.setOpacity(1);
            bar.getChildren().add(layout);
        }
    }

    // ── Reusable primitives ─────────────────────────────────────────

    /** Creates an ascii-button with a styled Region icon. */
    private static Button iconButton(String iconStyleClass) {
        Button btn = new Button();
        btn.getStyleClass().add("ascii-button");
        Region icon = new Region();
        icon.getStyleClass().add(iconStyleClass);
        btn.setGraphic(icon);
        return btn;
    }

    /** Creates a traffic-light dot with a mouse-transparent icon overlay. */
    private static Button trafficLightButton(String type) {
        Button btn = new Button();
        btn.getStyleClass().addAll("custom-traffic-light", type);
        Region icon = new Region();
        icon.getStyleClass().add("traffic-light-icon");
        icon.setMouseTransparent(true);
        btn.setGraphic(icon);
        return btn;
    }

    /** Creates an HBox that stretches to fill available space. */
    private static HBox growContainer(Pos alignment) {
        HBox box = new HBox();
        box.setAlignment(alignment);
        box.setMinWidth(0);
        box.setPrefWidth(1);
        box.setPickOnBounds(false);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Prevents mouse events from bubbling up to the window-drag handler. */
    private static void consumeDragEvents(HBox box) {
        box.addEventHandler(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);
        box.addEventHandler(MouseEvent.MOUSE_DRAGGED, MouseEvent::consume);
    }

    /** Installs a window-drag handler on the layout's empty areas. */
    private static void installDragHandler(Stage stage, HBox layout) {
        double[] dragDelta = new double[2];
        layout.setOnMousePressed(event -> {
            Node target = (Node) event.getTarget();
            if (target == layout || target.getParent() == layout) {
                dragDelta[0] = stage.getX() - event.getScreenX();
                dragDelta[1] = stage.getY() - event.getScreenY();
                event.consume();
            }
        });
        layout.setOnMouseDragged(event -> {
            Node target = (Node) event.getTarget();
            if (target == layout || target.getParent() == layout) {
                stage.setX(event.getScreenX() + dragDelta[0]);
                stage.setY(event.getScreenY() + dragDelta[1]);
                event.consume();
            }
        });
    }
}
