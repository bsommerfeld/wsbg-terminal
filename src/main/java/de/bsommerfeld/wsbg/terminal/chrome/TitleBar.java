package de.bsommerfeld.wsbg.terminal.chrome;

import de.bsommerfeld.wsbg.terminal.i18n.I18n;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

/**
 * The title bar of the extended stage: the system's own window buttons at one
 * edge (their slot is reserved by the {@link HeaderBar}), the context title
 * centred on the whole bar, the action strip at the other edge. The bar and the
 * title are the drag region; the buttons are carved out of it.
 *
 * <p>The title is context, not a constant: the brand by default, the settings
 * heading while {@link #settingsOpenProperty()} is set. The gear toggles that
 * state and turns into the back arrow while it holds.
 */
public final class TitleBar extends HeaderBar {

    /** The bar's height, and the height the system window buttons are asked to match. */
    public static final double HEIGHT = 38;

    private static final double TRACKING_EM = 0.22;

    private final BooleanProperty settingsOpen = new SimpleBooleanProperty(this, "settingsOpen", false);

    public TitleBar() {
        getStyleClass().add("titlebar");

        HBox title = title();
        // At its preferred width, not filling the centre area: only then does
        // the HeaderBar centre it on the whole bar, window buttons included.
        title.setMaxWidth(Region.USE_PREF_SIZE);
        HeaderBar.setAlignment(title, Pos.CENTER);
        HeaderBar.setDragType(title, HeaderDragType.DRAGGABLE_SUBTREE);
        setCenter(title);

        HBox actions = new HBox(gridButton(), gearButton());
        actions.getStyleClass().add("tb-actions");
        HeaderBar.setAlignment(actions, Pos.CENTER_RIGHT);
        setRight(actions);
    }

    /** Whether the settings view is open - the gear flips it, the title follows it. */
    public BooleanProperty settingsOpenProperty() {
        return settingsOpen;
    }

    private HBox title() {
        TrackedText wsbg = new TrackedText("WSBG", TRACKING_EM, "tb-text");
        wsbg.glyph(3).getStyleClass().setAll("flag");
        Text sep = new Text("·");
        sep.getStyleClass().add("sep");
        TrackedText terminal = new TrackedText("Terminal", TRACKING_EM, "tb-text");
        HBox brand = new HBox(wsbg, sep, terminal);
        brand.getStyleClass().add("tb-brand");

        TrackedText name = new TrackedText("", TRACKING_EM, "tb-text");
        I18n.bind("settings.title").addListener((o, was, now) -> name.setText(now));
        name.setText(I18n.get("settings.title"));

        brand.visibleProperty().bind(settingsOpen.not());
        brand.managedProperty().bind(brand.visibleProperty());
        name.visibleProperty().bind(settingsOpen);
        name.managedProperty().bind(name.visibleProperty());

        HBox title = new HBox(brand, name);
        title.getStyleClass().add("tb-title");
        return title;
    }

    private Button gridButton() {
        return iconButton("grid-btn", Icons.grid(), "grid.open", "grid.open");
    }

    private Button gearButton() {
        Node gear = Icons.gear();
        Node back = Icons.back();
        Button button = iconButton("gear-btn", gear, "titlebar.settings.title", "titlebar.settings.aria");
        button.setOnAction(e -> settingsOpen.set(!settingsOpen.get()));
        settingsOpen.addListener((o, was, open) -> button.setGraphic(open ? back : gear));
        return button;
    }

    private static Button iconButton(String styleClass, Node glyph, String tipKey, String accessibleKey) {
        Button button = new Button();
        button.getStyleClass().setAll("iconbtn", styleClass);
        button.setGraphic(glyph);
        button.setFocusTraversable(false);
        Tooltip tip = new Tooltip();
        tip.textProperty().bind(I18n.bind(tipKey));
        button.setTooltip(tip);
        button.accessibleTextProperty().bind(I18n.bind(accessibleKey));
        return button;
    }
}
