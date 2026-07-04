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
//
// This class is now only the CefBrowser_N / CefRenderHandler PROTOCOL shell;
// the raster/GPU pipeline (OsrRenderPanel), the live-resize state machine
// (OsrResizeGate), the AWT input wiring + wheel translation (OsrInputRouter),
// and the CEF-initiated drag source (OsrDragSource) live in cooperating
// package-private classes in this package.

package org.cef.browser;

import de.bsommerfeld.wsbg.terminal.ui.scroll.PassthroughWheelScrollPolicy;
import de.bsommerfeld.wsbg.terminal.ui.scroll.WheelScrollPolicy;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

public class SwingCefBrowser extends CefBrowser_N implements CefRenderHandler, RenderContext {

    private final OsrRenderPanel panel_;
    private boolean justCreated_ = false;

    // Live-resize gate: the ONLY thing CEF ever sees is its view rect, committed
    // exclusively inside the gate's signalResize so CEF observes exactly one new
    // size per gate cycle (see OsrResizeGate). Its commit callback runs the
    // inherited wasResized + invalidate; isClosed guards a commit after teardown.
    private final OsrResizeGate resizeGate_ =
            new OsrResizeGate(this::isClosed, (w, h) -> { wasResized(w, h); invalidate(); });

    private Point screenPoint_ = new Point(0, 0);
    private double scaleFactor_ = 1.0;
    private final boolean isTransparent_;
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
        panel_ = new OsrRenderPanel(this);
        new OsrInputRouter(panel_, this, wheelScrollPolicy_).install();
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
        return resizeGate_.viewRect();
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
        Rectangle rect = resizeGate_.viewRect();
        screenInfo.Set(scaleFactor_, 32, 8, false, rect.getBounds(), rect.getBounds());
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
        boolean resizedFrame = panel_.onPaint(popup, dirtyRects, buffer, width, height);
        if (resizedFrame) {
            SwingUtilities.invokeLater(resizeGate_::unblock);
        }
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
        return OsrDragSource.startDragging(panel_, this, dragData, mask, x, y);
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

    // ---- RenderContext (for OsrRenderPanel) -------------------------------

    @Override
    public double renderScale() {
        return scaleFactor_;
    }

    @Override
    public boolean isRenderClosed() {
        return isClosed();
    }

    @Override
    public void notifyParentChanged() {
        notifyAfterParentChanged();
    }

    // ---- input callbacks (for OsrInputRouter) -----------------------------

    /** Live-resize: refresh geometry, then hand the new panel size to the gate. */
    void handleComponentResized() {
        refreshGeometry();
        resizeGate_.onLiveResize(Math.max(1, panel_.getWidth()), Math.max(1, panel_.getHeight()));
    }

    void handleComponentMoved() {
        if (panel_.isShowing()) screenPoint_ = panel_.getLocationOnScreen();
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
        if (panel_.isShowing()) {
            screenPoint_ = panel_.getLocationOnScreen();
        }
        scaleFactor_ = currentScale();
    }
}
