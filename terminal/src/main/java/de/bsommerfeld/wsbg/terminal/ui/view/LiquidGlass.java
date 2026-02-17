package de.bsommerfeld.wsbg.terminal.ui.view;

import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ButtonBase;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.InnerShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * iPadOS Liquid Glass effect with Venom-style surface tension.
 * The blob wraps controls, flows between them like liquid metal,
 * and stretches like a symbiote when pulled away from a control.
 *
 * <p>
 * The glass surface uses a Canvas overlay for real-time specular
 * highlights and shimmer effects that CSS cannot achieve with
 * positional awareness.
 */
public final class LiquidGlass {

        private static final String APPLIED_KEY = "liquid-glass-applied";
        private static final String HOVER_CLASS = "liquid-glass-hover";

        // Applied to the snap target so CSS `:hover`-equivalent rules fire.
        // A plain style class is used instead of pseudoClassStateChanged(:hover)
        // because JavaFX's Scene resets the :hover pseudo-class every CSS pulse
        // based on real mouse pick results.
        private static final String BLOB_HOVER_CLASS = "blob-hover";

        // Container deformation
        private static final double TRANSLATE_MAX = 4.0;
        private static final double HOVER_SCALE = 1.04;
        private static final double SCALE_SQUASH = 0.015;
        private static final double DEPTH_RADIUS = 10.0;
        private static final double DEPTH_OFFSET = 3.0;
        private static final double ELEVATION_RADIUS = 16.0;
        private static final double PARALLAX_DAMP = 0.35;

        // Blob flow
        private static final double BLOB_MIN = 28.0;
        private static final double VEL_SMOOTH = 0.15;
        private static final double FLOW_LERP = 0.25;

        // Snap attraction: controls pull the blob within this distance (parent coords).
        private static final double ATTRACT_RADIUS = 30.0;

        // Blob clings to control until cursor exceeds this boundary.
        private static final double STICKY_RADIUS = 30.0;

        // Extra padding around snapped bounds to bridge visual gaps.
        private static final double SNAP_PAD = 6.0;

        // Venom: blob resists leaving the control, then stretches toward cursor.
        private static final double VENOM_LERP = 0.12;
        private static final double VENOM_STRETCH_MAX = 0.45;

