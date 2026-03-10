package de.bsommerfeld.wsbg.terminal.ui;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
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
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the custom HeaderBar for StageStyle.EXTENDED windows.
 *
 * <p>
 * Layout uses the HeaderBar's three slots:
 *
 * <pre>
 * [ leading: broom ]  [ center: ── search ── ]  [ trailing: settings | graph ]
 * </pre>
 *
 * <p>
 * Native window controls (min/max/close) are provided by the OS automatically
 * via the EXTENDED stage style. On macOS they appear left, on Windows right.
 */
@SuppressWarnings("deprecation")
final class TitleBarFactory {

    private static final Logger LOG = LoggerFactory.getLogger(TitleBarFactory.class);

    private TitleBarFactory() {
    }

    /**
     * Creates the HeaderBar, sets up the Scene with a BorderPane root,
     * and attaches the stylesheet. Must be called before stage.show().
     *
     * @return the graph toggle button for blink animation, or null on failure
     */
    static Button buildHeaderBar(Stage stage, ApplicationEventBus eventBus,
            GlobalConfig config, I18nService i18n) {
        HeaderBar headerBar = new HeaderBar();
        headerBar.getStyleClass().add("custom-header-bar");

        HBox searchBox = buildSearchBox(eventBus, i18n);
        HBox utilityControls = buildUtilityControls(eventBus, config, i18n);
        HBox broomContainer = buildBroomButton(eventBus);

        Button graphBtn = (Button) utilityControls.getChildren().get(1);

        // Disable broom when graph is active — clearing the terminal while
        // viewing the graph has no visible effect. Container-level disable
        // lets Liquid Glass ignore it entirely.
        BooleanProperty graphActive = new SimpleBooleanProperty(false);
        broomContainer.disableProperty().bind(graphActive);
        eventBus.register(new Object() {
            @Subscribe
            @SuppressWarnings("unused")
            public void onToggle(ToggleGraphViewEvent event) {
                Platform.runLater(() -> graphActive.set(!graphActive.get()));
            }
        });

        headerBar.setLeading(broomContainer);
        headerBar.setCenter(searchBox);
        headerBar.setTrailing(utilityControls);

        LiquidGlass.apply(broomContainer);
        LiquidGlass.apply(utilityControls);
        LiquidGlass.apply(searchBox);

        BorderPane root = new BorderPane();
        root.setTop(headerBar);

        // Deep charcoal background — Bloomberg-professional aesthetic.
        // Slightly lighter than pure black for depth perception in future
        // dashboard tile layouts.
        Scene scene = new Scene(root);
        scene.setFill(Color.web("#0f0f0f"));

        scene.getStylesheets().add(
                TitleBarFactory.class.getResource("/css/index.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("");

        return graphBtn;
    }

    // ── Component builders ──────────────────────────────────────────

    private static HBox buildSearchBox(ApplicationEventBus eventBus, I18nService i18n) {
        HBox box = new HBox(0);
        box.getStyleClass().add("title-search-box");
        box.setAlignment(Pos.CENTER_LEFT);
        consumeDragEvents(box);

        TextField field = new TextField();
        field.setPromptText(i18n.get("ui.search.prompt"));
        field.getStyleClass().add("title-search-field");
        HBox.setHgrow(field, Priority.ALWAYS);

        eventBus.register(new Object() {
            @Subscribe
            public void onLanguageChanged(
                    de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LanguageChangedEvent e) {
                Platform.runLater(() -> field.setPromptText(i18n.get("ui.search.prompt")));
            }
        });

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

    private static HBox buildUtilityControls(ApplicationEventBus eventBus,
            GlobalConfig config, I18nService i18n) {
        HBox controls = new HBox(0);
        controls.getStyleClass().add("utility-controls-box");
        controls.setAlignment(Pos.CENTER);
        consumeDragEvents(controls);

        SettingsViewModel settingsVm = new SettingsViewModel(config, i18n, eventBus);
        SettingsPopOver settingsPopOver = new SettingsPopOver(settingsVm);

        Button settingsBtn = iconButton("icon-settings");
        settingsBtn.setOnAction(e -> settingsPopOver.toggle(settingsBtn));

        settingsVm.alertActiveProperty().addListener((obs, o, n) -> {
            Region icon = (Region) settingsBtn.getGraphic();
            icon.getStyleClass().clear();
            icon.getStyleClass().add(n ? "icon-settings-alert" : "icon-settings");
        });

        Button graphBtn = iconButton("icon-graph");
        graphBtn.setOnAction(e -> {
            Region icon = (Region) graphBtn.getGraphic();
            boolean isGraphActive = icon.getStyleClass().contains("icon-view-terminal");
            icon.getStyleClass().clear();
            icon.getStyleClass().add(isGraphActive ? "icon-graph" : "icon-view-terminal");
            eventBus.post(new ToggleGraphViewEvent());
        });

        controls.getChildren().addAll(settingsBtn, graphBtn);
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

    /** Prevents mouse events from bubbling past interactive controls. */
    private static void consumeDragEvents(HBox box) {
        box.addEventHandler(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);
        box.addEventHandler(MouseEvent.MOUSE_DRAGGED, MouseEvent::consume);
    }
}
