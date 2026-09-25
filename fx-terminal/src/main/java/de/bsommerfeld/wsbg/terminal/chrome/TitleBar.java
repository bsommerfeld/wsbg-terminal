package de.bsommerfeld.wsbg.terminal.chrome;

import javafx.beans.property.BooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Dimension2D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

/**
 * The title bar of the extended stage: the system's own window buttons at one
 * edge (their slot is reserved by the {@link HeaderBar}), the zen switch at the
 * other - with the {@link UpdateNotice} beside it, once there is an update -
 * and the brand centred on the whole bar. The bar and the brand are the drag
 * region.
 */
public final class TitleBar extends HeaderBar {

    /** The bar's height, and the height the system window buttons are asked to match. */
    public static final double HEIGHT = 38;

    private static final double TRACKING_EM = 0.22;

    /**
     * The top-left of the zen icon's four corners, pointing out; the other three
     * are it turned about the icon's centre. Spun half a turn about its own
     * centre it points in - that is the switch's animation, in the stylesheet.
     */
    private static final String CORNER = "M4 9V4h5";

    private static final PseudoClass ZEN = PseudoClass.getPseudoClass("zen");

    private final Button zenSwitch = new Button();
    private final UpdateNotice updateNotice = new UpdateNotice();
    /** The zen switch and the update notice: the edge opposite the system buttons. */
    private final HBox tools = new HBox(updateNotice, zenSwitch);

    /** @param zen what the zen switch toggles, and whose state its icon shows */
    public TitleBar(BooleanProperty zen) {
        getStyleClass().add("titlebar");

        HBox brand = brand();
        // At its preferred width, not filling the centre area: only then does
        // the HeaderBar centre it on the whole bar, window buttons included.
        brand.setMaxWidth(Region.USE_PREF_SIZE);
        HeaderBar.setAlignment(brand, Pos.CENTER);
        HeaderBar.setDragType(brand, HeaderDragType.DRAGGABLE_SUBTREE);
        setCenter(brand);

        Group icon = new Group(new Rectangle(24, 24, Color.TRANSPARENT));
        for (int quarter = 0; quarter < 4; quarter++) {
            icon.getChildren().add(corner(quarter));
        }
        zenSwitch.setGraphic(icon);
        zenSwitch.getStyleClass().setAll("tb-zen");
        zen.subscribe(on -> zenSwitch.pseudoClassStateChanged(ZEN, on));
        zenSwitch.setFocusTraversable(false);
        zenSwitch.setOnAction(_ -> zen.set(!zen.get()));

        tools.getStyleClass().add("tb-tools");
        HeaderBar.setAlignment(tools, Pos.CENTER);
        HeaderBar.setMargin(tools, new Insets(0, 5, 0, 5));
        setRight(tools);
    }

    public UpdateNotice updateNotice() {
        return updateNotice;
    }

    /**
     * Keeps the zen switch on the edge opposite the system window buttons - the
     * right on macOS, the left on Windows - and the update notice on its inner
     * side. While the buttons are hidden their inset is empty on both sides;
     * the switch then stays where it is.
     */
    public void followSystemButtons(Stage stage) {
        HeaderBar.leftSystemInsetProperty(stage).addListener((_, _, _) -> placeZenSwitch(stage));
        HeaderBar.rightSystemInsetProperty(stage).addListener((_, _, _) -> placeZenSwitch(stage));
        placeZenSwitch(stage);
    }

    private void placeZenSwitch(Stage stage) {
        if (occupied(HeaderBar.getLeftSystemInset(stage))) {
            setLeft(null);
            tools.getChildren().setAll(updateNotice, zenSwitch);
            setRight(tools);
        } else if (occupied(HeaderBar.getRightSystemInset(stage))) {
            setRight(null);
            tools.getChildren().setAll(zenSwitch, updateNotice);
            setLeft(tools);
        }
    }

    /** The top-left corner, turned {@code quarter} quarter turns clockwise about the icon's centre. */
    private static Group corner(int quarter) {
        SVGPath corner = new SVGPath();
        corner.setContent(CORNER);
        corner.getStyleClass().add("tb-corner");
        Group quadrant = new Group(corner);
        quadrant.getTransforms().add(new Rotate(90 * quarter, 12, 12));
        return quadrant;
    }

    private static boolean occupied(Dimension2D inset) {
        return inset != null && inset.getWidth() > 0;
    }

    private static HBox brand() {
        TrackedText wsbg = new TrackedText("WSBG", TRACKING_EM, "tb-text");
        // Sets the germany colors for the fourth letter "G"
        wsbg.glyph(3).getStyleClass().setAll("flag");

        Text sep = new Text("·");
        sep.getStyleClass().add("sep");

        TrackedText terminal = new TrackedText("Terminal", TRACKING_EM, "tb-text");

        HBox brand = new HBox(wsbg, sep, terminal);
        brand.getStyleClass().add("tb-brand");
        return brand;
    }
}
