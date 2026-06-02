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

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
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
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
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
    private final RenderPanel panel_;
    private boolean justCreated_ = false;
    private final Rectangle browser_rect_ = new Rectangle(0, 0, 1, 1); // CEF issue #1437.
    private Point screenPoint_ = new Point(0, 0);
    private double scaleFactor_ = 1.0;
    private final boolean isTransparent_;

    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> onPaintListeners =
            new CopyOnWriteArrayList<>();

    public SwingCefBrowser(CefClient client, String url, boolean transparent,
            CefRequestContext context) {
        this(client, url, transparent, context, null, null, null);
    }

    private SwingCefBrowser(CefClient client, String url, boolean transparent,
            CefRequestContext context, SwingCefBrowser parent, Point inspectAt,
            CefBrowserSettings settings) {
        super(client, url, context, parent, inspectAt, settings);
        isTransparent_ = transparent;
        panel_ = new RenderPanel();
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
                client, url, isTransparent_, context, (SwingCefBrowser) this, inspectAt, null);
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
        panel_.onPaint(popup, buffer, width, height);
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
        wasResized(browser_rect_.width, browser_rect_.height);
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
        // NOTE(scroll): wheel is forwarded as-is. OSR scrolling on Windows feels
        // slow and ignores third-party "reverse scroll" tools (jcef's native
        // sendMouseWheelEvent uses a fixed per-event delta and reads only the
        // rotation sign; AWT delivers the raw HID wheel, not the OS FlipFlopWheel
        // setting). Tried: re-sending N× for speed + a FlipFlopWheel registry
        // read for direction — but the user's reverse comes from a tool, not the
        // OS, so it can't be auto-detected. Revisit with the virtual scroll work
        // (a manual config toggle is the realistic fix for tool-based reverse).
        panel_.addMouseWheelListener(new MouseWheelListener() {
            public void mouseWheelMoved(MouseWheelEvent e) { sendMouseWheelEvent(e); }
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
            @Override public void componentResized(ComponentEvent e) { refreshGeometry(); }
            @Override public void componentMoved(ComponentEvent e) {
                if (panel_.isShowing()) screenPoint_ = panel_.getLocationOnScreen();
            }
        });
        new DropTarget(panel_, new CefDropTargetListener(this));
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

        void onPaint(boolean popup, ByteBuffer buffer, int width, int height) {
            if (width <= 0 || height <= 0) return;
            IntBuffer src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            synchronized (lock) {
                if (popup) {
                    popupImage_ = blit(popupImage_, src, width, height);
                } else {
                    image_ = blit(image_, src, width, height);
                }
            }
            repaint();
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

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g); // fills the (black) background
            synchronized (lock) {
                if (image_ != null) {
                    // image_ is a device-pixel buffer; its intended on-screen
                    // size is bufferPx / deviceScale. Never draw it LARGER than
                    // that — on a big jump (maximize) stretching the stale buffer
                    // to the new bounds balloons the text for a frame until CEF
                    // repaints. Cap to the native logical size (the freshly-sized
                    // frame arrives in a tick and fills the rest of the bg).
                    double s = scaleFactor_ <= 0 ? 1.0 : scaleFactor_;
                    int logW = (int) Math.round(image_.getWidth() / s);
                    int logH = (int) Math.round(image_.getHeight() / s);
                    g.drawImage(image_, 0, 0,
                            Math.min(getWidth(), logW), Math.min(getHeight(), logH), null);
                }
                if (popupVisible_ && popupImage_ != null) {
                    g.drawImage(popupImage_, popupRect_.x, popupRect_.y,
                            popupRect_.width, popupRect_.height, null);
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
