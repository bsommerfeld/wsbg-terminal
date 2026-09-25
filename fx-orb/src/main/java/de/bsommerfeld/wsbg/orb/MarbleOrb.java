package de.bsommerfeld.wsbg.orb;

import javafx.animation.AnimationTimer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
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
 * The orb: at rest exactly the SVG ring; on hover a glass marble fills - a cloud
 * front moves in from the rim while mist rises from below - and the storm inside
 * keeps drifting until the pointer leaves again.
 * <p>
 * Rendered by the GLSL shader through {@link OrbRenderer} and shown as an image
 * backed by the renderer's own buffer, sized to the window's output scale so it
 * stays sharp on Retina screens.
 */
public final class MarbleOrb extends Region {

    private final DoubleProperty size = new SimpleDoubleProperty(this, "size", 48);
    private final ObjectProperty<OrbPalette> palette = new SimpleObjectProperty<>(this, "palette", OrbPalette.SALBEI);
    private final BooleanProperty revealed = new SimpleBooleanProperty(this, "revealed");
    private final ObjectProperty<Color> ringColor = new SimpleObjectProperty<>(this, "ringColor", Color.web("#1f1f1f"));
    private final DoubleProperty speed = new SimpleDoubleProperty(this, "speed", 1);
    private final ObjectProperty<Duration> revealDuration =
            new SimpleObjectProperty<>(this, "revealDuration", Duration.millis(300));

    private final ImageView view = new ImageView();

    private OrbRenderer renderer;
    private PixelBuffer<ByteBuffer> buffer;
    private double hover;
    private double stormTime;
    private long lastTick;

    private final AnimationTimer ticker = new AnimationTimer() {
        @Override
        public void handle(long now) {
            double dt = lastTick == 0 ? 0 : (now - lastTick) / 1e9;
            lastTick = now;
            advance(dt);
            draw();
            if (hover == 0 && !wantsReveal()) {
                stop();
                lastTick = 0;
            }
        }
    };

    public MarbleOrb() {
        view.setSmooth(true);
        getChildren().add(view);

        hoverProperty().addListener((_, _, _) -> ticker.start());
        size.addListener((_, _, _) -> reallocate());
        palette.addListener((_, _, _) -> draw());
        revealed.addListener((_, _, _) -> ticker.start());
        ringColor.addListener((_, _, _) -> draw());
        sceneProperty().addListener((_, _, scene) -> {
            if (scene == null) {
                release();
            } else {
                scene.windowProperty().addListener((_, _, window) -> watchScale(window));
                watchScale(scene.getWindow());
            }
        });
    }

    /**
     * Moves the reveal towards the hover state - linear on purpose, an ease-in reads
     * as a stall at the rim - and the storm on by its speed. The storm time is
     * integrated rather than derived from the clock, so changing the speed never
     * makes it jump.
     */
    private void advance(double dt) {
        double target = wantsReveal() ? 1 : 0;
        double seconds = Math.max(getRevealDuration().toSeconds(), 1e-3);
        double step = dt / seconds;
        hover = target > hover ? Math.min(target, hover + step) : Math.max(target, hover - step);
        stormTime += dt * getSpeed();
    }

    private boolean wantsReveal() {
        return isHover() || isRevealed();
    }

    private void watchScale(Window window) {
        if (window != null) {
            window.renderScaleXProperty().addListener((_, _, _) -> reallocate());
            reallocate();
        }
    }

    /** New target size in device pixels: fresh buffer, fresh image, one frame. */
    private void reallocate() {
        Window window = getScene() == null ? null : getScene().getWindow();
        if (window == null) {
            return;
        }
        int pixels = (int) Math.round(getSize() * window.getRenderScaleX());
        if (renderer == null) {
            renderer = new OrbRenderer();
        }
        ByteBuffer target = renderer.resize(pixels, pixels);
        buffer = new PixelBuffer<>(pixels, pixels, target, PixelFormat.getByteBgraPreInstance());
        view.setImage(new WritableImage(buffer));
        view.setFitWidth(getSize());
        view.setFitHeight(getSize());
        requestLayout();
        draw();
        if (wantsReveal()) {
            ticker.start();
        }
    }

    private void draw() {
        if (renderer == null || buffer == null) {
            return;
        }
        buffer.updateBuffer(_ -> {
            renderer.render((float) stormTime, (float) hover, getPalette(), getRingColor());
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
        return getSize();
    }

    @Override
    protected double computePrefHeight(double width) {
        return getSize();
    }

    @Override
    protected void layoutChildren() {
        view.relocate((getWidth() - getSize()) / 2, (getHeight() - getSize()) / 2);
    }

    // --- properties -------------------------------------------------------------

    /** Edge length in logical pixels. */
    public DoubleProperty sizeProperty() { return size; }
    public double getSize() { return size.get(); }
    public void setSize(double value) { size.set(value); }

    /** The storm's colours; {@link OrbPalette#SALBEI} by default. */
    public ObjectProperty<OrbPalette> paletteProperty() { return palette; }
    public OrbPalette getPalette() { return palette.get(); }
    public void setPalette(OrbPalette value) { palette.set(value); }

    /** The ring the orb shows at rest; near-black by default, for light grounds. */
    public ObjectProperty<Color> ringColorProperty() { return ringColor; }
    public Color getRingColor() { return ringColor.get(); }
    public void setRingColor(Color value) { ringColor.set(value); }

    /**
     * Reveals the marble as hover does, for a pointer the orb does not see
     * itself - a button around it - and holds it until let go.
     */
    public BooleanProperty revealedProperty() { return revealed; }
    public boolean isRevealed() { return revealed.get(); }
    public void setRevealed(boolean value) { revealed.set(value); }

    /** How fast the storm inside moves: 1 is the designed pace, 2 twice as fast, 0 stands still. */
    public DoubleProperty speedProperty() { return speed; }
    public double getSpeed() { return speed.get(); }
    public void setSpeed(double value) { speed.set(value); }

    /** How long the orb takes to fill on hover and to empty again; 300 ms by default. */
    public ObjectProperty<Duration> revealDurationProperty() { return revealDuration; }
    public Duration getRevealDuration() { return revealDuration.get(); }
    public void setRevealDuration(Duration value) { revealDuration.set(value); }
}