        private static final Duration EXIT_DUR = Duration.millis(350);
        private static final Interpolator SPRING = Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0);

        private LiquidGlass() {
        }

        // ── Mutable state shared between AnimationTimer and event handlers ───
        // Bundled into an effectively-final object so lambdas can close over it
        // without requiring arrays-of-one.

        private static final class GlassState {
                double dampX, dampY;
                boolean dampInit;
                double prevX, prevY;
                double svx, svy, speed;
                boolean hovered;
                boolean pressed;

                // Snap / Venom
                Node snapTarget;
                boolean venom;
                double anchorCX, anchorCY, anchorW, anchorH;

                // Mouse tracking
                double mouseX, mouseY;

                // Click feedback
                long lastClickTime;
                double clickX, clickY;

                // Saved child cursors — restored on mouse exit
                final Map<Node, Cursor> savedCursors = new IdentityHashMap<>();

                // Previous snap target — used to toggle synthetic hover
                // pseudo-class when the blob flows between controls.
                Node prevSnapTarget;
        }

        /**
         * Applies the Liquid Glass effect to the given node.
         * Idempotent — calling it twice on the same node is a no-op.
         */
        public static void apply(Node node) {
                if (Boolean.TRUE.equals(node.getProperties().get(APPLIED_KEY)))
                        return;
                node.getProperties().put(APPLIED_KEY, Boolean.TRUE);

                Region blob = createBlob(node);
                Canvas glass = createGlassCanvas(node, blob);
                clipContainer(node);

                InnerShadow depth = new InnerShadow(DEPTH_RADIUS, 0, 0, Color.color(0, 0, 0, 0));
                depth.setInput(node.getEffect());
                DropShadow elevation = new DropShadow(ELEVATION_RADIUS, 0, 3, Color.color(0, 0, 0, 0));
                elevation.setInput(depth);
                node.setEffect(elevation);
                guardEffect(node, depth, elevation);

                GlassState state = new GlassState();

                Timeline exitSlide = new Timeline();
                exitSlide.setOnFinished(e -> {
                        if (blob != null)
                                blob.setOpacity(0);
                        if (glass != null)
                                glass.setOpacity(0);
                });

                Timeline springBack = buildSpringBack(node, depth, elevation);

                AnimationTimer timer = new AnimationTimer() {
                        @Override
                        public void handle(long now) {
                                if (!state.hovered)
                                        return;
                                enforceCursorNone(node);
                                tickPhysics(state);
                                deform(node, blob, glass, depth, elevation, springBack, state);
                        }
                };

                installEventHandlers(node, blob, glass, depth, elevation,
                                state, timer, exitSlide, springBack);
        }

        // ── Event wiring ────────────────────────────────────────────────

        private static void installEventHandlers(Node node, Region blob, Canvas glass,
                        InnerShadow depth, DropShadow elevation,
                        GlassState state, AnimationTimer timer,
                        Timeline exitSlide, Timeline springBack) {
                EventHandler<MouseEvent> moveHandler = e -> {
                        if (!state.hovered)
                                return;
                        exitSlide.stop();
                        forceCursor(node, e);
                        state.mouseX = e.getX();
                        state.mouseY = e.getY();
                };

                node.addEventFilter(MouseEvent.MOUSE_MOVED, moveHandler);
                node.addEventFilter(MouseEvent.MOUSE_DRAGGED, moveHandler);
                node.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, e -> forceCursor(node, e));
                node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> forceCursor(node, e));
                node.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> forceCursor(node, e));
                node.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> forceCursor(node, e));

                node.setOnMouseEntered(e -> onEnter(node, blob, glass, elevation,
                                state, timer, springBack, exitSlide, e));

                node.setOnMouseExited(e -> onExit(node, blob, glass,
                                state, timer, springBack, exitSlide, e));

                node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> onPress(node, blob,
                                depth, elevation, state, springBack, exitSlide, e));

                node.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> onRelease(node, blob, glass,
                                state, timer, springBack));

                // Proxy click to the snap target when the blob covers a control
                // but the real cursor sits slightly outside it.
                node.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
                        Node snap = state.snapTarget;
                        if (snap instanceof ButtonBase btn && !snap.isHover()) {
                                btn.fire();
                                e.consume();
                        }
                });
        }

        private static void onEnter(Node node, Region blob, Canvas glass,
                        DropShadow elevation, GlassState state,
                        AnimationTimer timer, Timeline springBack,
                        Timeline exitSlide, MouseEvent e) {
                state.hovered = true;
                state.mouseX = e.getX();
                state.mouseY = e.getY();
                timer.start();

                springBack.stop();
                exitSlide.stop();
                node.setCursor(Cursor.NONE);

                if (node instanceof Pane pn)
                        saveCursors(pn, state.savedCursors);
                if (!node.getStyleClass().contains(HOVER_CLASS))
                        node.getStyleClass().add(HOVER_CLASS);

                state.dampInit = false;
                state.snapTarget = null;
                state.venom = false;
                node.setScaleX(HOVER_SCALE);
                node.setScaleY(HOVER_SCALE);
                elevation.setColor(Color.color(0, 0, 0, 0.25));

                if (blob != null) {
                        double bw = node.getLayoutBounds().getWidth();
                        double bh = node.getLayoutBounds().getHeight();
                        blob.resize(bw > 0 ? bw : BLOB_MIN, bh > 0 ? bh : BLOB_MIN);
                        blob.setLayoutX(0);
                        blob.setLayoutY(0);
                        blob.setScaleX(1);
                        blob.setScaleY(1);
                        blob.setRotate(0);
                        blob.setTranslateX(0);
                        blob.setTranslateY(0);
                        blob.toFront();
                        blob.setOpacity(1);
                }
                if (glass != null) {
                        glass.toFront();
                        glass.setOpacity(1);
                }

                lockSceneCursor(node, state);
        }

        private static void onExit(Node node, Region blob, Canvas glass,
                        GlassState state, AnimationTimer timer,
                        Timeline springBack, Timeline exitSlide, MouseEvent e) {
                state.hovered = false;

                // Drag may fire MOUSE_EXITED while still pressing — keep blob alive.
                if (state.pressed)
                        return;

                cleanupHoverState(node, state, timer);
                springBack.playFromStart();

                if (blob != null) {
                        double w = node.getLayoutBounds().getWidth();
                        double h = node.getLayoutBounds().getHeight();
                        double ex = clamp(e.getX(), 0, w);
                        double ey = clamp(e.getY(), 0, h);
                        exitSlide.getKeyFrames().clear();
                        exitSlide.getKeyFrames().add(new KeyFrame(EXIT_DUR,
                                        new KeyValue(blob.layoutXProperty(), ex - blob.getWidth() / 2, SPRING),
                                        new KeyValue(blob.layoutYProperty(), ey - blob.getHeight() / 2, SPRING),
                                        new KeyValue(blob.scaleXProperty(), 0.3, SPRING),
                                        new KeyValue(blob.scaleYProperty(), 0.3, SPRING),
                                        new KeyValue(blob.opacityProperty(), 0.0, SPRING)));
                        exitSlide.playFromStart();
                }
                if (glass != null)
                        glass.setOpacity(0);
        }

        private static void onPress(Node node, Region blob,
                        InnerShadow depth, DropShadow elevation,
                        GlassState state, Timeline springBack,
                        Timeline exitSlide, MouseEvent e) {
                state.pressed = true;
                state.lastClickTime = System.nanoTime();

                if (blob != null) {
                        state.clickX = e.getX() - (blob.getLayoutX() + blob.getTranslateX());
                        state.clickY = e.getY() - (blob.getLayoutY() + blob.getTranslateY());
                }

                springBack.stop();
                exitSlide.stop();
                node.setCursor(Cursor.NONE);
                if (node.getScene() != null)
                        node.getScene().setCursor(Cursor.NONE);

                node.setScaleX(1.0 - SCALE_SQUASH);
                node.setScaleY(1.0 - SCALE_SQUASH);
                depth.setColor(Color.color(0, 0, 0, 0.35));
                elevation.setColor(Color.color(0, 0, 0, 0.35));

                // Quick scale pulse — tactile "press into water" feel.
                if (blob != null) {
                        blob.setScaleX(1.15);
                        blob.setScaleY(1.15);
                }
        }

        private static void onRelease(Node node, Region blob, Canvas glass,
                        GlassState state, AnimationTimer timer,
                        Timeline springBack) {
                state.pressed = false;
                springBack.playFromStart();

                // If cursor drifted out while pressing, trigger full exit cleanup.
                if (!state.hovered) {
                        cleanupHoverState(node, state, timer);
                        if (blob != null) {
                                Timeline fadeOut = new Timeline(new KeyFrame(Duration.millis(200),
                                                new KeyValue(blob.opacityProperty(), 0, Interpolator.EASE_OUT),
                                                new KeyValue(blob.scaleXProperty(), 0.5, Interpolator.EASE_OUT),
                                                new KeyValue(blob.scaleYProperty(), 0.5, Interpolator.EASE_OUT)));
                                fadeOut.play();
                        }
                        if (glass != null)
                                glass.setOpacity(0);
                }
        }

        // ── Physics / deformation ───────────────────────────────────────

        /** Advances damped cursor tracking one step. */
        private static void tickPhysics(GlassState s) {
                double lx = s.mouseX;
                double ly = s.mouseY;

                if (!s.dampInit) {
                        s.dampX = lx;
                        s.dampY = ly;
                        s.prevX = lx;
                        s.prevY = ly;
                        s.svx = 0;
                        s.svy = 0;
                        s.speed = 0;
                        s.dampInit = true;
                }

                double vxf = lx - s.prevX;
                double vyf = ly - s.prevY;
                double sp = Math.sqrt(vxf * vxf + vyf * vyf);

                s.svx += (vxf - s.svx) * VEL_SMOOTH;
                s.svy += (vyf - s.svy) * VEL_SMOOTH;
                s.speed += (sp - s.speed) * VEL_SMOOTH;

                s.prevX = lx;
                s.prevY = ly;

                s.dampX += (lx - s.dampX) * PARALLAX_DAMP;
                s.dampY += (ly - s.dampY) * PARALLAX_DAMP;
        }

        /**
         * Applies parallax deformation to the container and drives
         * blob snap/flow/venom logic per frame.
         */
        private static void deform(Node node, Region blob, Canvas glass,
                        InnerShadow depth, DropShadow elevation,
                        Timeline springBack, GlassState s) {
                springBack.stop();
                double w = node.getLayoutBounds().getWidth();
                double h = node.getLayoutBounds().getHeight();
                if (w <= 0 || h <= 0)
                        return;

                if (blob != null && node instanceof Pane pane) {
                        updateBlobPosition(pane, blob, s);
                        syncCanvas(glass, blob);
                }

                // Parallax deformation
                double nx = clamp((s.dampX - w / 2) / (w / 2), -1, 1);
                double ny = clamp((s.dampY - h / 2) / (h / 2), -1, 1);
                double dist = Math.min(1.0, Math.sqrt(nx * nx + ny * ny));
                node.setTranslateX(nx * TRANSLATE_MAX * dist);
                node.setTranslateY(ny * TRANSLATE_MAX * dist);
                node.setScaleX(HOVER_SCALE - Math.abs(nx) * SCALE_SQUASH * dist
                                + Math.abs(ny) * SCALE_SQUASH * 0.3 * dist);
                node.setScaleY(HOVER_SCALE - Math.abs(ny) * SCALE_SQUASH * dist
                                + Math.abs(nx) * SCALE_SQUASH * 0.3 * dist);
                depth.setOffsetX(-nx * DEPTH_OFFSET * dist);
                depth.setOffsetY(-ny * DEPTH_OFFSET * dist);
                depth.setColor(Color.color(0, 0, 0, 0.25 * dist));
                elevation.setOffsetX(nx * 1.5);
                elevation.setOffsetY(3 + ny * 1.5);

                if (glass != null) {
                        paintGlassOverlay(glass, blob, s.dampX, s.dampY, s.speed,
                                        System.nanoTime(), s.lastClickTime, s.clickX, s.clickY);
                }
        }

        /**
         * Per-frame blob positioning: snaps to nearest control,
         * stretches via Venom, or fills the container when free.
         */
        private static void updateBlobPosition(Pane pane, Region blob, GlassState s) {
                double lx = s.mouseX;
                double ly = s.mouseY;

                Node nearest = findNearest(pane, lx, ly, ATTRACT_RADIUS);

                if (nearest != null) {
                        // Snap/flow to control
                        s.snapTarget = nearest;
                        s.venom = false;

                        Bounds b = nearest.getBoundsInParent();
                        double tx = b.getMinX() - SNAP_PAD;
                        double ty = b.getMinY() - SNAP_PAD;
                        double tw = b.getWidth() + SNAP_PAD * 2;
                        double th = b.getHeight() + SNAP_PAD * 2;

                        s.anchorCX = b.getCenterX();
                        s.anchorCY = b.getCenterY();
                        s.anchorW = tw;
                        s.anchorH = th;

                        flowTo(blob, tx, ty, tw, th);
                        settleDeformation(blob);

                } else if (s.snapTarget != null && !s.venom) {
                        // Cursor left attract zone while still snapped
                        if (inSticky(s.snapTarget, lx, ly)) {
                                clingToControl(blob, s, lx, ly);
                        } else {
                                s.venom = true;
                                s.snapTarget = null;
                        }

                } else if (s.venom) {
                        stretchFromAnchor(blob, s, lx, ly);

                } else {
                        fillContainer(blob, pane, lx, ly);
                }

                // Synthetic hover management: the blob is the visual cursor,
                // so the control it covers must show hover styling even when
                // the real cursor sits slightly outside the control bounds.
                if (s.snapTarget != s.prevSnapTarget) {
                        if (s.prevSnapTarget != null)
                                s.prevSnapTarget.getStyleClass().remove(BLOB_HOVER_CLASS);
                        if (s.snapTarget != null && !s.snapTarget.getStyleClass().contains(BLOB_HOVER_CLASS))
                                s.snapTarget.getStyleClass().add(BLOB_HOVER_CLASS);
                        s.prevSnapTarget = s.snapTarget;
                }
        }

        /** Smoothly flows the blob toward target bounds. */
        private static void flowTo(Region blob, double tx, double ty, double tw, double th) {
                blob.setLayoutX(blob.getLayoutX() + (tx - blob.getLayoutX()) * FLOW_LERP);
                blob.setLayoutY(blob.getLayoutY() + (ty - blob.getLayoutY()) * FLOW_LERP);
                blob.resize(Math.max(1, blob.getWidth() + (tw - blob.getWidth()) * FLOW_LERP),
                                Math.max(1, blob.getHeight() + (th - blob.getHeight()) * FLOW_LERP));
        }

        /** Resets scale/rotation toward neutral smoothly. */
        private static void settleDeformation(Region blob) {
                smoothRotate(blob, 0);
                blob.setScaleX(blob.getScaleX() + (1.0 - blob.getScaleX()) * 0.15);
                blob.setScaleY(blob.getScaleY() + (1.0 - blob.getScaleY()) * 0.15);
                blob.setTranslateX(blob.getTranslateX() * 0.85);
                blob.setTranslateY(blob.getTranslateY() * 0.85);
        }

        /** Keeps blob snapped to last control while stretching toward cursor. */
        private static void clingToControl(Region blob, GlassState s, double lx, double ly) {
                Bounds b = s.snapTarget.getBoundsInParent();
                double tx = b.getMinX() - SNAP_PAD;
                double ty = b.getMinY() - SNAP_PAD;
                double tw = b.getWidth() + SNAP_PAD * 2;
                double th = b.getHeight() + SNAP_PAD * 2;
                flowTo(blob, tx, ty, tw, th);

                double pullDX = lx - s.anchorCX;
                double pullDY = ly - s.anchorCY;
                double pullDist = Math.sqrt(pullDX * pullDX + pullDY * pullDY);
                if (pullDist > 3) {
                        double preStretch = 1.0 + Math.min(pullDist / 80.0, 0.15);
                        double angle = Math.toDegrees(Math.atan2(pullDY, pullDX));
                        smoothRotate(blob, angle);
                        blob.setScaleX(blob.getScaleX() + (preStretch - blob.getScaleX()) * 0.1);
                        blob.setScaleY(blob.getScaleY() + (1.0 / preStretch - blob.getScaleY()) * 0.1);
                }
        }

        /**
         * Venom mode: blob stretches from anchor toward cursor, shrinking as it
         * detaches.
         */
        private static void stretchFromAnchor(Region blob, GlassState s, double lx, double ly) {
                double blobCX = blob.getLayoutX() + blob.getWidth() / 2;
                double blobCY = blob.getLayoutY() + blob.getHeight() / 2;

                blobCX += (lx - blobCX) * VENOM_LERP;
                blobCY += (ly - blobCY) * VENOM_LERP;

                double dxA = blobCX - s.anchorCX;
                double dyA = blobCY - s.anchorCY;
                double detachDist = Math.sqrt(dxA * dxA + dyA * dyA);
                double progress = Math.min(1.0, detachDist / 100.0);

                double sz = Math.max(BLOB_MIN, s.anchorW * (1.0 - progress) + BLOB_MIN * progress);

                blob.setLayoutX(blobCX - sz / 2);
                blob.setLayoutY(blobCY - sz / 2);
                blob.resize(sz, sz);

                double stretchAmt = 1.0 + VENOM_STRETCH_MAX * (1.0 - progress);
                double angle = Math.toDegrees(Math.atan2(dyA, dxA));
                smoothRotate(blob, angle);
                blob.setScaleX(blob.getScaleX() + (stretchAmt - blob.getScaleX()) * 0.12);
                blob.setScaleY(blob.getScaleY() + (1.0 / stretchAmt - blob.getScaleY()) * 0.12);

                double toDst = Math.sqrt((blobCX - lx) * (blobCX - lx) + (blobCY - ly) * (blobCY - ly));
                if (toDst < 5)
                        s.venom = false;
        }

        /** Free mode: blob fills the container, with subtle cursor attraction. */
        private static void fillContainer(Region blob, Pane pane, double lx, double ly) {
                double w = pane.getLayoutBounds().getWidth();
                double h = pane.getLayoutBounds().getHeight();

                flowTo(blob, 0, 0, w, h);

                double attractNX = clamp((lx - w / 2.0) / (w / 2.0), -1, 1);
                double attractNY = clamp((ly - h / 2.0) / (h / 2.0), -1, 1);
                double maxShift = 4.0;
                blob.setTranslateX(blob.getTranslateX() + (attractNX * maxShift - blob.getTranslateX()) * 0.08);
                blob.setTranslateY(blob.getTranslateY() + (attractNY * maxShift - blob.getTranslateY()) * 0.08);

                settleDeformation(blob);
        }

        // ── Glass canvas rendering ──────────────────────────────────────

        /** Keeps the glass canvas aligned and sized to the blob Region. */
        private static void syncCanvas(Canvas canvas, Region blob) {
                if (canvas == null || blob == null)
                        return;
                canvas.setLayoutX(blob.getLayoutX());
                canvas.setLayoutY(blob.getLayoutY());
                double bw = blob.getWidth();
                double bh = blob.getHeight();
                if (canvas.getWidth() != bw || canvas.getHeight() != bh) {
                        canvas.setWidth(bw);
                        canvas.setHeight(bh);
                }
                canvas.setScaleX(blob.getScaleX());
                canvas.setScaleY(blob.getScaleY());
                canvas.setRotate(blob.getRotate());
                canvas.setTranslateX(blob.getTranslateX());
                canvas.setTranslateY(blob.getTranslateY());
        }

        /**
         * Paints a flashlight sweep on click — a narrow specular streak
         * that enters from the left edge, widens as it crosses the surface,
         * then tapers and fades out at the right edge.
         */
        private static void paintGlassOverlay(Canvas canvas, Region blob,
                        double dx, double dy, double speed,
                        long nowNano, long clickTime,
                        double cx, double cy) {
                double cw = canvas.getWidth();
                double ch = canvas.getHeight();
                if (cw < 2 || ch < 2)
                        return;

                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.clearRect(0, 0, cw, ch);

                if (clickTime <= 0)
                        return;

                double age = (nowNano - clickTime) / 1e9;
                double duration = 0.45;
                if (age >= duration)
                        return;

                double t = age / duration;

                // Envelope: fade-in 0→0.2, full 0.2→0.7, fade-out 0.7→1.0
                double envelope;
                if (t < 0.2)
                        envelope = t / 0.2;
                else if (t < 0.7)
                        envelope = 1.0;
                else
                        envelope = 1.0 - (t - 0.7) / 0.3;

                // Band widens as it travels, simulating a torch being
                // dragged closer to the glass surface.
                double minBand = Math.max(cw, ch) * 0.15;
                double maxBand = Math.max(cw, ch) * 0.45;
                double bandWidth = minBand + (maxBand - minBand) * t;

                // Sweep position: left edge → right edge
                double sweepX = -bandWidth + (cw + bandWidth * 2) * t;

                gc.save();
                gc.beginPath();
                gc.rect(0, 0, cw, ch);
                gc.clip();

                gc.translate(cw / 2, ch / 2);
                gc.rotate(-25);

                double offset = sweepX - cw / 2;

                double peakAlpha = 0.18 * envelope;
                LinearGradient streak = new LinearGradient(
                                offset, 0, offset + bandWidth, 0,
                                false, CycleMethod.NO_CYCLE,
                                new Stop(0.0, Color.rgb(255, 255, 255, 0.0)),
                                new Stop(0.35, Color.rgb(255, 255, 255, peakAlpha * 0.6)),
                                new Stop(0.5, Color.rgb(255, 255, 255, peakAlpha)),
                                new Stop(0.65, Color.rgb(255, 255, 255, peakAlpha * 0.6)),
                                new Stop(1.0, Color.rgb(255, 255, 255, 0.0)));

                double diag = Math.sqrt(cw * cw + ch * ch);
                gc.setFill(streak);
                gc.setGlobalBlendMode(BlendMode.SRC_OVER);
                gc.fillRect(offset - diag, -diag, diag * 2 + bandWidth, diag * 2);
                gc.restore();

                gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        }

        // ── Factory methods ─────────────────────────────────────────────

        /**
         * Creates the blob Region — the container's liquid glass surface.
         * Same size, same corner radius. A single InnerShadow simulates
         * light refraction at the meniscus.
         */
        private static Region createBlob(Node node) {
                if (!(node instanceof Pane pane))
                        return null;

                Region blob = new Region();
                blob.setMouseTransparent(true);
                blob.setManaged(false);
                blob.setOpacity(0);

                double w = pane.getWidth();
                double h = pane.getHeight();
                blob.resize(w > 0 ? w : BLOB_MIN, h > 0 ? h : BLOB_MIN);

                blob.setEffect(new InnerShadow(4, 0, 0, Color.rgb(255, 255, 255, 0.15)));

                // Pill radius (100) lets the blob adapt to any target shape.
                // Thick border (1.5px) matches Apple's prominent glass edge.
                blob.setStyle(
                                "-fx-background-color: rgba(200,200,200,0.08);"
                                                + "-fx-background-radius: 100;"
                                                + "-fx-border-color: rgba(255,255,255,0.2);"
                                                + "-fx-border-width: 1.5;"
                                                + "-fx-border-radius: 100;"
                                                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 10, 0, 0, 4);");
                pane.getChildren().add(blob);
                return blob;
        }

        /**
         * Creates an overlay Canvas for dynamic glass surface effects.
         * Uses Canvas instead of CSS because CSS cannot reposition gradients
         * relative to cursor movement within a single pulse cycle.
         */
        private static Canvas createGlassCanvas(Node node, Region blob) {
                if (!(node instanceof Pane pane) || blob == null)
                        return null;

                double initW = pane.getWidth() > 0 ? pane.getWidth() : BLOB_MIN;
                double initH = pane.getHeight() > 0 ? pane.getHeight() : BLOB_MIN;
                Canvas canvas = new Canvas(initW, initH);
                canvas.setMouseTransparent(true);
                canvas.setManaged(false);
                canvas.setOpacity(0);

                double cr = resolveCornerRadius(pane);
                Rectangle clip = new Rectangle(initW, initH);
                clip.setArcWidth(cr);
                clip.setArcHeight(cr);
                canvas.setClip(clip);
                canvas.widthProperty().addListener((obs, o, n) -> clip.setWidth(n.doubleValue()));
                canvas.heightProperty().addListener((obs, o, n) -> clip.setHeight(n.doubleValue()));

                pane.getChildren().add(canvas);
                return canvas;
        }

        // ── Setup helpers ───────────────────────────────────────────────

        /** Clips the container so the blob cannot spill outside visible bounds. */
        private static void clipContainer(Node node) {
                if (!(node instanceof Region region))
                        return;
                Rectangle clip = new Rectangle();
                clip.widthProperty().bind(region.widthProperty());
                clip.heightProperty().bind(region.heightProperty());
                double cr = resolveCornerRadius(region);
                clip.setArcWidth(cr);
                clip.setArcHeight(cr);
                region.setClip(clip);
        }

        /**
         * Guards the effect chain: if external code replaces the node's effect,
         * re-inserts it as the depth shadow's input to keep the chain intact.
         */
        private static void guardEffect(Node node, InnerShadow depth, DropShadow elevation) {
                boolean[] selfSet = { false };
                node.effectProperty().addListener((obs, o, n) -> {
                        if (selfSet[0])
                                return;
                        if (n != elevation) {
                                depth.setInput(n);
                                selfSet[0] = true;
                                node.setEffect(elevation);
                                selfSet[0] = false;
                        }
                });
        }

        private static Timeline buildSpringBack(Node node, InnerShadow depth, DropShadow elevation) {
                Timeline timeline = new Timeline();
                timeline.getKeyFrames().add(new KeyFrame(EXIT_DUR,
                                new KeyValue(node.scaleXProperty(), 1.0, SPRING),
                                new KeyValue(node.scaleYProperty(), 1.0, SPRING),
                                new KeyValue(node.translateXProperty(), 0, SPRING),
                                new KeyValue(node.translateYProperty(), 0, SPRING),
                                new KeyValue(depth.offsetXProperty(), 0, SPRING),
                                new KeyValue(depth.offsetYProperty(), 0, SPRING),
                                new KeyValue(depth.colorProperty(), Color.color(0, 0, 0, 0), SPRING),
                                new KeyValue(elevation.colorProperty(), Color.color(0, 0, 0, 0), SPRING)));
                timeline.setOnFinished(e -> node.getStyleClass().remove(HOVER_CLASS));
                return timeline;
        }

        // ── Utilities ───────────────────────────────────────────────────

        /** Smoothly rotates blob toward target angle, handling 360° wrap. */
        private static void smoothRotate(Region blob, double target) {
                double current = blob.getRotate();
                double diff = target - current;
                while (diff > 180)
                        diff -= 360;
                while (diff < -180)
                        diff += 360;
                blob.setRotate(current + diff * 0.12);
        }

        /**
         * Resolves corner radius for the container.
         * CSS background-radius isn't queryable from Region, so we
         * use a heuristic: pill containers use their smallest dimension.
         */
        private static double resolveCornerRadius(Region region) {
                double h = region.getHeight();
                if (h <= 0)
                        h = region.getMinHeight();
                if (h <= 0)
                        h = 32;
                double w = region.getWidth();
                if (w > 0 && w < h)
                        return w;
                return h;
        }

        /**
         * Proximity-based snap: finds the nearest interactive child within radius.
         * Skips non-visible, non-managed, mouse-transparent, and ignored nodes.
         */
        private static Node findNearest(Pane parent, double lx, double ly, double radius) {
                Node nearest = null;
                double minDist = radius;
                for (Node c : parent.getChildren()) {
                        if (!c.isManaged() || !c.isVisible() || c.isMouseTransparent()
                                        || c.getProperties().containsKey("liquid-glass-ignore"))
                                continue;
                        double d = distToBounds(c.getBoundsInParent(), lx, ly);
                        if (d < minDist) {
                                minDist = d;
                                nearest = c;
                        }
                }
                return nearest;
        }

        /** Euclidean distance from point to axis-aligned bounding box. */
        private static double distToBounds(Bounds b, double x, double y) {
                double dx = Math.max(0, Math.max(b.getMinX() - x, x - b.getMaxX()));
                double dy = Math.max(0, Math.max(b.getMinY() - y, y - b.getMaxY()));
                return Math.sqrt(dx * dx + dy * dy);
        }

        private static boolean inSticky(Node target, double lx, double ly) {
                Bounds b = target.getBoundsInParent();
                return lx >= b.getMinX() - STICKY_RADIUS && lx <= b.getMaxX() + STICKY_RADIUS
                                && ly >= b.getMinY() - STICKY_RADIUS && ly <= b.getMaxY() + STICKY_RADIUS;
        }

        // ── Cursor management ───────────────────────────────────────────

        /**
         * Enforces NONE cursor on both the container and the event target.
         * The Liquid Glass blob acts as the cursor replacement.
         */
        private static void forceCursor(Node n, MouseEvent e) {
                n.setCursor(Cursor.NONE);
                if (e.getTarget() instanceof Node target && target != n) {
                        target.setCursor(Cursor.NONE);
                }
        }

        /** Forces NONE cursor on the Scene every frame while hovered. */
        private static void enforceCursorNone(Node node) {
                if (node.getScene() != null && node.getScene().getCursor() != Cursor.NONE) {
                        node.getScene().setCursor(Cursor.NONE);
                }
        }

        /** Locks the Scene cursor to NONE and installs a listener to re-enforce it. */
        private static void lockSceneCursor(Node node, GlassState state) {
                if (node.getScene() == null)
                        return;
                node.getScene().setCursor(Cursor.NONE);
                node.getScene().cursorProperty().addListener((obs, old, val) -> {
                        if ((state.hovered || state.pressed) && val != Cursor.NONE) {
                                node.getScene().setCursor(Cursor.NONE);
                        }
                });
        }

        /** Saves children's cursors and replaces them with NONE. */
        private static void saveCursors(Pane parent, Map<Node, Cursor> saved) {
                saved.clear();
                for (Node child : parent.getChildren()) {
                        Cursor c = child.getCursor();
                        if (c != null)
                                saved.put(child, c);
                        child.setCursor(Cursor.NONE);
                }
        }

        private static void restoreCursors(Pane parent, Map<Node, Cursor> saved) {
                for (Node child : parent.getChildren()) {
                        child.setCursor(saved.get(child));
                }
                saved.clear();
        }

        /** Resets timer, damping, snap, venom, and cursor state on hover end. */
        private static void cleanupHoverState(Node node, GlassState state, AnimationTimer timer) {
                timer.stop();
                state.dampInit = false;

                // Remove synthetic hover from whatever control the blob was covering
                if (state.snapTarget != null)
                        state.snapTarget.getStyleClass().remove(BLOB_HOVER_CLASS);
                if (state.prevSnapTarget != null && state.prevSnapTarget != state.snapTarget)
                        state.prevSnapTarget.getStyleClass().remove(BLOB_HOVER_CLASS);
                state.snapTarget = null;
                state.prevSnapTarget = null;
                state.venom = false;

                if (node instanceof Pane pn)
                        restoreCursors(pn, state.savedCursors);
                node.setCursor(Cursor.DEFAULT);
                if (node.getScene() != null)
                        node.getScene().setCursor(Cursor.DEFAULT);
        }

        private static double clamp(double v, double min, double max) {
                return Math.max(min, Math.min(max, v));
        }
}
