package de.bsommerfeld.wsbg.orb;

import javafx.animation.AnimationTimer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.ByteBuffer;

/**
 * The orb's storm without the orb: the same marble, laid flat and poured into a
 * shape - the hands and the diamond of the app icon, say. While it is
 * {@link #revealedProperty() revealed} the storm pours in the way the orb fills
 * (mist from below, a cloud front from outside) and then keeps drifting.
 * <p>
 * The shape is a node used as the clip, so its alpha decides where the storm
 * shows: an {@link ImageView} of a white mask does exactly that.
 * <p>
 * As a {@link #setWave wave} the sheet instead lays the storm down on the
 * ground: revealing sends a wave out from a shape - its outline first, rounder
 * the further it runs - a bright crest in front and the storm soaking away
 * behind it, until nothing is left. The shape comes as a distance field.
 */
public final class MarbleSheet extends Region {

    /** The sheet has no ring; the renderer still wants a colour for it. */
    private static final Color NO_RING = Color.TRANSPARENT;

    private final DoubleProperty sheetWidth = new SimpleDoubleProperty(this, "sheetWidth", 100);
    private final DoubleProperty sheetHeight = new SimpleDoubleProperty(this, "sheetHeight", 100);
    private final ObjectProperty<OrbPalette> palette = new SimpleObjectProperty<>(this, "palette", OrbPalette.HERBST);
    private final BooleanProperty revealed = new SimpleBooleanProperty(this, "revealed");
    private final DoubleProperty speed = new SimpleDoubleProperty(this, "speed", 1);
    private final ObjectProperty<Duration> revealDuration =
            new SimpleObjectProperty<>(this, "revealDuration", Duration.millis(600));

    private final ImageView view = new ImageView();
    private boolean wave;
    private float[] field;
    private int fieldWidth;
    private int fieldHeight;
    private double reach;
    private boolean fieldChanged;

    private OrbRenderer renderer;
    private PixelBuffer<ByteBuffer> buffer;
    private double fill;
    private double stormTime;
    private long lastTick;

    private final AnimationTimer ticker = new AnimationTimer() {
        @Override
        public void handle(long now) {
            double dt = lastTick == 0 ? 0 : (now - lastTick) / 1e9;
            lastTick = now;
            advance(dt);
            draw();
            if (fill == 0 && !isRevealed()) {
                stop();
                lastTick = 0;
            }
        }
    };

    public MarbleSheet() {
        view.setSmooth(true);
        getChildren().add(view);

        sheetWidth.addListener((_, _, _) -> reallocate());
        sheetHeight.addListener((_, _, _) -> reallocate());
        palette.addListener((_, _, _) -> draw());
        revealed.addListener((_, _, _) -> ticker.start());
        sceneProperty().addListener((_, _, scene) -> {
            if (scene == null) {
                release();
            } else {
                scene.windowProperty().addListener((_, _, window) -> watchScale(window));
                watchScale(scene.getWindow());
            }
        });
    }

    /** Lays the storm down as a wave instead of pouring it into a shape. */
    public void setWave(boolean wave) {
        this.wave = wave;
        draw();
    }

    /**
     * Where the wave starts and how it spreads: for every cell of a grid
     * stretched over the sheet, the distance to the shape it runs out from, in
     * logical pixels - 0 on and inside the shape. Row by row from the top.
     *
     * @param reach how far the wave runs before it is done - the farthest
     *              distance that shows, which the field itself may exceed
     */
    public void setDistanceField(float[] field, int width, int height, double reach) {
        this.field = field;
        this.fieldWidth = width;
        this.fieldHeight = height;
        this.reach = reach;
        fieldChanged = true;
        draw();
    }

    /** How far the reveal has come: 0 empty, 1 full - for a wave, how far it has run. */
    public double fill() {
        return fill;
    }

    /**
     * How far the wave's crest has run from the shape at {@code fill}, in
     * logical pixels - sheet.frag's waveEdge, for whoever keeps time with it.
     */
    public double waveRadius(double fill) {
        return fill * fill * (3 - 2 * fill) * reach;
    }

