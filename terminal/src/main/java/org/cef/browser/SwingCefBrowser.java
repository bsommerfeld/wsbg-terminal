// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// WSBG fork of org.cef.browser.CefBrowserOsr: a software (Swing) off-screen
// renderer. Lives in package org.cef.browser on purpose — CefBrowser_N is
// package-private with the native JNI hooks, so a faithful OSR browser can
// only be built from inside this package.
//
// Why a fork at all: jcef's stock CefBrowserOsr paints into a JOGL GLCanvas
// whose renderer (CefRenderer) does a black glClear + draws a red/blue debug
// gradient on every frame, and uploads the browser texture on a different
// thread than it presents. On repaint-heavy moments (mouse move, live resize)
// the GLCanvas catches that intermediate state → hard flicker. It also drags
// in the JOGL natives (x86_64-only in jcefmaven's bundle → arm64 crash) and
// JOGL's threads complicate shutdown. This impl drops OpenGL entirely: CEF's
// onPaint BGRA buffer is blitted into a BufferedImage drawn by a Swing
// component. Swing double-buffers, so there is no flicker; there is no GL, so
// no arm64 native, no AppKit-thread GL exceptions, and clean teardown. Live
// resize tracks because Swing repaints the image at the new size each step.

package org.cef.browser;

import de.bsommerfeld.wsbg.terminal.ui.scroll.PassthroughWheelScrollPolicy;
import de.bsommerfeld.wsbg.terminal.ui.scroll.WheelScroll;
import de.bsommerfeld.wsbg.terminal.ui.scroll.WheelScrollPolicy;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DropTarget;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.VolatileImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

public class SwingCefBrowser extends CefBrowser_N implements CefRenderHandler {
    // Paint-path diagnostics (WSBG_PAINT_PROFILE=true): logs slow EDT paints and
    // the CEF dirty-rect sizes, to tell "CEF reports full-frame damage" apart
    // from "the Swing blit is slow" when hunting size-dependent jank.
    private static final boolean PAINT_PROFILE =
            Boolean.parseBoolean(System.getenv("WSBG_PAINT_PROFILE"));

    private final RenderPanel panel_;
    private boolean justCreated_ = false;
    private final Rectangle browser_rect_ = new Rectangle(0, 0, 1, 1); // CEF issue #1437.

    // Live-resize signal gate. componentResized fires per AWT event (well
    // above 60/s during a macOS live resize) and every wasResized queues a
    // full re-layout + re-raster of the page in the renderer. Signalled
    // unthrottled, an excessive drag puts the renderer SECONDS behind: after
    // release a stale frame sits on screen until the backlog drains, and
    // further resizing only lengthens the queue. So at most ONE resize render
    // is ever in flight — newer sizes coalesce into resizePendingSignal_ and
    // go out when the previous frame arrives (getViewRect reads the live
    // browser_rect_, so a coalesced signal still renders the NEWEST size, and
    // the pending signal guarantees the final size always renders). The
    // timeout frees the gate when CEF never delivers a size-changed frame
    // (size unchanged, frame coalesced away) so it can't wedge shut.
    // All three fields are EDT-confined.
    private static final int RESIZE_ACK_TIMEOUT_MS = 250;
    private boolean resizeSignalInFlight_;
    private boolean resizePendingSignal_;
    private final javax.swing.Timer resizeAckTimeout_ =
            new javax.swing.Timer(RESIZE_ACK_TIMEOUT_MS, e -> resizeUnblocked());
    private Point screenPoint_ = new Point(0, 0);
    private double scaleFactor_ = 1.0;
    private final boolean isTransparent_;
    // The wheel-scroll seam. OSR has no native window, so AWT wheel events are
    // re-scaled here before being handed to CEF — see WheelScrollPolicy.
    private final WheelScrollPolicy wheelScrollPolicy_;

    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> onPaintListeners =
            new CopyOnWriteArrayList<>();

