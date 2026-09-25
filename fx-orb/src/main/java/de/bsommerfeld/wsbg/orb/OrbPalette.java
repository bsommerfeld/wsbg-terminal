package de.bsommerfeld.wsbg.orb;

import javafx.scene.paint.Color;

/**
 * The colours of the storm inside the orb: a gradient from the deepest to the
 * lightest tone, plus the colour of the clouds drifting over it.
 */
public record OrbPalette(Color deep, Color dark, Color mid, Color light, Color cloud) {

    /** Sage - the terminal's orb. */
    public static final OrbPalette SALBEI = of("#2f3a2c", "#5e7257", "#93a488", "#c9d2b8", "#eef0e2");

    /** Autumn - chestnut and caramel warming up to the terminal's amber. */
    public static final OrbPalette HERBST = of("#231c18", "#5a4032", "#9a6a3e", "#e0a458", "#f4e6d0");

    public static OrbPalette of(String deep, String dark, String mid, String light, String cloud) {
        return new OrbPalette(Color.web(deep), Color.web(dark), Color.web(mid), Color.web(light), Color.web(cloud));
    }

    /** The four gradient stops in shader order, deepest first. */
    Color[] stops() {
        return new Color[]{deep, dark, mid, light};
    }
}