    /** Where the storm shows: the clip, laid over the sheet at its own size. */
    public void setShape(Node shape) {
        setClip(shape);
    }

    /** Pours linearly, as the orb fills; the storm moves on by its speed. */
    private void advance(double dt) {
        double target = isRevealed() ? 1 : 0;
        double step = dt / Math.max(getRevealDuration().toSeconds(), 1e-3);
        fill = target > fill ? Math.min(target, fill + step) : Math.max(target, fill - step);
        stormTime += dt * getSpeed();
    }

    private void watchScale(Window window) {
        if (window != null) {
            window.renderScaleXProperty().addListener((_, _, _) -> reallocate());
            reallocate();
        }
    }

    private void reallocate() {
        Window window = getScene() == null ? null : getScene().getWindow();
        if (window == null) {
            return;
        }
        int width = Math.max(1, (int) Math.round(getSheetWidth() * window.getRenderScaleX()));
        int height = Math.max(1, (int) Math.round(getSheetHeight() * window.getRenderScaleY()));
        if (renderer == null) {
            renderer = new OrbRenderer("sheet.frag");
            fieldChanged = field != null;
        }
        ByteBuffer target = renderer.resize(width, height);
        buffer = new PixelBuffer<>(width, height, target, PixelFormat.getByteBgraPreInstance());
        view.setImage(new WritableImage(buffer));
        view.setFitWidth(getSheetWidth());
        view.setFitHeight(getSheetHeight());
        requestLayout();
        draw();
        if (isRevealed()) {
            ticker.start();
        }
    }

    private void draw() {
        if (renderer == null || buffer == null) {
            return;
        }
        if (fieldChanged) {
            renderer.distance(field, fieldWidth, fieldHeight, (float) reach);
            fieldChanged = false;
        }
        buffer.updateBuffer(_ -> {
            renderer.render((float) stormTime, (float) fill, getPalette(), NO_RING, wave ? 1 : 0);
            return null;
        });
    }

    private void release() {
        ticker.stop();
        if (renderer != null) {
            renderer.close();
            renderer = null;
            buffer = null;
            view.setImage(null);
        }
    }

    @Override
    protected double computePrefWidth(double height) {
        return getSheetWidth();
    }

    @Override
    protected double computePrefHeight(double width) {
        return getSheetHeight();
    }

    @Override
    protected void layoutChildren() {
        view.relocate(0, 0);
    }

    // --- properties -------------------------------------------------------------

    /** Width in logical pixels. */
    public DoubleProperty sheetWidthProperty() { return sheetWidth; }
    public double getSheetWidth() { return sheetWidth.get(); }
    public void setSheetWidth(double value) { sheetWidth.set(value); }

    /** Height in logical pixels. */
    public DoubleProperty sheetHeightProperty() { return sheetHeight; }
    public double getSheetHeight() { return sheetHeight.get(); }
    public void setSheetHeight(double value) { sheetHeight.set(value); }

    /** The storm's colours; {@link OrbPalette#HERBST} by default, as the terminal's orb. */
    public ObjectProperty<OrbPalette> paletteProperty() { return palette; }
    public OrbPalette getPalette() { return palette.get(); }
    public void setPalette(OrbPalette value) { palette.set(value); }

    /** Pours the storm in, and holds it until let go - then it drains again. */
    public BooleanProperty revealedProperty() { return revealed; }
    public boolean isRevealed() { return revealed.get(); }
    public void setRevealed(boolean value) { revealed.set(value); }

    /** How fast the storm moves: 1 is the orb's designed pace, 0 stands still. */
    public DoubleProperty speedProperty() { return speed; }
    public double getSpeed() { return speed.get(); }
    public void setSpeed(double value) { speed.set(value); }

    /** How long pouring in takes, and draining again; 600 ms by default. */
    public ObjectProperty<Duration> revealDurationProperty() { return revealDuration; }
    public Duration getRevealDuration() { return revealDuration.get(); }
    public void setRevealDuration(Duration value) { revealDuration.set(value); }
}