    public SwingCefBrowser(CefClient client, String url, boolean transparent,
            CefRequestContext context, WheelScrollPolicy wheelScrollPolicy,
            CefBrowserSettings settings) {
        this(client, url, transparent, context, null, null, settings, wheelScrollPolicy);
    }

    private SwingCefBrowser(CefClient client, String url, boolean transparent,
            CefRequestContext context, SwingCefBrowser parent, Point inspectAt,
            CefBrowserSettings settings, WheelScrollPolicy wheelScrollPolicy) {
        super(client, url, context, parent, inspectAt, settings);
        isTransparent_ = transparent;
        wheelScrollPolicy_ = wheelScrollPolicy != null
                ? wheelScrollPolicy : new PassthroughWheelScrollPolicy();
        panel_ = new RenderPanel();
        resizeSettleTimer_.setRepeats(false);
        wireInput();
    }

    @Override
    public void createImmediately() {
        justCreated_ = true;
        createBrowserIfRequired(false);
    }

    @Override
    public Component getUIComponent() {
        return panel_;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
            CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
        return new SwingCefBrowser(
                client, url, isTransparent_, context, (SwingCefBrowser) this, inspectAt, null,
                wheelScrollPolicy_);
    }

    // ---- CefRenderHandler -------------------------------------------------

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return browser_rect_;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        Point screenPoint = new Point(screenPoint_);
        screenPoint.translate(viewPoint.x, viewPoint.y);
        return screenPoint;
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        scaleFactor_ = currentScale();
        screenInfo.Set(scaleFactor_, 32, 8, false, browser_rect_.getBounds(),
                browser_rect_.getBounds());
        return true;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        if (!show) {
            panel_.clearPopup();
            invalidate();
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        panel_.setPopupRect(size);
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
            ByteBuffer buffer, int width, int height) {
        panel_.onPaint(popup, dirtyRects, buffer, width, height);
        if (!onPaintListeners.isEmpty()) {
            CefPaintEvent paintEvent =
                    new CefPaintEvent(browser, popup, dirtyRects, buffer, width, height);
            for (Consumer<CefPaintEvent> l : onPaintListeners) {
                l.accept(paintEvent);
            }
        }
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, final int cursorType) {
        SwingUtilities.invokeLater(() -> panel_.setCursor(new Cursor(cursorType)));
        return true; // OSR always handles the cursor change.
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        int action = getDndAction(mask);
        MouseEvent triggerEvent =
                new MouseEvent(panel_, MouseEvent.MOUSE_DRAGGED, 0, 0, x, y, 0, false);
        DragGestureEvent ev = new DragGestureEvent(
                new SyntheticDragGestureRecognizer(panel_, action, triggerEvent), action,
                new Point(x, y), new ArrayList<>(Arrays.asList(triggerEvent)));
        DragSource.getDefaultDragSource().startDrag(ev, /*dragCursor=*/null,
                new StringSelection(dragData.getFragmentText()), new DragSourceAdapter() {
                    @Override
                    public void dragDropEnd(DragSourceDropEvent dsde) {
                        dragSourceEndedAt(dsde.getLocation(), action);
                        dragSourceSystemDragEnded();
                    }
                });
        return true;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        // No-op: a single cursor is fine for our use.
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.add(listener);
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.clear();
        onPaintListeners.add(listener);
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.remove(listener);
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        return CompletableFuture.completedFuture(panel_.snapshot(nativeResolution, scaleFactor_));
    }

    // ---- internals --------------------------------------------------------

    private void createBrowserIfRequired(boolean hasParent) {
        long windowHandle = 0; // OSR (windowless): no native window needed.
        if (getNativeRef("CefBrowser") == 0) {
            if (getParentBrowser() != null) {
                createDevTools(getParentBrowser(), getClient(), windowHandle, true, isTransparent_,
                        null, getInspectAt());
            } else {
                createBrowser(getClient(), windowHandle, getUrl(), true, isTransparent_, null,
                        getRequestContext());
            }
        } else if (hasParent && justCreated_) {
            notifyAfterParentChanged();
            setFocus(true);
            justCreated_ = false;
        }
    }

