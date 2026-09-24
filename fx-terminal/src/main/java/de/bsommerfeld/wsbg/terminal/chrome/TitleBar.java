package de.bsommerfeld.wsbg.terminal.chrome;

import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

/**
 * The title bar of the extended stage: the system's own window buttons at one
 * edge (their slot is reserved by the {@link HeaderBar}) and the brand centred
 * on the whole bar. The bar and the brand are the drag region.
 */
public final class TitleBar extends HeaderBar {

    /** The bar's height, and the height the system window buttons are asked to match. */
    public static final double HEIGHT = 38;

    private static final double TRACKING_EM = 0.22;

    public TitleBar() {
        getStyleClass().add("titlebar");

        HBox brand = brand();
        // At its preferred width, not filling the centre area: only then does
        // the HeaderBar centre it on the whole bar, window buttons included.
        brand.setMaxWidth(Region.USE_PREF_SIZE);
        HeaderBar.setAlignment(brand, Pos.CENTER);
        HeaderBar.setDragType(brand, HeaderDragType.DRAGGABLE_SUBTREE);
        setCenter(brand);
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
