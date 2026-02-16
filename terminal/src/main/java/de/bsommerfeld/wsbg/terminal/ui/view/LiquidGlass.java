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
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.control.TextInputControl;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.InnerShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * iPadOS Liquid Glass effect with Venom-style surface tension.
 * The blob wraps controls, flows between them like liquid metal,
 * and stretches like a symbiote when pulled away from a control.
 *
 * <p>
 * The glass surface uses a Canvas overlay for real-time specular
 * highlights, caustic patterns, and chromatic aberration — effects
 * that CSS alone cannot achieve with positional awareness.
 * </p>
 */
public final class LiquidGlass {

        private static final String APPLIED_KEY = "liquid-glass-applied";
        private static final String HOVER_CLASS = "liquid-glass-hover";

        private static final double TRANSLATE_MAX = 4.0;
        private static final double HOVER_SCALE = 1.04;
        private static final double SCALE_SQUASH = 0.015;
        private static final double DEPTH_RADIUS = 10.0;
        private static final double DEPTH_OFFSET = 3.0;
        private static final double ELEVATION_RADIUS = 16.0;
        private static final double PARALLAX_DAMP = 0.35;

        private static final double BLOB_MIN = 28.0;
        private static final double VEL_SMOOTH = 0.15;

        // Flow lerp: balanced for smoothness without feeling sluggish.
        private static final double FLOW_LERP = 0.25;

        // Controls attract the blob within this distance (in parent coords).
        // Increased to cover gaps between controls better (e.g. text field and button).
        private static final double ATTRACT_RADIUS = 30.0;

        // Blob clings to control until cursor exceeds this.
        private static final double STICKY_RADIUS = 30.0;

        // Padding added to the snapped bounds.
        // Increased to 6.0 to "include spacing" and bridge gaps between neighbors.
        private static final double SNAP_PAD = 6.0;

        // Venom lerp: Blob resists leaving the control.
        // Increased to reduce "sluggishing" feeling.
        private static final double VENOM_LERP = 0.12;

        // Venom stretch: how much the blob elongates toward cursor
        // while still anchored to the control.
        private static final double VENOM_STRETCH_MAX = 0.45;