    private void notifyAfterParentChanged() {
        // With OSR there is no native window to reparent, but the notification
        // still has to be sent for focus bookkeeping.
        getClient().onAfterParentChanged(this);
    }

    private double currentScale() {
        GraphicsConfiguration gc = panel_.getGraphicsConfiguration();
        return gc != null ? gc.getDefaultTransform().getScaleX() : 1.0;
    }

    private void refreshGeometry() {
        browser_rect_.setBounds(0, 0, Math.max(1, panel_.getWidth()), Math.max(1, panel_.getHeight()));
        if (panel_.isShowing()) {
            screenPoint_ = panel_.getLocationOnScreen();
        }
        scaleFactor_ = currentScale();
    }

    private void signalResize() {
        resizeSettleTimer_.stop();
        lastResizeSignalMs_ = System.currentTimeMillis();
        wasResized(browser_rect_.width, browser_rect_.height);
    }

    // Geometry always tracks the live size (getViewRect reads browser_rect_);
    // only the expensive wasResized re-raster is throttled. The settle timer
    // guarantees a trailing signal at the final size after the last event.
    private void onLiveResize() {
        refreshGeometry();
        if (System.currentTimeMillis() - lastResizeSignalMs_ >= RESIZE_SIGNAL_INTERVAL_MS) {
            signalResize();
        } else {
            resizeSettleTimer_.restart();
        }
    }

