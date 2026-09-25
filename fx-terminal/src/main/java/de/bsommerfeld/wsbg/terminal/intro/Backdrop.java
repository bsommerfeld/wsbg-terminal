package de.bsommerfeld.wsbg.terminal.intro;

import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;

import java.util.SplittableRandom;

/**
 * The room the logo appears in: the terminal's frame colour under the glyph,
 * falling off to the canvas shade in the corners, and one pool of warm light on
 * the glyph that the intro turns up as the storm pours in.
 *
 * <p>Both are computed per pixel rather than stacked from gradient stops. A
 * falloff this shallow spans only a handful of 8-bit steps from the middle to
 * the edge; drawn as gradients those steps show as rings - the stepped look the
 * old backdrop had. Here every pixel gets a smooth value plus a little
 * triangular dither, which trades the rings for grain far below what the eye
 * picks up.
 */
final class Backdrop extends Region {

    /** The room: frame colour (-bg) in the middle, the canvas shade (-dark4) in the corners. */
    private static final int[] ROOM_CENTRE = {0x32, 0x31, 0x30};
    private static final int[] ROOM_CORNER = {0x22, 0x21, 0x20};
    /** The pool's light, warm rather than white - white haze greys a warm room. */
    private static final int[] LIGHT = {0xff, 0xd9, 0xa8};
    /** The pool at full strength, in the middle. */
    private static final double POOL_PEAK = 0.03;
    /** The pool's width, as a share of the shorter side (the gaussian's sigma). */
    private static final double POOL_SIGMA = 0.33;

    private final ImageView room = new ImageView();
    private final ImageView pool = new ImageView();
    /** Night over the room: the dive darkens it, so the terminal it opens onto stands out. */
    private final Region dusk = new Region();
    private int paintedWidth;
    private int paintedHeight;

    Backdrop() {
        pool.setOpacity(0);
        dusk.setStyle("-fx-background-color: #0c0b0a;");
        dusk.setOpacity(0);
        getChildren().addAll(room, pool, dusk);
    }

    /** 0 the bare room, 1 the pool at full strength. */
    void setLight(double strength) {
        pool.setOpacity(strength);
    }

    /** 0 the room as lit, 1 dark. */
    void setDusk(double darkness) {
        dusk.setOpacity(darkness);
    }

    @Override
    protected void layoutChildren() {
        dusk.resize(getWidth(), getHeight());
        int w = (int) Math.ceil(getWidth());
        int h = (int) Math.ceil(getHeight());
        if (w > 0 && h > 0 && (w != paintedWidth || h != paintedHeight)) {
            paint(w, h);
        }
    }

    private void paint(int w, int h) {
        paintedWidth = w;
        paintedHeight = h;
        SplittableRandom grain = new SplittableRandom(1);
        WritableImage roomImage = new WritableImage(w, h);
        WritableImage poolImage = new WritableImage(w, h);
        PixelWriter roomPixels = roomImage.getPixelWriter();
        PixelWriter poolPixels = poolImage.getPixelWriter();

        double cx = w / 2.0;
        double cy = h / 2.0;
        double corner = Math.hypot(cx, cy);
        double sigma = POOL_SIGMA * Math.min(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x + 0.5 - cx;
                double dy = y + 0.5 - cy;
                double r = Math.hypot(dx, dy);

                // smoothstep keeps the slope flat at both ends - no edge to see
                double t = Math.min(r / corner, 1);
                double fall = t * t * (3 - 2 * t);
                roomPixels.setArgb(x, y, 0xff000000
                        | channel(ROOM_CENTRE[0] + (ROOM_CORNER[0] - ROOM_CENTRE[0]) * fall, grain) << 16
                        | channel(ROOM_CENTRE[1] + (ROOM_CORNER[1] - ROOM_CENTRE[1]) * fall, grain) << 8
                        | channel(ROOM_CENTRE[2] + (ROOM_CORNER[2] - ROOM_CENTRE[2]) * fall, grain));

                double light = POOL_PEAK * Math.exp(-(r * r) / (2 * sigma * sigma));
                int alpha = channel(light * 255, grain);
                poolPixels.setArgb(x, y, alpha << 24 | LIGHT[0] << 16 | LIGHT[1] << 8 | LIGHT[2]);
            }
        }
        room.setImage(roomImage);
        pool.setImage(poolImage);
    }

    /** A value rounded to 8 bits with triangular dither of one step. */
    private static int channel(double value, SplittableRandom grain) {
        double dither = grain.nextDouble() - grain.nextDouble();
        return (int) Math.clamp(Math.round(value + dither), 0, 255);
    }
}