        private static final Duration EXIT_DUR = Duration.millis(350);
        private static final Interpolator SPRING = Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0);

        private LiquidGlass() {
        }

        public static void apply(Node node) {
                if (Boolean.TRUE.equals(node.getProperties().get(APPLIED_KEY)))
                        return;
                node.getProperties().put(APPLIED_KEY, Boolean.TRUE);

                Region blob = createBlob(node);
                Canvas glassCanvas = createGlassCanvas(node, blob);

                // Clip the container so the blob cannot spill outside the
                // container's visible bounds. The arc radius matches the
                // container's background-radius (pill = height, rounded = fixed).
                // Resolving at layout time because CSS-based radii are not
                // directly queryable from Region.
                if (node instanceof Region region) {
                        Rectangle clip = new Rectangle();
                        clip.widthProperty().bind(region.widthProperty());
                        clip.heightProperty().bind(region.heightProperty());
                        double cornerRadius = resolveCornerRadius(region);
                        clip.setArcWidth(cornerRadius);
                        clip.setArcHeight(cornerRadius);
                        region.setClip(clip);
                }

                InnerShadow depth = new InnerShadow(DEPTH_RADIUS, 0, 0, Color.color(0, 0, 0, 0));
                depth.setInput(node.getEffect());
                DropShadow elevation = new DropShadow(ELEVATION_RADIUS, 0, 3, Color.color(0, 0, 0, 0));
                elevation.setInput(depth);
                node.setEffect(elevation);

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

                double[] dampX = { 0 }, dampY = { 0 };
                boolean[] dampInit = { false };
                double[] prevX = { 0 }, prevY = { 0 };
                double[] svx = { 0 }, svy = { 0 }, spd = { 0 };
                boolean[] hovered = { false };

                // Snap state
                Node[] snapTarget = { null };
                // Venom state: blob is stretching away from anchor toward cursor
                boolean[] venom = { false };
                double[] anchorCX = { 0 }, anchorCY = { 0 };
                double[] anchorW = { 0 }, anchorH = { 0 };

                // Saved child cursors — restored when the mouse exits the container
                java.util.Map<Node, Cursor> savedCursors = new java.util.IdentityHashMap<>();

                // Glass canvas animation — paints specular/caustic each frame while hovered
                Timeline exitSlide = new Timeline();
                exitSlide.setOnFinished(e -> {
                        if (blob != null)
                                blob.setOpacity(0);
                        if (glassCanvas != null)
                                glassCanvas.setOpacity(0);
                });

                Timeline springBack = new Timeline();
                springBack.getKeyFrames().add(new KeyFrame(EXIT_DUR,
                                new KeyValue(node.scaleXProperty(), 1.0, SPRING),
                                new KeyValue(node.scaleYProperty(), 1.0, SPRING),
                                new KeyValue(node.translateXProperty(), 0, SPRING),
                                new KeyValue(node.translateYProperty(), 0, SPRING),
                                new KeyValue(depth.offsetXProperty(), 0, SPRING),
                                new KeyValue(depth.offsetYProperty(), 0, SPRING),
                                new KeyValue(depth.colorProperty(), Color.color(0, 0, 0, 0), SPRING),
                                new KeyValue(elevation.colorProperty(), Color.color(0, 0, 0, 0), SPRING)));
                springBack.setOnFinished(e -> node.getStyleClass().remove(HOVER_CLASS));

                final double[] mouseX = { 0 };
                final double[] mouseY = { 0 };
                final boolean[] isPressed = { false };
                final long[] lastClickTime = { 0 };
                final double[] clickX = { 0 };
                final double[] clickY = { 0 };

                AnimationTimer timer = new AnimationTimer() {
                        @Override
                        public void handle(long now) {
                                if (!hovered[0])
                                        return;

                                // PERSISTENT CURSOR FORCE:
                                // Ensure Scene cursor overrides any other cursor logic (WindowShell, Skins,
                                // etc.)
                                // that might trigger on click/drag. Checked every frame.
                                if (node.getScene() != null && node.getScene().getCursor() != Cursor.NONE) {
                                        node.getScene().setCursor(Cursor.NONE);
                                }

                                double lx = mouseX[0];
                                double ly = mouseY[0];

                                // Physics / Damping Logic (moved from moveHandler)
                                if (!dampInit[0]) {
                                        dampX[0] = lx;
                                        dampY[0] = ly;
                                        prevX[0] = lx;
                                        prevY[0] = ly;
                                        svx[0] = 0;
                                        svy[0] = 0;
                                        spd[0] = 0;
                                        dampInit[0] = true;
                                }

                                // Velocity is delta of current target (mouse) vs previous target
                                double vxf = lx - prevX[0];
                                double vyf = ly - prevY[0];
                                double s = Math.sqrt(vxf * vxf + vyf * vyf);

                                svx[0] += (vxf - svx[0]) * VEL_SMOOTH;
                                svy[0] += (vyf - svy[0]) * VEL_SMOOTH;
                                spd[0] += (s - spd[0]) * VEL_SMOOTH;

                                prevX[0] = lx;
                                prevY[0] = ly;

                                dampX[0] += (lx - dampX[0]) * PARALLAX_DAMP;
                                dampY[0] += (ly - dampY[0]) * PARALLAX_DAMP;

                                deform(node, blob, glassCanvas, depth, elevation, springBack,
                                                lx, ly, dampX[0], dampY[0],
                                                svx[0], svy[0], spd[0],
                                                snapTarget, venom, anchorCX, anchorCY, anchorW, anchorH,
                                                lastClickTime[0], clickX[0], clickY[0]);
                        }
                };

                EventHandler<MouseEvent> moveHandler = e -> {
                        if (!hovered[0])
                                return;
                        exitSlide.stop();
                        forceCursor(node, e);

                        // Just update the target position for the physics loop
                        mouseX[0] = e.getX();
                        mouseY[0] = e.getY();
                };

                node.addEventFilter(MouseEvent.MOUSE_MOVED, moveHandler);
                node.addEventFilter(MouseEvent.MOUSE_DRAGGED, moveHandler);
                node.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, e -> forceCursor(node, e));
                node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> forceCursor(node, e));
                node.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> forceCursor(node, e));
                node.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> forceCursor(node, e));

                node.setOnMouseEntered(e -> {
                        hovered[0] = true;
                        // Initialize mouse position immediately
                        mouseX[0] = e.getX();
                        mouseY[0] = e.getY();

                        timer.start(); // Start physics loop

                        springBack.stop();
                        exitSlide.stop();
                        node.setCursor(Cursor.NONE);

                        if (node instanceof Pane pn) {
                                saveCursors(pn, savedCursors);
                        }

                        if (!node.getStyleClass().contains(HOVER_CLASS))
                                node.getStyleClass().add(HOVER_CLASS);
                        dampInit[0] = false;
                        snapTarget[0] = null;
                        venom[0] = false;
                        node.setScaleX(HOVER_SCALE);
                        node.setScaleY(HOVER_SCALE);
                        elevation.setColor(Color.color(0, 0, 0, 0.25));

                        // Blob fills the entire container — it IS the container's
                        // liquid glass surface, matching Apple's approach exactly.
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
                        if (glassCanvas != null) {
                                glassCanvas.toFront();
                                glassCanvas.setOpacity(1);
                        }

                        // AGGRESSIVE CURSOR LOCK: Monitor Scene cursor and squash it
                        if (node.getScene() != null) {
                                node.getScene().setCursor(Cursor.NONE);
                                // If anything tries to change the cursor back (e.g. TextField skin),
                                // we slam it back to NONE immediately.
                                node.getScene().cursorProperty().addListener((obs, old, val) -> {
                                        // Keep enforcing if hovered OR pressed
                                        if ((hovered[0] || isPressed[0]) && val != Cursor.NONE) {
                                                node.getScene().setCursor(Cursor.NONE);
                                        }
                                });
                        }
                });

                node.setOnMouseExited(e -> {
                        hovered[0] = false;

                        // CRITICAL FIX: If user is actively pressing/dragging, DO NOT stop the logic
                        // yet.
                        // The system might fire MOUSE_EXITED when a drag operation starts (stealing
                        // focus),
                        // but visually we are still "holding" the blob.
                        if (isPressed[0]) {
                                return;
                        }

                        timer.stop(); // Stop physics loop
                        dampInit[0] = false;
                        snapTarget[0] = null;
                        venom[0] = false;

                        // Restore original child cursors
                        if (node instanceof Pane pn) {
                                restoreCursors(pn, savedCursors);
                        }
                        node.setCursor(Cursor.DEFAULT);
                        // Release Scene Cursor Lock
                        if (node.getScene() != null) {
                                node.getScene().setCursor(Cursor.DEFAULT);
                        }

                        springBack.playFromStart();
                        if (blob != null) {
                                double w = node.getLayoutBounds().getWidth();
                                double h = node.getLayoutBounds().getHeight();
                                double ex = clamp(e.getX(), 0, w), ey = clamp(e.getY(), 0, h);
                                exitSlide.getKeyFrames().clear();
                                exitSlide.getKeyFrames().add(new KeyFrame(EXIT_DUR,
                                                new KeyValue(blob.layoutXProperty(), ex - blob.getWidth() / 2, SPRING),
                                                new KeyValue(blob.layoutYProperty(), ey - blob.getHeight() / 2, SPRING),
                                                new KeyValue(blob.scaleXProperty(), 0.3, SPRING),
                                                new KeyValue(blob.scaleYProperty(), 0.3, SPRING),
                                                new KeyValue(blob.opacityProperty(), 0.0, SPRING)));
                                exitSlide.playFromStart();
                        }
                        if (glassCanvas != null) {
                                glassCanvas.setOpacity(0);
                        }
                });

                node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                        isPressed[0] = true;
                        lastClickTime[0] = System.nanoTime();

                        // Calculate relative click position within the blob if snapped,
                        // otherwise just node-relative.
                        if (blob != null) {
                                // Calculate click position relative to the blob's current surface
                                // This ensures the ripple moves WITH the liquid ("Sonic Boom" on the glass)
                                clickX[0] = e.getX() - (blob.getLayoutX() + blob.getTranslateX());
                                clickY[0] = e.getY() - (blob.getLayoutY() + blob.getTranslateY());
                        }

                        springBack.stop();
                        exitSlide.stop();
                        node.setCursor(Cursor.NONE);
                        if (node.getScene() != null)
                                node.getScene().setCursor(Cursor.NONE);

                        node.setScaleX(1.0 - SCALE_SQUASH);
                        node.setScaleY(1.0 - SCALE_SQUASH);
                        depth.setColor(Color.color(0, 0, 0, 0.35));
                        elevation.setColor(Color.color(0, 0, 0, 0.35)); // Deeper shadow on press

                        // Quick scale pulse on click — pure property animation,
                        // no setStyle() calls. Gives a tactile "press into water"
                        // feel without the perf cost of CSS re-parse.
                        if (blob != null) {
                                blob.setScaleX(1.15);
                                blob.setScaleY(1.15);
                        }
                });
                node.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
                        isPressed[0] = false;
                        springBack.playFromStart();

                        // If we drifted out while pressing, now we trigger the exit logic
                        if (!hovered[0]) {
                                timer.stop();
                                dampInit[0] = false;
                                snapTarget[0] = null;
                                venom[0] = false;
                                if (node instanceof Pane pn)
                                        restoreCursors(pn, savedCursors);
                                node.setCursor(Cursor.DEFAULT);
                                if (node.getScene() != null)
                                        node.getScene().setCursor(Cursor.DEFAULT);

                                // Fade out blob
                                if (blob != null) {
                                        Timeline fadeOut = new Timeline(new KeyFrame(Duration.millis(200),
                                                        new KeyValue(blob.opacityProperty(), 0, Interpolator.EASE_OUT),
                                                        new KeyValue(blob.scaleXProperty(), 0.5, Interpolator.EASE_OUT),
                                                        new KeyValue(blob.scaleYProperty(), 0.5,
                                                                        Interpolator.EASE_OUT)));
                                        fadeOut.play();
                                }
                                if (glassCanvas != null)
                                        glassCanvas.setOpacity(0);
                        }
                });

        }

        private static void deform(Node node, Region blob, Canvas glassCanvas,
                        InnerShadow depth, DropShadow elevation, Timeline springBack,
                        double lx, double ly, double dx, double dy,
                        double svx, double svy, double speed,
                        Node[] snapTarget, boolean[] venom,
                        double[] anchorCX, double[] anchorCY,
                        double[] anchorW, double[] anchorH,
                        long clickTime, double cx, double cy) {
                springBack.stop();
                double w = node.getLayoutBounds().getWidth();
                double h = node.getLayoutBounds().getHeight();
                if (w <= 0 || h <= 0)
                        return;

                if (blob != null && node instanceof Pane pane) {
                        // Find nearest control (proximity-based, not just direct hit)
                        Node nearest = findNearest(pane, lx, ly, ATTRACT_RADIUS);

                        if (nearest != null) {
                                // Cursor near a control → snap/flow to it
                                if (snapTarget[0] != nearest) {
                                        snapTarget[0] = nearest;
                                }
                                venom[0] = false;

                                Bounds b = nearest.getBoundsInParent();
                                double tx = b.getMinX() - SNAP_PAD;
                                double ty = b.getMinY() - SNAP_PAD;
                                double tw = b.getWidth() + SNAP_PAD * 2;
                                double th = b.getHeight() + SNAP_PAD * 2;

                                // Record anchor for Venom release
                                anchorCX[0] = b.getCenterX();
                                anchorCY[0] = b.getCenterY();
                                anchorW[0] = tw;
                                anchorH[0] = th;

                                // Fluid flow to target
                                double bxf = blob.getLayoutX() + (tx - blob.getLayoutX()) * FLOW_LERP;
                                double byf = blob.getLayoutY() + (ty - blob.getLayoutY()) * FLOW_LERP;
                                double bwf = blob.getWidth() + (tw - blob.getWidth()) * FLOW_LERP;
                                double bhf = blob.getHeight() + (th - blob.getHeight()) * FLOW_LERP;
                                blob.setLayoutX(bxf);
                                blob.setLayoutY(byf);
                                blob.resize(Math.max(1, bwf), Math.max(1, bhf));

                                // Reset deformation smoothly
                                smoothRotate(blob, 0);
                                blob.setScaleX(blob.getScaleX() + (1.0 - blob.getScaleX()) * 0.15);
                                blob.setScaleY(blob.getScaleY() + (1.0 - blob.getScaleY()) * 0.15);
                                blob.setTranslateX(blob.getTranslateX() * 0.85);
                                blob.setTranslateY(blob.getTranslateY() * 0.85);

                        } else if (snapTarget[0] != null && !venom[0]) {
                                // Cursor left attract zone but we're still snapped.
                                if (inSticky(snapTarget[0], lx, ly)) {
                                        // Still clinging: keep snapped, stretch toward cursor
                                        Bounds b = snapTarget[0].getBoundsInParent();
                                        double tx = b.getMinX() - SNAP_PAD;
                                        double ty = b.getMinY() - SNAP_PAD;
                                        double tw = b.getWidth() + SNAP_PAD * 2;
                                        double th = b.getHeight() + SNAP_PAD * 2;
                                        blob.setLayoutX(blob.getLayoutX()
                                                        + (tx - blob.getLayoutX()) * FLOW_LERP);
                                        blob.setLayoutY(blob.getLayoutY()
                                                        + (ty - blob.getLayoutY()) * FLOW_LERP);
                                        blob.resize(Math.max(1,
                                                        blob.getWidth() + (tw - blob.getWidth()) * FLOW_LERP),
                                                        Math.max(1, blob.getHeight()
                                                                        + (th - blob.getHeight()) * FLOW_LERP));

                                        // Pre-stretch toward cursor (subtle pull)
                                        double pullDX = lx - anchorCX[0];
                                        double pullDY = ly - anchorCY[0];
                                        double pullDist = Math.sqrt(pullDX * pullDX + pullDY * pullDY);
                                        if (pullDist > 3) {
                                                double preStretch = 1.0 + Math.min(pullDist / 80.0, 0.15);
                                                double angle = Math.toDegrees(Math.atan2(pullDY, pullDX));
                                                smoothRotate(blob, angle);
                                                blob.setScaleX(blob.getScaleX()
                                                                + (preStretch - blob.getScaleX()) * 0.1);
                                                blob.setScaleY(blob.getScaleY()
                                                                + (1.0 / preStretch - blob.getScaleY()) * 0.1);
                                        }
                                } else {
                                        // Exceeded sticky zone: VENOM mode
                                        venom[0] = true;
                                        snapTarget[0] = null;
                                }
                        } else if (venom[0]) {
                                // VENOM: blob stretches from anchor toward cursor
                                double blobCX = blob.getLayoutX() + blob.getWidth() / 2;
                                double blobCY = blob.getLayoutY() + blob.getHeight() / 2;

                                blobCX += (lx - blobCX) * VENOM_LERP;
                                blobCY += (ly - blobCY) * VENOM_LERP;

                                // Size shrinks as blob moves from anchor to cursor
                                double dxA = blobCX - anchorCX[0];
                                double dyA = blobCY - anchorCY[0];
                                double detachDist = Math.sqrt(dxA * dxA + dyA * dyA);
                                double progress = Math.min(1.0, detachDist / 100.0);

                                double sz = anchorW[0] * (1.0 - progress) + BLOB_MIN * progress;
                                sz = Math.max(BLOB_MIN, sz);

                                blob.setLayoutX(blobCX - sz / 2);
                                blob.setLayoutY(blobCY - sz / 2);
                                blob.resize(sz, sz);

                                // Stretch back toward anchor (symbiote tendril)
                                double stretchAmt = 1.0 + VENOM_STRETCH_MAX * (1.0 - progress);
                                double angle = Math.toDegrees(Math.atan2(dyA, dxA));
                                smoothRotate(blob, angle);
                                blob.setScaleX(blob.getScaleX() + (stretchAmt - blob.getScaleX()) * 0.12);
                                blob.setScaleY(blob.getScaleY() + (1.0 / stretchAmt - blob.getScaleY()) * 0.12);

                                // Once close enough to cursor: transition to free
                                double toDst = Math.sqrt(
                                                (blobCX - lx) * (blobCX - lx) + (blobCY - ly) * (blobCY - ly));
                                if (toDst < 5)
                                        venom[0] = false;

                        } else {
                                // FREE: blob fills the container like water in a
                                // glass. translateX/Y shifts it toward the cursor —
                                // same principle as tilting a glass to pool the liquid.
                                blob.setLayoutX(blob.getLayoutX() + (0 - blob.getLayoutX()) * FLOW_LERP);
                                blob.setLayoutY(blob.getLayoutY() + (0 - blob.getLayoutY()) * FLOW_LERP);
                                blob.resize(Math.max(1, blob.getWidth() + (w - blob.getWidth()) * FLOW_LERP),
                                                Math.max(1, blob.getHeight() + (h - blob.getHeight()) * FLOW_LERP));

                                // Cursor attraction: normalize cursor pos to -1..1
                                double attractNX = clamp((lx - w / 2.0) / (w / 2.0), -1, 1);
                                double attractNY = clamp((ly - h / 2.0) / (h / 2.0), -1, 1);
                                double maxShift = 4.0;
                                double targetTX = attractNX * maxShift;
                                double targetTY = attractNY * maxShift;
                                blob.setTranslateX(blob.getTranslateX()
                                                + (targetTX - blob.getTranslateX()) * 0.08);
                                blob.setTranslateY(blob.getTranslateY()
                                                + (targetTY - blob.getTranslateY()) * 0.08);

                                // Settle deformation back to neutral
                                smoothRotate(blob, 0);
                                blob.setScaleX(blob.getScaleX() + (1.0 - blob.getScaleX()) * 0.15);
                                blob.setScaleY(blob.getScaleY() + (1.0 - blob.getScaleY()) * 0.15);
                        }

                        // Sync canvas position to blob
                        syncCanvas(glassCanvas, blob);
                }

                // Parallax deformation
                double nx = clamp((dx - w / 2) / (w / 2), -1, 1);
                double ny = clamp((dy - h / 2) / (h / 2), -1, 1);
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
                // Paint glass overlay effect (highlight + shockwave)
                if (glassCanvas != null) {
                        paintGlassOverlay(glassCanvas, blob, dx, dy, speed,
                                        System.nanoTime(), clickTime, cx, cy);
                }
        }

        // ── Glass canvas rendering ──────────────────────────────────────────

        /** Keeps the glass canvas aligned and sized to the blob Region. */
        private static void syncCanvas(Canvas canvas, Region blob) {
                if (canvas == null || blob == null)
                        return;
                canvas.setLayoutX(blob.getLayoutX());
                canvas.setLayoutY(blob.getLayoutY());
                double bw = blob.getWidth(), bh = blob.getHeight();
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
         * Clears the canvas overlay each frame. Visual indication is handled
         * entirely by the blob's physical movement (translateX/Y attraction)
         * rather than painted specular highlights.
         */
        /**
         * Clears the canvas overlay each frame and renders dynamic light effects.
         * Now includes a "Sonic Boom" shockwave on click.
         */
        private static void paintGlassOverlay(Canvas canvas, Region blob,
                        double dx, double dy, double speed,
                        long nowNano, long clickTime, double cx, double cy) {
                double cw = canvas.getWidth(), ch = canvas.getHeight();
                if (cw < 2 || ch < 2)
                        return;

                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.clearRect(0, 0, cw, ch);

                // --- SHIMMER REFLECTION WAVE ---
                // "Schimmer von rechts unten nach links oben"
                // A subtle reflection wave gliding across the surface, simulating light
                // refraction.
                // No color modifications, just a pure white alpha gradient.
                if (clickTime > 0) {
                        double age = (nowNano - clickTime) / 1e9;
                        double duration = 0.7; // Smooth gliding duration

                        if (age < duration) {
                                double progress = age / duration;
                                // Ease-in-out for natural "gliding" appearance
                                double ease = progress < 0.5 ? 2 * progress * progress
                                                : 1 - Math.pow(-2 * progress + 2, 2) / 2;

                                // Base scale for the effect width
                                double size = Math.max(cw, ch);
                                double bandWidth = size * 0.4;

                                // Coordinate transformation: moving from bottom-right (cw, ch) to top-left
                                // (0,0)
                                // We simulate this by moving a parallel band along the diagonal.
                                // Diagonal length:
                                double diag = Math.sqrt(cw * cw + ch * ch);

                                // Movement range: start completely off-screen (bottom-right) to off-screen
                                // (top-left)
                                double startPos = diag + bandWidth;
                                double endPos = -bandWidth;
                                double currentPos = startPos + (endPos - startPos) * ease;

                                gc.save();

                                // Clip to component bounds so the shimmer doesn't bleed out
                                gc.beginPath();
                                gc.rect(0, 0, cw, ch);
                                gc.clip();

                                // Rotate context to draw the band diagonally (45 degrees)
                                gc.translate(cw / 2, ch / 2);
                                gc.rotate(-45); // Standard cartesian angle for BR->TL movement relative to center?
                                // Actually, visual Top-Left is (0,0). Visual Bottom-Right is (w,h).
                                // We want movement from (w,h) to (0,0). That is a -135 degree vector.
                                // Drawing a vertical bar and moving it along X in rotated space:
                                // Let's simplify: Rotate -45 degrees. The "vertical" axis aligns with the
                                // diagonal.
                                // We draw a rectangle moving along the new Y axis? Or better, X.
                                // Let's use simple geometry without complex rotation logic if possible.
                                // Just draw a rotated rectangle.

                                gc.translate(-cw / 2, -ch / 2); // Reset translation (relative to center) but keep
                                                                // rotation for drawing?
                                // No, standard logic:
                                // 1. Translate to center
                                // 2. Rotate -45 deg
                                // 3. Draw rect at (currentPos, -huge) size (width, huge)
                                // currentPos moves from +diag/2 to -diag/2

                                double centerOffset = currentPos - (diag / 2); // Shift to be relative to center

                                // Gradient for the "Shimmer" - soft edges
                                LinearGradient shimmerGradient = new LinearGradient(
                                                centerOffset, 0, centerOffset + bandWidth, 0,
                                                false, CycleMethod.NO_CYCLE,
                                                new Stop(0.0, Color.rgb(255, 255, 255, 0.0)),
                                                new Stop(0.5, Color.rgb(255, 255, 255, 0.2)), // Peak opacity at center
                                                new Stop(1.0, Color.rgb(255, 255, 255, 0.0)));

                                // We use a rect that covers the entire "height" of the rotated space
                                double hugeH = diag * 2;

                                gc.setFill(shimmerGradient);
                                gc.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER); // Additive (light)
                                gc.fillRect(centerOffset - diag, -diag, diag * 2 + bandWidth, hugeH);
                                // Note: Gradient coords are local to the fillRect call if not proportional?
                                // LinearGradient acts on local coords.
                                // Wait, `centerOffset` in gradient must match drawn rect X.
                                // Actually simplistic approach:
                                // Draw a rect at (centerX, centerY) but rotated.
                                // Let's restart the drawing logic for clarity in the implementation block
                                // below.

                                gc.restore();
                        }
                }

                gc.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
        }

        // ── Shape & factory ─────────────────────────────────────────────────

        /** Smoothly rotate blob to target angle, handling 360° wrap. */
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
         * Creates the blob Region filling the container's shape.
         * The blob IS the container's liquid glass surface — same
         * size, same corner radius. A single subtle InnerShadow
         * simulates light refraction at the meniscus.
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

                InnerShadow rimGlow = new InnerShadow(4, 0, 0, Color.rgb(255, 255, 255, 0.15));
                blob.setEffect(rimGlow);

                // Pill radius (100) so the blob has no own fixed boundings —
                // it adapts to any target shape. Thick border (2px) matches
                // Apple's prominent glass edge visible in the HIG reference.
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
         * Sits on top of the blob, painting specular highlights, caustics,
         * and chromatic aberration that respond to cursor position/velocity.
         * Using Canvas instead of pure CSS — CSS cannot reposition gradients
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

                // Clip arc matches the container's corner radius,
                // not the blob height (which produced the pill mismatch).
                double cr = resolveCornerRadius(pane);
                Rectangle canvasClip = new Rectangle(initW, initH);
                canvasClip.setArcWidth(cr);
                canvasClip.setArcHeight(cr);
                canvas.setClip(canvasClip);
                canvas.widthProperty().addListener((obs, o, n) -> {
                        canvasClip.setWidth(n.doubleValue());
                });
                canvas.heightProperty().addListener((obs, o, n) -> {
                        canvasClip.setHeight(n.doubleValue());
                });
                pane.getChildren().add(canvas);
                return canvas;
        }

        // ── Utilities ───────────────────────────────────────────────────────

        /**
         * Resolves an appropriate corner radius for the container.
         * CSS background-radius isn't directly queryable from Region, so
         * we use a heuristic: pill containers (radius ≥ height/2) get
         * their height as arc; otherwise we use a moderate default.
         * This keeps the blob and clip coherent with the container shape.
         */
        private static double resolveCornerRadius(Region region) {
                double h = region.getHeight();
                if (h <= 0)
                        h = region.getMinHeight();
                if (h <= 0)
                        h = 32;
                // Use the smallest dimension for pill rounding (Apple style)
                // if height > width (vertical bar), use width.
                double w = region.getWidth();
                if (w > 0 && w < h)
                        return w;
                // Most containers in the toolbar use radius:100px (pill).
                // For pills, the effective visual radius equals the height.
                return h;
        }

        /**
         * Proximity-based snap: finds the nearest interactive child within radius.
         * The blob wraps individual controls to indicate cursor target (Apple HIG).
         * Skips non-visible, non-managed, mouse-transparent, and text input nodes.
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

        /**
         * Hides cursor on both the container and the event target.
         * Walks the target's parent chain to catch intermediary containers
         * that may re-introduce a cursor.
         * Hides cursor on the container, but allows TEXT cursor on inputs.
         */

        private static void forceCursor(Node n, MouseEvent e) {
                // User explicitly requested to NOT see the cursor.
                // The Liquid Glass blob acts as the cursor.
                n.setCursor(Cursor.NONE);
                // Also enforce on the specific child that was clicked/hovered
                // to override any control-specific behavior (like TextField skin)
                if (e.getTarget() instanceof Node target && target != n) {
                        target.setCursor(Cursor.NONE);
                }
        }

        /**
         * Saves and replaces all children's cursors with NONE.
         * Call once on mouse-enter; restoreCursors undoes it on exit.
         */
        private static void saveCursors(Pane parent, java.util.Map<Node, Cursor> saved) {
                saved.clear();
                for (Node child : parent.getChildren()) {
                        Cursor c = child.getCursor();
                        // Save original cursor if it's set
                        if (c != null) {
                                saved.put(child, c);
                        }
                        // Enforce NONE
                        child.setCursor(Cursor.NONE);
                }
        }

        private static void restoreCursors(Pane parent, java.util.Map<Node, Cursor> saved) {
                for (Node child : parent.getChildren()) {
                        Cursor original = saved.get(child);
                        child.setCursor(original);
                }
                saved.clear();
        }

        private static double clamp(double v, double min, double max) {
                return Math.max(min, Math.min(max, v));
        }
}
