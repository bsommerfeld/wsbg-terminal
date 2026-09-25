package de.bsommerfeld.wsbg.terminal.intro;

import de.bsommerfeld.wsbg.orb.MarbleSheet;
import de.bsommerfeld.wsbg.orb.OrbPalette;
import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Transform;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The startup intro: the app icon coming alive. A plate over the whole window.
 *
 * <pre>
 *   0      the room, in the terminal's greys
 *   80     the orb's storm pours into the hands and the diamond - mist from
 *          below, a cloud front from outside, as the orb fills - the icon's
 *          glass comes up on them, and the room lights up warm around them.
 *          The glyph floats: a soft shadow lies on the ground beneath it
 *   580    the storm drifts on inside the glyph
 * </pre>
 *
 * Then one of two endings:
 *
 * <pre>
 *   SETTLE (2.8 s)
 *   1150   the glyph sinks to the ground, its shadow drawing in under it
 *   1500   it touches down and goes to water: loses its shape, spreads flat,
 *          and runs out over the ground as a wave of storm - from its outline,
 *          everything that faces outwards, in its shape at first and rounder
 *          the further it runs; a bright crest in front, soaking away behind.
 *          It stays on the canvas - the frame and the title bar are not ground. The room goes with the first of it; the
 *          terminal is the ground. Every canvas dot the crest crosses lights
 *          up in the storm's colours, lifted a little by the crest as if seen
 *          through water, and cools back to grey
 *
 *   DIVE (2 s)
 *   1150   the glyph draws back a little, taking a breath
 *   1350   the camera dives into the stone: the glyph grows past the window,
 *          and through its solid silhouette the terminal is already there -
 *          until it fills the view. The room darkens, so the terminal inside
 *          the silhouette reads as a window, not as more of the same grey
 * </pre>
 *
 * The terminal the dive shows through the glyph is a snapshot of it, taken in
 * the first frames while only the room shows; the real one is under the plate,
 * identical, when the plate goes.
 *
 * The plate swallows every click and key while it is up - the app behind it is
 * booting and must not be poked through the animation.
 */
public final class Intro extends Pane {

    /** How the intro hands over to the terminal. */
    public enum Ending {
        /** The glyph settles on the ground like water, and the storm runs out into the canvas. */
        SETTLE,
        /** The camera dives into the stone, the terminal shows through its silhouette. */
        DIVE
    }

    // --- the timeline (ms) --------------------------------------------------------
    /** The terminal is snapshotted while only the bare room shows - taking it costs a frame or more. */
    private static final double T_SNAPSHOT = 40;
    private static final double T_POUR = 80;
    private static final Duration D_POUR = Duration.millis(500);

    private static final double T_DESCEND = 1150;
    private static final double D_DESCEND = 350;
    private static final double T_TOUCH = T_DESCEND + D_DESCEND;
    /** The glyph loses its shape over this long once it touches down. */
    private static final double D_MELT = 220;
    private static final Duration D_WAVE = Duration.millis(900);
    private static final double T_ROOM_OUT = T_TOUCH + 80;
    private static final double D_ROOM_OUT = 350;
    /** A lit dot takes this long to cool back to grey. */
    private static final double D_COOL = 450;

    private static final double T_BREATH = 1150;
    private static final double D_BREATH = 200;
    private static final double T_DIVE = T_BREATH + D_BREATH;
    private static final double D_DIVE = 650;
    /** The storm leaves the glyph in the first part of the dive; after that it would only be a blur. */
    private static final double D_STORM_OUT = 300;

    // --- the stage --------------------------------------------------------------
    /** The glyph's width: min(46vh, 42vw, 460px), as the old intro's logo. */
    private static final double GLYPH_HEIGHT_SHARE = 0.46;
    private static final double GLYPH_WIDTH_SHARE = 0.42;
    private static final double GLYPH_MAX = 460;
    /** The storm, faster than on the pill - it has a second to be seen. */
    private static final double STORM_SPEED = 3;