    private void wireInput() {
        MouseListener ml = new MouseListener() {
            public void mousePressed(MouseEvent e) { panel_.requestFocusInWindow(); sendMouseEvent(e); }
            public void mouseReleased(MouseEvent e) { sendMouseEvent(e); }
            public void mouseEntered(MouseEvent e) { sendMouseEvent(e); }
            public void mouseExited(MouseEvent e) { sendMouseEvent(e); }
            public void mouseClicked(MouseEvent e) { sendMouseEvent(e); }
        };
        panel_.addMouseListener(ml);
        panel_.addMouseMotionListener(new MouseMotionListener() {
            public void mouseMoved(MouseEvent e) { sendMouseEvent(e); }
            public void mouseDragged(MouseEvent e) { sendMouseEvent(e); }
        });
        // OSR has no native window, so AWT delivers the wheel event and JCEF's
        // native bridge forwards getUnitsToScroll() (OS scroll-lines) straight
        // into CEF, which reads it as pixels → ~3px/notch, "sticky" scroll. The
        // WheelScrollPolicy decides direction + magnitude; forwardScroll does the
        // delivery. (Replaces the old as-is forwarding; see ui/scroll/.)
        panel_.addMouseWheelListener(new MouseWheelListener() {
            public void mouseWheelMoved(MouseWheelEvent e) {
                forwardScroll(wheelScrollPolicy_.translate(e), e);
            }
        });
        panel_.addKeyListener(new KeyListener() {
            public void keyTyped(KeyEvent e) { sendKeyEvent(e); }
            public void keyPressed(KeyEvent e) { sendKeyEvent(e); }
            public void keyReleased(KeyEvent e) { sendKeyEvent(e); }
        });
        panel_.setFocusable(true);
        panel_.addFocusListener(new FocusListener() {
            public void focusLost(FocusEvent e) { setFocus(false); }
            public void focusGained(FocusEvent e) {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                setFocus(true);
            }
        });
        panel_.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { onLiveResize(); }
            @Override public void componentMoved(ComponentEvent e) {
                if (panel_.isShowing()) screenPoint_ = panel_.getLocationOnScreen();
            }
        });
        new DropTarget(panel_, new CefDropTargetListener(this));
    }

    /**
     * Delivers a policy-decided {@link WheelScroll} to CEF. JCEF's native
     * sendMouseWheelEvent reads getUnitsToScroll() (= scrollAmount × rotation)
     * and routes to the horizontal axis only when SHIFT is held, so we encode
     * the desired pixel delta as a WHEEL_UNIT_SCROLL event with scrollAmount =
     * |delta|, rotation = sign, and set/clear SHIFT per axis. X and Y are sent
     * as separate events because the native handles one axis at a time.
     */
    private void forwardScroll(WheelScroll scroll, MouseWheelEvent src) {
        if (scroll == null || scroll.isEmpty()) return;
        if (scroll.unitsY() != 0) {
            sendMouseWheelEvent(buildUnitScroll(src, scroll.unitsY(), false));
        }
        if (scroll.unitsX() != 0) {
            sendMouseWheelEvent(buildUnitScroll(src, scroll.unitsX(), true));
        }
    }

    private MouseWheelEvent buildUnitScroll(MouseWheelEvent src, int units, boolean horizontal) {
        int mods = horizontal
                ? src.getModifiersEx() | InputEvent.SHIFT_DOWN_MASK
                : src.getModifiersEx() & ~InputEvent.SHIFT_DOWN_MASK;
        int rotation = units >= 0 ? 1 : -1;
        int amount = Math.abs(units); // getUnitsToScroll() = amount × rotation = units
        return new MouseWheelEvent(panel_, src.getID(), src.getWhen(), mods,
                src.getX(), src.getY(), 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, amount, rotation);
    }

    private static int getDndAction(int mask) {
        if ((mask & CefDragData.DragOperations.DRAG_OPERATION_COPY)
                == CefDragData.DragOperations.DRAG_OPERATION_COPY) {
            return DnDConstants.ACTION_COPY;
        } else if ((mask & CefDragData.DragOperations.DRAG_OPERATION_MOVE)
                == CefDragData.DragOperations.DRAG_OPERATION_MOVE) {
            return DnDConstants.ACTION_MOVE;
        } else if ((mask & CefDragData.DragOperations.DRAG_OPERATION_LINK)
                == CefDragData.DragOperations.DRAG_OPERATION_LINK) {
            return DnDConstants.ACTION_LINK;
        }
        return DnDConstants.ACTION_NONE;
    }

    private static final class SyntheticDragGestureRecognizer extends DragGestureRecognizer {
        SyntheticDragGestureRecognizer(Component c, int action, MouseEvent triggerEvent) {
            super(new DragSource(), c, action);
            appendEvent(triggerEvent);
        }
        protected void registerListeners() {}
        protected void unregisterListeners() {}
    }

    /**
     * The on-screen surface. Holds the latest CEF frame as a BufferedImage and
     * blits it (scaled to the component size) each Swing paint. Popups (e.g.
     * {@code <select>} dropdowns) arrive as a separate buffer composited on top.
     */
    private final class RenderPanel extends JComponent {
        private final Object lock = new Object();
        private BufferedImage image_;
        private BufferedImage popupImage_;
        private final Rectangle popupRect_ = new Rectangle();
        private boolean popupVisible_ = false;

        // GPU-resident mirror of image_. image_'s raster is written directly,
        // which makes it permanently "unmanaged" for Java2D — drawing it to the
        // screen re-uploads the ENTIRE buffer as a texture on EVERY paint
        // (measured: a fixed ~6ms at 2.5K, ~24ms at 6.7K device px, regardless
        // of clip size — the big-window jank). Instead, paintComponent uploads
        // only the accumulated dirty region into this VolatileImage and blits
        // that to the screen: VRAM→VRAM, effectively free at any window size.
        private VolatileImage vram_;
        private final Rectangle vramDirty_ = new Rectangle(); // device px pending upload
        private boolean vramFull_ = true;                     // full re-upload needed
        private boolean resizeFrame_ = false;                 // buffer size just changed

        RenderPanel() {
            setOpaque(true);
            setBackground(Color.BLACK);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            notifyAfterParentChanged();
        }

        @Override
        public void removeNotify() {
            synchronized (lock) {
                if (vram_ != null) {
                    vram_.flush();   // surface is tied to the peer being removed
                    vram_ = null;
                    vramFull_ = true;
                }
            }
            if (!isClosed()) notifyAfterParentChanged();
            super.removeNotify();
        }

        void setPopupRect(Rectangle r) {
            synchronized (lock) {
                popupRect_.setBounds(r);
                popupVisible_ = true;
            }
        }

        void clearPopup() {
            synchronized (lock) {
                popupVisible_ = false;
            }
            repaint();
        }

        // Copies only the dirty region out of CEF's buffer and schedules an
        // equally narrow Swing repaint. A full-frame copy at 60fps is a
        // multi-GB/s memcpy on a Retina display and was the app-wide scroll
        // bottleneck; the dirty path also shrinks the lock's critical section
        // to the changed rows, so CEF's paint thread and the EDT no longer
        // stall each other on busy frames.
        void onPaint(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer,
                int width, int height) {
            if (width <= 0 || height <= 0) return;
            IntBuffer src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            Rectangle repaintArea = null; // null = repaint the whole component
            long t0 = PAINT_PROFILE ? System.nanoTime() : 0;
            long tLocked = 0;
            synchronized (lock) {
                if (PAINT_PROFILE) tLocked = System.nanoTime();
                if (popup) {
                    popupImage_ = blit(popupImage_, src, width, height);
                    if (popupVisible_) {
                        // popupRect_ is already in component (logical) space.
                        repaintArea = grow(new Rectangle(popupRect_), 2);
                    }
                } else if (image_ != null && image_.getWidth() == width
                        && image_.getHeight() == height && dirtyRects != null
                        && dirtyRects.length > 0 && src.remaining() >= width * height) {
                    Rectangle devicePx = blitDirty(image_, src, width, dirtyRects, height);
                    if (PAINT_PROFILE) {
                        long now = System.nanoTime();
                        System.out.println("[PAINT] cef dirty=" + devicePx.width + "x"
                                + devicePx.height + " of " + width + "x" + height
                                + " rects=" + dirtyRects.length
                                + " lockWait=" + (tLocked - t0) / 1_000_000 + "ms"
                                + " copy=" + (now - tLocked) / 1_000_000 + "ms");
                    }
                    if (devicePx.isEmpty()) return; // nothing visible changed
                    if (vramDirty_.isEmpty()) {
                        vramDirty_.setBounds(devicePx);
                    } else {
                        vramDirty_.add(devicePx);
                    }
                    // The image is a device-pixel buffer drawn at bufferPx/scale;
                    // map the dirty union into component space, padded for the
                    // scale rounding.
                    double s = scaleFactor_ <= 0 ? 1.0 : scaleFactor_;
                    repaintArea = grow(new Rectangle(
                            (int) Math.floor(devicePx.x / s),
                            (int) Math.floor(devicePx.y / s),
                            (int) Math.ceil(devicePx.width / s),
                            (int) Math.ceil(devicePx.height / s)), 2);
                } else {
                    // First frame, resize, or an unusable dirty list → full copy.
                    if (image_ == null || image_.getWidth() != width
                            || image_.getHeight() != height) {
                        resizeFrame_ = true;
                    }
                    image_ = blit(image_, src, width, height);
                    vramFull_ = true;
                }
            }
            if (repaintArea == null) {
                repaint();
            } else {
                repaint(repaintArea);
            }
        }

        // BGRA little-endian bytes read as a little-endian int yield 0xAARRGGBB
        // = ARGB, so the buffer copies straight into a TYPE_INT_ARGB raster.
        private BufferedImage blit(BufferedImage img, IntBuffer src, int w, int h) {
            if (img == null || img.getWidth() != w || img.getHeight() != h) {
                img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            }
            int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            src.get(dst, 0, Math.min(dst.length, src.remaining()));
            return img;
        }

        /**
         * Copies only {@code dirtyRects} (device px, row by row — src and dst
         * share the buffer's row stride) into the existing image and returns
         * their union, empty when no rect intersected the buffer.
         */
        private Rectangle blitDirty(BufferedImage img, IntBuffer src, int w,
                Rectangle[] dirtyRects, int h) {
            int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            Rectangle bounds = new Rectangle(0, 0, w, h);
            Rectangle union = null;
            for (Rectangle r : dirtyRects) {
                Rectangle c = r.intersection(bounds);
                if (c.isEmpty()) continue;
                for (int row = 0; row < c.height; row++) {
                    int off = (c.y + row) * w + c.x;
                    src.position(off);
                    src.get(dst, off, c.width);
                }
                union = union == null ? c : union.union(c);
            }
            return union == null ? new Rectangle() : union;
        }

        private Rectangle grow(Rectangle r, int pad) {
            r.grow(pad, pad);
            return r;
        }

        /**
         * Brings {@link #vram_} up to date with {@link #image_} (dirty-region
         * sub-rect upload) and returns the surface to draw — {@code vram_}
         * normally, {@code image_} itself when no volatile surface is available
         * (component not displayable, incompatible surface). Runs on the EDT
         * under {@code lock}.
         */
        private java.awt.Image syncVram() {
            int w = image_.getWidth(), h = image_.getHeight();
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc == null) return image_;
            if (vram_ == null || vram_.getWidth() != w || vram_.getHeight() != h) {
                // A live resize delivers a NEW buffer size on every frame —
                // recreating the volatile surface + a full upload per frame is
                // slower than the plain unmanaged draw ever was (the content
                // visibly trails the window edge). Draw image_ directly for
                // size-changing frames and rebuild the mirror once, on the
                // first stable-size paint after the drag settles.
                if (resizeFrame_) {
                    resizeFrame_ = false;
                    return image_;
                }
                if (vram_ != null) vram_.flush();
                vram_ = createVolatileImage(w, h);
                vramFull_ = true;
            } else {
                resizeFrame_ = false;
            }
            if (vram_ == null) return image_;
            int state = vram_.validate(gc);
            if (state == VolatileImage.IMAGE_INCOMPATIBLE) {
                vram_.flush();
                vram_ = createVolatileImage(w, h);
                vramFull_ = true;
                if (vram_ == null) return image_;
                if (vram_.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) return image_;
            } else if (state == VolatileImage.IMAGE_RESTORED) {
                vramFull_ = true;
            }
            Rectangle up = vramFull_ ? new Rectangle(0, 0, w, h)
                    : vramDirty_.intersection(new Rectangle(0, 0, w, h));
            if (!up.isEmpty()) {
                long m0 = PAINT_PROFILE ? System.nanoTime() : 0;
                Graphics2D vg = vram_.createGraphics();
                long m1 = PAINT_PROFILE ? System.nanoTime() : 0;
                try {
                    vg.setComposite(AlphaComposite.Src);
                    if (vramFull_) {
                        vg.drawImage(image_, 0, 0, null);
                    } else {
                        // Metal's sw→VRAM blit re-uploads the ENTIRE source image
                        // for an unmanaged source — a clip does NOT bound it
                        // (measured: a 113x36 clipped draw cost the same ~9ms as
                        // full-frame at 6.7K device px). A subimage VIEW (shared
                        // raster, no pixel copy) bounds the source itself, so the
                        // upload is dirty-sized.
                        vg.drawImage(image_.getSubimage(up.x, up.y, up.width, up.height),
                                up.x, up.y, null);
                    }
                } finally {
                    vg.dispose();
                }
                if (PAINT_PROFILE) {
                    long m2 = System.nanoTime();
                    if ((m2 - m0) / 1_000_000 >= 3) {
                        System.out.println("[PAINT] vram-up rect=" + up.width + "x" + up.height
                                + " mkG=" + (m1 - m0) / 1_000_000 + "ms"
                                + " draw+disp=" + (m2 - m1) / 1_000_000 + "ms"
                                + " full=" + vramFull_);
                    }
                }
            }
            vramDirty_.setBounds(0, 0, 0, 0);
            vramFull_ = false;
            return vram_;
        }

        @Override
        protected void paintComponent(Graphics g) {
            long t0 = PAINT_PROFILE ? System.nanoTime() : 0;
            long tLocked = 0;
            super.paintComponent(g); // fills the (black) background
            Rectangle clip = g.getClipBounds();
            synchronized (lock) {
                if (PAINT_PROFILE) tLocked = System.nanoTime();
                if (image_ != null) {
                    // Sync the GPU mirror: upload only the dirty device-px
                    // region of image_ into vram_ (a sub-rect sw→VRAM upload),
                    // then blit vram_ to the screen — VRAM→VRAM, cheap at any
                    // window size. Drawing image_ directly would re-upload the
                    // whole (unmanaged) buffer every paint. Falls back to the
                    // direct draw when a volatile surface isn't available.
                    java.awt.Image toDraw = syncVram();

                    // image_ is a device-pixel buffer; its intended on-screen
                    // size is bufferPx / deviceScale. Never draw it LARGER than
                    // that — on a big jump (maximize) stretching the stale buffer
                    // to the new bounds balloons the text for a frame until CEF
                    // repaints. Cap to the native logical size (the freshly-sized
                    // frame arrives in a tick and fills the rest of the bg).
                    // Only the clip region is drawn, mapped to its source rect
                    // with per-coordinate rounding so adjacent clips share
                    // identical source edges (no seams).
                    long tSync = PAINT_PROFILE ? System.nanoTime() : 0;
                    double s = scaleFactor_ <= 0 ? 1.0 : scaleFactor_;
                    int logW = (int) Math.round(image_.getWidth() / s);
                    int logH = (int) Math.round(image_.getHeight() / s);
                    int maxW = Math.min(getWidth(), logW);
                    int maxH = Math.min(getHeight(), logH);
                    int dx1 = 0, dy1 = 0, dx2 = maxW, dy2 = maxH;
                    if (clip != null) {
                        dx1 = Math.max(0, clip.x);
                        dy1 = Math.max(0, clip.y);
                        dx2 = Math.min(maxW, clip.x + clip.width);
                        dy2 = Math.min(maxH, clip.y + clip.height);
                    }
                    if (dx1 < dx2 && dy1 < dy2) {
                        g.drawImage(toDraw, dx1, dy1, dx2, dy2,
                                (int) Math.round(dx1 * s), (int) Math.round(dy1 * s),
                                (int) Math.round(dx2 * s), (int) Math.round(dy2 * s), null);
                    }
                    if (toDraw == vram_ && vram_.contentsLost()) {
                        vramFull_ = true; // surface evicted mid-paint → redo fully
                        repaint();
                    }
                    if (PAINT_PROFILE) {
                        long now = System.nanoTime();
                        long total = (now - t0) / 1_000_000;
                        if (total >= 4) {
                            System.out.println("[PAINT] edt-split sync="
                                    + (tSync - tLocked) / 1_000_000 + "ms blit="
                                    + (now - tSync) / 1_000_000 + "ms vram=" + (toDraw == vram_));
                        }
                    }
                }
                if (popupVisible_ && popupImage_ != null) {
                    g.drawImage(popupImage_, popupRect_.x, popupRect_.y,
                            popupRect_.width, popupRect_.height, null);
                }
            }
            if (PAINT_PROFILE && clip != null) {
                long now = System.nanoTime();
                long ms = (now - t0) / 1_000_000;
                if (ms >= 4) {
                    System.out.println("[PAINT] edt clip=" + clip.width + "x" + clip.height
                            + " took=" + ms + "ms"
                            + " lockWait=" + (tLocked - t0) / 1_000_000 + "ms"
                            + " draw=" + (now - tLocked) / 1_000_000 + "ms");
                }
            }
        }

        BufferedImage snapshot(boolean nativeResolution, double scale) {
            synchronized (lock) {
                if (image_ == null) return null;
                if (nativeResolution || scale == 1.0) {
                    BufferedImage copy = new BufferedImage(
                            image_.getWidth(), image_.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    copy.getGraphics().drawImage(image_, 0, 0, null);
                    return copy;
                }
                int w = (int) (image_.getWidth() / scale);
                int h = (int) (image_.getHeight() / scale);
                BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                scaled.getGraphics().drawImage(image_, 0, 0, w, h, null);
                return scaled;
            }
        }
    }
}
