package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A windowless browser that renders into nothing. It loads the page, runs its
 * scripts and keeps its cookies like any browser - it just has no pixels.
 *
 * <p>In {@code org.cef.browser} because {@link CefBrowser_N} is package-private.
 * JCEF's own windowless browser draws through JOGL, whose natives this needs
 * none of; the terminal's {@code SwingCefBrowser} on master had the same
 * reason to exist, with a Swing surface this one does without.
 */
public final class HeadlessCefBrowser extends CefBrowser_N implements CefRenderHandler {

    /** A common laptop viewport - what the page believes it is laid out in. */
    private static final Rectangle VIEW = new Rectangle(0, 0, 1440, 900);

    /**
     * Never shown, never added to a window - JCEF's callbacks ask for the
     * browser's component and expect one to exist.
     */
    private final Component component = new java.awt.Panel();

    public HeadlessCefBrowser(CefClient client, String url, CefBrowserSettings settings) {
        super(client, url, null, null, null, settings);
        component.setSize(VIEW.width, VIEW.height);
    }

    @Override
    public void createImmediately() {
        createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
    }

    @Override
    public Component getUIComponent() {
        return component;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context,
            CefBrowser_N parent, Point inspectAt) {
        throw new UnsupportedOperationException("no dev tools in a hidden tab");
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("headless"));
    }

    // ---- CefRenderHandler: a fixed view, every frame discarded ------------

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return new Rectangle(VIEW);
    }

    /*
     * On macOS, JCEF calls this from the AppKit main thread, and its native
     * side (RenderHandler::GetScreenInfo in libjcef) fails there: the JVM
     * reports a StackOverflowError on entering Java ("Exception in thread
     * AppKit Thread" on stderr) and -Xcheck:jni a bad local reference in that
     * same function (measured 2026-09-26). Chromium then keeps its default
     * screen info. A page nobody sees loads, runs and sets cookies all the
     * same; the fix belongs in JCEF.
    */
    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        screenInfo.Set(2.0, 32, 8, false, new Rectangle(VIEW), new Rectangle(VIEW));
        return true;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        return new Point(viewPoint);
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer,
            int width, int height) {
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return true;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
    }
}