    /** The floating glyph's shadow on the ground, and how it draws in as the glyph comes down. */
    private static final Color SHADOW = Color.web("#070605");
    private static final double FLOAT_SHADOW_OFFSET = 26;
    private static final double FLOAT_SHADOW_RADIUS = 38;
    private static final double FLOAT_SHADOW_ALPHA = 0.45;
    private static final double GROUND_SHADOW_OFFSET = 3;
    private static final double GROUND_SHADOW_RADIUS = 10;
    private static final double GROUND_SHADOW_ALPHA = 0.8;
    /** How much smaller the glyph is on the ground than floating - further from the eye. */
    private static final double GROUND_SCALE = 0.93;
    /** Going to water: wider, flatter, blurred away. */
    private static final double MELT_WIDEN = 0.14;
    private static final double MELT_FLATTEN = 0.22;
    private static final double MELT_BLUR = 16;

    /** The lit dots: the storm's light tones, lifted by the crest as it passes. */
    private static final Color[] DOT_TONES = {Color.web("#e0a458"), Color.web("#f4e6d0"), Color.web("#c98a45")};
    private static final double DOT_RADIUS = 1.4;
    private static final double CREST_LIFT = 5;
    private static final double CREST_WIDTH = 22;

    /** How far the glyph draws back before the dive. */
    private static final double BREATH = 0.035;
    /** How dark the room gets during the dive. */
    private static final double DIVE_DUSK = 0.7;
    /**
     * The dive's target in the glyph images, as shares of them: the middle of
     * the stone, and the largest circle around it that stays inside the solid
     * stone - {@code .script/build-icons.py} prints both.
     */
    private static final double ZOOM_CENTRE_X = 0.501;
    private static final double ZOOM_CENTRE_Y = 0.277;
    private static final double ZOOM_REACH = 0.239;

    private static final Interpolator EASE = Interpolator.SPLINE(0.25, 0.1, 0.25, 1);
    private static final Interpolator EASE_IN_OUT = Interpolator.SPLINE(0.42, 0, 0.58, 1);
    /** Coming down: slow off the float, settling softly onto the ground. */
    private static final Interpolator SINK = Interpolator.SPLINE(0.5, 0, 0.3, 1);
    /**
     * The dive: on the exponent of the zoom, where a linear run reads as a
     * steady speed - so this starts slow and plunges. It ends at full speed on
     * purpose: by then the glyph covers the window and nothing is left to stop.
     */
    private static final Interpolator DIVE = Interpolator.SPLINE(0.6, 0, 0.9, 0.95);

    private final Node terminal;
    private final Ending ending;

    private final Backdrop backdrop = new Backdrop();
    private final Image glyphMask = new Image(Intro.class.getResource("intro-glyph.png").toExternalForm());
    private final ImageView shape = new ImageView(glyphMask);
    private final MarbleSheet storm = new MarbleSheet();
    /** The icon's glass on the glyph: its edges lit from the upper left - baked by build-icons.py. */
    private final ImageView sheen = new ImageView(new Image(Intro.class.getResource("intro-sheen.png").toExternalForm()));
    private final Group glyph = new Group(storm, sheen);
    private final DropShadow shadow = new DropShadow(BlurType.GAUSSIAN, Color.TRANSPARENT, 0, 0, 0, 0);
    private final GaussianBlur melt = new GaussianBlur(0);
    /** One scale for the glyph and the dive's iris, about the glyph's point that stays put. */
    private final Scale zoom = new Scale(1, 1);

    /** The storm laid down on the ground, as a wave. */
    private final MarbleSheet pool = new MarbleSheet();
    /** The dots the wave lights, above everything. */
    private final Canvas canvas = new Canvas();
    private List<Point2D> dots;
    private double[] litAt;

    /** The terminal, seen through the glyph's solid silhouette during the dive. */
    private final ImageView window = new ImageView();
    /** The glyph's solid silhouette - the dive's window, and the shape the settling wave runs out from. */
    private final Image silhouette = new Image(Intro.class.getResource("intro-iris.png").toExternalForm());
    private final ImageView iris = new ImageView(silhouette);
    private DistanceField field;

    private final Rectangle clip = new Rectangle();
    private final EventHandler<KeyEvent> swallowKeys = Event::consume;

    private Runnable onFinished = () -> {
    };
    private long startedAt = -1;
    private boolean finished;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (startedAt < 0) {
                startedAt = now;
            }
            frame((now - startedAt) / 1e6);
        }
    };

    /**
     * @param terminal what the plate opens onto - the dive shows it through the
     *                 glyph, the settling storm lights its canvas dots
     */
    public Intro(Node terminal, Ending ending) {
        this.terminal = terminal;
        this.ending = ending;

        shape.setSmooth(true);
        sheen.setSmooth(true);
        // The glass comes with the storm: on an empty glyph it would draw the outline alone.
        sheen.setOpacity(0);
        storm.setShape(shape);
        storm.setPalette(OrbPalette.HERBST);
        storm.setSpeed(STORM_SPEED);
        storm.setRevealDuration(D_POUR);
        glyph.setMouseTransparent(true);
        glyph.getTransforms().add(zoom);
        melt.setInput(shadow);
        glyph.setEffect(melt);

        pool.setWave(true);
        pool.setPalette(OrbPalette.HERBST);
        pool.setSpeed(STORM_SPEED);
        pool.setRevealDuration(D_WAVE);
        pool.setMouseTransparent(true);
        canvas.setMouseTransparent(true);

        iris.setSmooth(true);
        iris.getTransforms().add(zoom);
        window.setClip(iris);
        window.setMouseTransparent(true);
        // Only the dive looks through: before it, the silhouette would show the
        // terminal through every gap the storm has not filled yet.
        window.setVisible(false);

        getStyleClass().add("intro");
        getChildren().addAll(backdrop, pool, window, glyph, canvas);
        setClip(clip);
        // Every click lands on the plate, none on the app booting behind it.
        setOnMouseClicked(Event::consume);
        setOnMousePressed(Event::consume);
        sceneProperty().addListener((_, before, after) -> swallowKeys(before, after));
    }

    /** Runs once the plate has played out and taken itself out of the scene. */
    public void setOnFinished(Runnable onFinished) {
        this.onFinished = onFinished;
    }

    /** Starts the intro with the next frame. */
    public void play() {
        frame(0);
        timer.start();
    }

    private void swallowKeys(Scene before, Scene after) {
        if (before != null) {
            before.removeEventFilter(KeyEvent.ANY, swallowKeys);
        }
        if (after != null && !finished) {
            after.addEventFilter(KeyEvent.ANY, swallowKeys);
        }
    }

    // --- layout -----------------------------------------------------------------

    private double glyphWidth() {
        return Math.min(Math.min(GLYPH_HEIGHT_SHARE * getHeight(), GLYPH_WIDTH_SHARE * getWidth()), GLYPH_MAX);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        clip.setWidth(w);
        clip.setHeight(h);
        backdrop.resizeRelocate(0, 0, w, h);
        canvas.setWidth(w);
        canvas.setHeight(h);
        pool.setSheetWidth(w);
        pool.setSheetHeight(h);
        pool.resizeRelocate(0, 0, w, h);

        double glyphWidth = glyphWidth();
        double glyphHeight = glyphWidth * glyphMask.getHeight() / glyphMask.getWidth();
        double left = (w - glyphWidth) / 2;
        double top = (h - glyphHeight) / 2;
        storm.setSheetWidth(glyphWidth);
        storm.setSheetHeight(glyphHeight);
        storm.resizeRelocate(left, top, glyphWidth, glyphHeight);
        shape.setFitWidth(glyphWidth);
        shape.setFitHeight(glyphHeight);
        sheen.setFitWidth(glyphWidth);
        sheen.setFitHeight(glyphHeight);
        sheen.relocate(left, top);
        // The clip lives in the window view's coordinates, which are the plate's.
        iris.setFitWidth(glyphWidth);
        iris.setFitHeight(glyphHeight);
        iris.setX(left);
        iris.setY(top);
        if (ending == Ending.DIVE) {
            zoom.setPivotX(left + ZOOM_CENTRE_X * glyphWidth);
            zoom.setPivotY(top + ZOOM_CENTRE_Y * glyphHeight);
        } else {
            // Settling, the glyph shrinks towards its middle and spreads from there - where the wave starts.
            zoom.setPivotX(w / 2);
            zoom.setPivotY(h / 2);
        }
    }

    // --- the timeline -----------------------------------------------------------

    /** Poses the whole plate for {@code now}, in ms since the intro began. */
    private void frame(double now) {
        if (finished) {
            return;
        }
        if (now >= T_POUR && !storm.isRevealed()) {
            storm.setRevealed(true);
        }
        double poured = progress(now, T_POUR, D_POUR.toMillis());
        backdrop.setLight(EASE.interpolate(0.0, 1.0, poured));
        sheen.setOpacity(EASE.interpolate(0.0, 1.0, poured));

        if (ending == Ending.DIVE) {
            dive(now);
        } else {
            settle(now, poured);
        }
    }

    private void settle(double now, double poured) {
        if (now >= T_DESCEND && field == null) {
            // Worked out while the glyph comes down, not at touch-down, where every frame shows.
            field = groundField();
            Bounds ground = groundBounds();
            pool.setDistanceField(field.distances(), field.columns(), field.rows(), field.farthestWithin(ground));
            pool.setClip(groundClip(ground));
        }
        double down = SINK.interpolate(0.0, 1.0, progress(now, T_DESCEND, D_DESCEND));
        shadow.setColor(Color.color(SHADOW.getRed(), SHADOW.getGreen(), SHADOW.getBlue(),
                poured * lerp(FLOAT_SHADOW_ALPHA, GROUND_SHADOW_ALPHA, down)));
        shadow.setOffsetY(lerp(FLOAT_SHADOW_OFFSET, GROUND_SHADOW_OFFSET, down));
        shadow.setRadius(lerp(FLOAT_SHADOW_RADIUS, GROUND_SHADOW_RADIUS, down));

        double melted = EASE.interpolate(0.0, 1.0, progress(now, T_TOUCH, D_MELT));
        double size = lerp(1, GROUND_SCALE, down);
        zoom.setX(size * (1 + MELT_WIDEN * melted));
        zoom.setY(size * (1 - MELT_FLATTEN * melted));
        melt.setRadius(MELT_BLUR * melted);
        glyph.setOpacity(1 - melted);

        if (now < T_TOUCH) {
            return;
        }
        if (!pool.isRevealed()) {
            pool.setRevealed(true);
            dots = bareDots();
            litAt = new double[dots.size()];
            Arrays.fill(litAt, -1);
        }
        backdrop.setOpacity(1 - EASE.interpolate(0.0, 1.0, progress(now, T_ROOM_OUT, D_ROOM_OUT)));
        boolean glowing = lightDots(now);

        if (pool.fill() >= 1 && !glowing) {
            finish();
        }
    }

    /**
     * Lights the dots the crest has reached, lifted outwards while it passes
     * over them, and lets them cool. Returns whether any is still warm.
     */
    private boolean lightDots(double now) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        double crest = pool.waveRadius(pool.fill());
        Point2D centre = new Point2D(getWidth() / 2, getHeight() / 2);
        boolean glowing = false;
        for (int i = 0; i < dots.size(); i++) {
            Point2D dot = dots.get(i);
            double r = field.at(dot);
            if (litAt[i] < 0) {
                if (r > crest) {
                    glowing = true;
                    continue;
                }
                litAt[i] = now;
            }
            double heat = 1 - (now - litAt[i]) / D_COOL;
            if (heat <= 0) {
                continue;
            }
            glowing = true;
            heat *= heat;
            double gap = (r - crest) / CREST_WIDTH;
            double lift = CREST_LIFT * Math.exp(-gap * gap);
            Point2D away = dot.subtract(centre);
            Point2D out = away.magnitude() == 0 ? Point2D.ZERO : away.normalize().multiply(lift);
            Color tone = DOT_TONES[Math.floorMod(Math.round(dot.getX() * 7 + dot.getY() * 13), DOT_TONES.length)];
            gc.setFill(Color.color(tone.getRed(), tone.getGreen(), tone.getBlue(), heat));
            double radius = DOT_RADIUS * (1 + 0.5 * heat);
            gc.fillOval(dot.getX() + out.getX() - radius, dot.getY() + out.getY() - radius, radius * 2, radius * 2);
        }
        return glowing;
    }

    private void dive(double now) {
        if (now >= T_SNAPSHOT && window.getImage() == null) {
            window.setImage(snapshot(terminal));
        }
        window.setVisible(now >= T_DIVE);
        double breath = 1 - BREATH * EASE_IN_OUT.interpolate(0.0, 1.0, progress(now, T_BREATH, D_BREATH));
        double dive = Math.pow(diveDepth(), DIVE.interpolate(0.0, 1.0, progress(now, T_DIVE, D_DIVE)));
        zoom.setX(breath * dive);
        zoom.setY(breath * dive);
        glyph.setOpacity(1 - EASE.interpolate(0.0, 1.0, progress(now, T_DIVE, D_STORM_OUT)));
        backdrop.setDusk(DIVE_DUSK * EASE.interpolate(0.0, 1.0, progress(now, T_BREATH, D_BREATH + D_STORM_OUT)));

        if (now >= T_DIVE + D_DIVE) {
            finish();
        }
    }

    /** How far the dive has to zoom until the solid stone covers the whole window. */
    private double diveDepth() {
        double reach = ZOOM_REACH * glyphWidth();
        double farthest = Math.hypot(Math.max(zoom.getPivotX(), getWidth() - zoom.getPivotX()),
                Math.max(zoom.getPivotY(), getHeight() - zoom.getPivotY()));
        return farthest / reach * 1.05;
    }

    /** The node as it is now, at the window's output scale so it stays sharp. */
    private Image snapshot(Node node) {
        double scale = getScene() == null || getScene().getWindow() == null
                ? 1 : getScene().getWindow().getRenderScaleX();
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        parameters.setTransform(Transform.scale(scale, scale));
        Image image = node.snapshot(parameters, null);
        window.setFitWidth(image.getWidth() / scale);
        window.setFitHeight(image.getHeight() / scale);
        return image;
    }

    /** The silhouette where the glyph lies once it is down: at its ground size, about the plate's middle. */
    private DistanceField groundField() {
        double glyphWidth = glyphWidth() * GROUND_SCALE;
        double glyphHeight = glyphWidth * silhouette.getHeight() / silhouette.getWidth();
        Rectangle2D placement = new Rectangle2D((getWidth() - glyphWidth) / 2, (getHeight() - glyphHeight) / 2,
                glyphWidth, glyphHeight);
        return new DistanceField(silhouette, placement, getWidth(), getHeight());
    }

    /** The canvas on the plate - the ground the wave runs over; the whole plate if there is none. */
    private Bounds groundBounds() {
        Node canvasNode = terminal.lookup(".desktop-canvas");
        return canvasNode == null ? getLayoutBounds() : onPlate(canvasNode);
    }

    /** The ground's outline, with the canvas's own rounded corners where it has them. */
    private Rectangle groundClip(Bounds ground) {
        Rectangle outline = new Rectangle(ground.getMinX(), ground.getMinY(), ground.getWidth(), ground.getHeight());
        if (terminal.lookup(".desktop-canvas") instanceof Node canvasNode && canvasNode.getClip() instanceof Rectangle corners) {
            outline.setArcWidth(corners.getArcWidth());
            outline.setArcHeight(corners.getArcHeight());
        }
        return outline;
    }

    /** The canvas dots that show - inside the canvas, not under a widget - in the plate's coordinates. */
    private List<Point2D> bareDots() {
        List<Point2D> found = new ArrayList<>();
        if (!(terminal.lookup(".dc-dots") instanceof Path path) || terminal.lookup(".desktop-canvas") == null) {
            return found;
        }
        Bounds ground = groundBounds();
        List<Bounds> widgets = terminal.lookupAll(".dc-widget").stream().map(this::onPlate).toList();
        List<PathElement> elements = path.getElements();
        for (int i = 0; i + 1 < elements.size(); i++) {
            if (elements.get(i) instanceof MoveTo move && elements.get(i + 1) instanceof ArcTo arc) {
                Point2D dot = sceneToLocal(path.localToScene(move.getX() + arc.getRadiusX(), move.getY()));
                if (ground.contains(dot) && widgets.stream().noneMatch(widget -> widget.contains(dot))) {
                    found.add(dot);
                }
            }
        }
        return found;
    }

    private Bounds onPlate(Node node) {
        return sceneToLocal(node.localToScene(node.getBoundsInLocal()));
    }

    /** Where {@code now} stands in a stretch of {@code duration} from {@code start}, clamped to 0..1. */
    private static double progress(double now, double start, double duration) {
        return Math.clamp((now - start) / duration, 0, 1);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private void finish() {
        finished = true;
        timer.stop();
        storm.setRevealed(false);
        pool.setRevealed(false);
        if (getScene() != null) {
            getScene().removeEventFilter(KeyEvent.ANY, swallowKeys);
        }
        if (getParent() instanceof Pane parent) {
            parent.getChildren().remove(this);
        }
        onFinished.run();
    }
}
