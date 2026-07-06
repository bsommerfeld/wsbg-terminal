package de.bsommerfeld.wsbg.terminal.ui;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * macOS-only <b>native</b> title-bar carve-out — the mac twin of Windows'
 * {@link WindowsCustomChrome} hit-test.
 *
 * <p><b>The problem.</b> {@link MacWindowChrome} keeps the JFrame decorated and
 * only makes the OS title bar transparent ({@code fullWindowContent}), so the
 * HTML titlebar draws flush over the native chrome. But the top ~28&nbsp;px stays
 * a native title-bar <em>drag</em> region: AppKit holds every mouse-down there to
 * disambiguate drag / double-click-zoom before delivering it. On the right, where
 * the HTML action buttons live (donate / gear / update), the click is held for
 * that window — the 1-2&nbsp;s "Settings gear lag". Content-area clicks are
 * instant because they aren't in a drag region.
 *
 * <p>A plain overlay {@code NSView} added over the title bar does <b>not</b> fix
 * it: its {@code mouseDownCanMoveWindow} is never even queried (verified live) —
 * AppKit treats the whole title-bar band as draggable regardless of arbitrary
 * subviews. Windows solves the twin problem in {@link WindowsCustomChrome} via
 * {@code WM_NCHITTEST}; macOS's equivalent is Apple's own mechanism for hosting
 * clickable controls in the title bar: a <b>trailing
 * {@code NSTitlebarAccessoryViewController}</b>. Its view is part of the title
 * bar's <em>control</em> region, not the drag region, so AppKit delivers the
 * mouse-down immediately and native window dragging / docking / tiling stay ON
 * everywhere else. We size the accessory to the ~140&nbsp;px action strip on the
 * trailing side (traffic lights own the leading side).
 *
 * <p>The accessory's view is a small custom {@code NSView} subclass that
 * <b>synthesises Swing {@link MouseEvent}s</b> from the native events and
 * dispatches them straight onto the OSR panel (where {@code OsrInputRouter}
 * listens) — routing through AWTView's native {@code mouseDown:} instead would
 * re-enter AppKit mouse-tracking and bring the lag back. An {@code NSTrackingArea}
 * forwards {@code mouseMoved:} so HTML {@code :hover} (the gear spin) still works.
 *
 * <p><b>Everything is best-effort.</b> Any failure (symbol not found, window not
 * found, class-pair clash) logs and returns — the buttons then just lag as
 * before, never break. Set {@code WSBG_MAC_CARVEOUT=false} to disable outright.
 *
 * <p><b>Threading.</b> All view mutation runs on the AppKit main thread via
 * {@code dispatch_async_f} — doing it off-thread is a hard SIGTRAP. Strong static
 * refs to every JNA {@link Callback} + the accessory controller are kept for the
 * process lifetime; a GC'd IMP would crash the next time AppKit dispatches to it.
 */
final class MacTitlebarCarveout {

    private static final Logger LOG = LoggerFactory.getLogger(MacTitlebarCarveout.class);

    // Kill switch. Any value other than an explicit "false" leaves it on.
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getenv("WSBG_MAC_CARVEOUT"));

    // Carve geometry in logical (NS point) units, kept in sync with titlebar.css:
    //   --titlebar-h = 38; .tb-actions = up to 3 iconbtns (30px) + gaps + padding
    //   ~122px, right-aligned. 140 covers the group at any button count with a
    //   small margin, matching Windows' ACTIONS_W_DIP = 130.
    private static final double CARVE_W = 140;
    private static final double CARVE_H = 38;

    // NSLayoutAttributeTrailing — right side in LTR; pins the accessory to the
    // right of the title bar (traffic lights own the leading side).
    private static final long NS_LAYOUT_ATTRIBUTE_TRAILING = 6;
    // NSTrackingArea options: MouseMoved | ActiveAlways | InVisibleRect.
    private static final long TRACKING_OPTS = 0x02 | 0x80 | 0x200;

    // --- objc/libSystem entry points (resolved from the already-loaded process:
    //     libobjc + AppKit + Foundation are linked in by AWT). ---
    private static final NativeLibrary PROC = safeProcess();
    private static final Function objc_getClass = fn("objc_getClass");
    private static final Function sel_registerName = fn("sel_registerName");
    private static final Function objc_msgSend = fn("objc_msgSend");
    private static final Function objc_allocateClassPair = fn("objc_allocateClassPair");
    private static final Function objc_registerClassPair = fn("objc_registerClassPair");
    private static final Function class_addMethod = fn("class_addMethod");
    private static final Function object_getClassName = fn("object_getClassName");
    private static final Function dispatch_async_f = fn("dispatch_async_f");

    // Strong refs (GC of any of these while installed => crash on next dispatch).
    private static Pointer overlayClass;
    private static Pointer titlebarAccessory; // the NSTitlebarAccessoryViewController
    private static Pointer selLocationInWindow, selClickCount;
    private static Callback impMouseDown, impMouseUp, impMouseDragged, impMouseMoved,
            impCanMove, impAcceptsFirstMouse, dispatchWork;

    // The Swing OSR panel the OsrInputRouter listens on — synthetic mouse events
    // are dispatched straight here, bypassing AWTView's native mouseDown: (which
    // re-enters AppKit mouse-tracking and reintroduces the very lag we remove).
    private static volatile Component osrTarget;

    private static volatile double pendingW, pendingH;
    private static boolean installed;

    private MacTitlebarCarveout() {}

    /**
     * Installs the carve-out. Call once, after the window is on screen (so it is
     * in {@code [NSApp windows]}). Reads the frame size on the EDT, then hops to
     * the AppKit main thread to build + attach the overlay.
     */
    static synchronized void install(JFrame frame) {
        if (!ENABLED || installed) return;
        if (!isMac() || PROC == null || objc_msgSend == null || dispatch_async_f == null) return;
        try {
            pendingW = frame.getWidth();
            pendingH = frame.getHeight();
            osrTarget = findOsr(frame);
            LOG.debug("mac carve-out: osr target = {}",
                    osrTarget == null ? "nil" : osrTarget.getClass().getName());
            dispatchWork = (DispatchFn) ctx -> attachOnMain();
            Pointer mainQueue = PROC.getGlobalVariableAddress("_dispatch_main_q");
            if (mainQueue == null) {
                LOG.debug("mac carve-out: _dispatch_main_q not found; skipping.");
                return;
            }
            installed = true; // set before the async hop; a failure inside logs + no-ops
            dispatch_async_f.invoke(Void.class, new Object[]{mainQueue, Pointer.NULL, dispatchWork});
        } catch (Throwable t) {
            LOG.debug("mac carve-out install skipped: {}", t.toString());
        }
    }

    /** Runs on the AppKit main thread. Builds the class (once) + attaches the view. */
    private static void attachOnMain() {
        try {
            ensureClass();
            if (overlayClass == null) return;

            Pointer window = findAwtWindow();
            if (window == null) {
                LOG.debug("mac carve-out: AWTWindow_Normal not found; keeping native caption.");
                return;
            }

            // Our custom NSView, sized to the trailing action-button strip. Height
            // is the (approx) native title-bar band; AppKit clamps it to the real
            // title-bar height. The bottom sliver of the 38px HTML titlebar that
            // spills below the native band already gets instant clicks (it's in
            // the content area, not a drag region), so this covers exactly the
            // laggy part.
            Pointer overlay = msg(msg(overlayClass, sel("alloc")), sel("init"));
            if (overlay == null) return;

            CGSize size = new CGSize();
            size.width = CARVE_W;
            size.height = CARVE_H;
            objc_msgSend.invoke(Void.class, new Object[]{overlay, sel("setFrameSize:"), size});
            addTrackingArea(overlay);

            // Apple's supported way to host clickable controls in the title bar:
            // a trailing NSTitlebarAccessoryViewController. Its view is part of the
            // title bar's CONTROL region, not the drag region — so AppKit delivers
            // the mouseDown immediately, with no drag / double-click-zoom wait, and
            // native window dragging stays intact everywhere else (docking/tiling).
            Pointer avcCls = msg0(objc_getClass, "NSTitlebarAccessoryViewController");
            if (avcCls == null) return;
            titlebarAccessory = msg(msg(avcCls, sel("alloc")), sel("init"));
            if (titlebarAccessory == null) return;
            objc_msgSend.invoke(Void.class, new Object[]{titlebarAccessory, sel("setView:"), overlay});
            objc_msgSend.invoke(Void.class, new Object[]{
                    titlebarAccessory, sel("setLayoutAttribute:"), NS_LAYOUT_ATTRIBUTE_TRAILING});
            objc_msgSend.invoke(Void.class, new Object[]{
                    window, sel("addTitlebarAccessoryViewController:"), titlebarAccessory});

            LOG.info("Installed macOS title-bar carve-out as a trailing accessory ({}x{} pt).",
                    (int) CARVE_W, (int) CARVE_H);
        } catch (Throwable t) {
            LOG.warn("mac carve-out attach failed; buttons keep the native-caption lag: {}", t.toString());
        }
    }

    /** Registers the {@code WSBGCarveoutView} NSView subclass with our IMPs. Idempotent. */
    private static void ensureClass() {
        if (overlayClass != null) return;
        try {
            selLocationInWindow = sel("locationInWindow");
            selClickCount = sel("clickCount");

            Pointer nsView = msg0(objc_getClass, "NSView");
            if (nsView == null) return;
            Pointer cls = objc_allocateClassPair.invokePointer(new Object[]{nsView, "WSBGCarveoutView", 0L});
            if (cls == null) return; // name clash / already built elsewhere => bail (buttons lag)

            impMouseDown = synthImp(MouseEvent.MOUSE_PRESSED);
            impMouseUp = synthImp(MouseEvent.MOUSE_RELEASED);
            impMouseDragged = synthImp(MouseEvent.MOUSE_DRAGGED);
            impMouseMoved = synthImp(MouseEvent.MOUSE_MOVED);
            impCanMove = (BoolNoArgImp) (self, cmd) -> (byte) 0;              // no window drag here
            impAcceptsFirstMouse = (BoolEventImp) (self, cmd, ev) -> (byte) 1; // click even when inactive

            addMethod(cls, sel("mouseDown:"), impMouseDown, "v@:@");
            addMethod(cls, sel("mouseUp:"), impMouseUp, "v@:@");
            addMethod(cls, sel("mouseDragged:"), impMouseDragged, "v@:@");
            addMethod(cls, sel("mouseMoved:"), impMouseMoved, "v@:@");
            addMethod(cls, sel("mouseDownCanMoveWindow"), impCanMove, "c@:");
            addMethod(cls, sel("acceptsFirstMouse:"), impAcceptsFirstMouse, "c@:@");

            objc_registerClassPair.invoke(Void.class, new Object[]{cls});
            overlayClass = cls;
        } catch (Throwable t) {
            LOG.debug("mac carve-out: class build failed: {}", t.toString());
            overlayClass = null;
        }
    }

    /**
     * An IMP that translates the native {@code NSEvent} into a Swing
     * {@link MouseEvent} of {@code swingId} and dispatches it straight to the OSR
     * panel — never touching AWTView's native handler (which re-enters AppKit
     * mouse-tracking and reintroduces the lag).
     */
    private static VoidEventImp synthImp(int swingId) {
        return (self, cmd, event) -> {
            try {
                postMouse(swingId, event);
            } catch (Throwable ignored) {
                // never let an exception cross back into AppKit
            }
        };
    }

    /** Reads the NSEvent location + click count and posts a Swing event to the OSR panel. */
    private static void postMouse(int swingId, Pointer event) {
        Component target = osrTarget;
        if (target == null || event == null) return;
        CGPoint loc = (CGPoint) objc_msgSend.invoke(CGPoint.class, new Object[]{event, selLocationInWindow});
        long rawClicks = objc_msgSend.invokeLong(new Object[]{event, selClickCount});

        int px = (int) Math.round(loc.x);
        // NSEvent origin is bottom-left of the window; Swing is top-left. The OSR
        // panel fills the full window (fullWindowContent), so flip about its height.
        int py = (int) Math.round(target.getHeight() - loc.y);
        boolean moved = swingId == MouseEvent.MOUSE_MOVED;
        boolean down = swingId == MouseEvent.MOUSE_PRESSED || swingId == MouseEvent.MOUSE_DRAGGED;
        int mods = down ? InputEvent.BUTTON1_DOWN_MASK : 0;
        int button = moved ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
        int clicks = moved ? 0 : (int) Math.max(1, rawClicks);
        long when = System.currentTimeMillis();

        SwingUtilities.invokeLater(() ->
                target.dispatchEvent(new MouseEvent(target, swingId, when, mods, px, py, clicks, false, button)));
    }

    /** The OSR render panel is the single child added to the content pane in BrowserWindow. */
    private static Component findOsr(JFrame frame) {
        try {
            Container cp = frame.getContentPane();
            return cp.getComponentCount() > 0 ? cp.getComponent(0) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Adds an NSTrackingArea so {@code mouseMoved:} is delivered (drives HTML :hover). */
    private static void addTrackingArea(Pointer overlay) {
        try {
            Pointer taCls = msg0(objc_getClass, "NSTrackingArea");
            if (taCls == null) return;
            CGRect zero = new CGRect(); // InVisibleRect ignores the rect and auto-tracks bounds
            Pointer ta = objc_msgSend.invokePointer(new Object[]{
                    msg(taCls, sel("alloc")), sel("initWithRect:options:owner:userInfo:"),
                    zero, TRACKING_OPTS, overlay, Pointer.NULL});
            if (ta != null) objc_msgSend.invoke(Void.class, new Object[]{overlay, sel("addTrackingArea:"), ta});
        } catch (Throwable t) {
            LOG.debug("mac carve-out: tracking area skipped (hover only, click still fixed): {}", t.toString());
        }
    }

    /** Finds our decorated Swing frame's NSWindow (class {@code AWTWindow_Normal}). */
    private static Pointer findAwtWindow() {
        Pointer nsApp = msg(msg0(objc_getClass, "NSApplication"), sel("sharedApplication"));
        if (nsApp == null) return null;
        Pointer windows = msg(nsApp, sel("windows"));
        if (windows == null) return null;
        long count = objc_msgSend.invokeLong(new Object[]{windows, sel("count")});
        for (long i = 0; i < count; i++) {
            Pointer w = objc_msgSend.invokePointer(new Object[]{windows, sel("objectAtIndex:"), i});
            if (w == null) continue;
            Pointer namePtr = object_getClassName.invokePointer(new Object[]{w});
            if (namePtr != null && "AWTWindow_Normal".equals(namePtr.getString(0))) return w;
        }
        return null;
    }

    // --- small objc helpers ---

    private static void addMethod(Pointer cls, Pointer selPtr, Callback imp, String types) {
        class_addMethod.invoke(Void.class, new Object[]{cls, selPtr, imp, types});
    }

    private static Pointer sel(String name) {
        return sel_registerName.invokePointer(new Object[]{name});
    }

    private static Pointer msg(Pointer receiver, Pointer selPtr) {
        if (receiver == null || selPtr == null) return null;
        return objc_msgSend.invokePointer(new Object[]{receiver, selPtr});
    }

    /** {@code [class-named-by-string selectorless]} convenience: objc_getClass(name). */
    private static Pointer msg0(Function getClass, String className) {
        return getClass == null ? null : getClass.invokePointer(new Object[]{className});
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static NativeLibrary safeProcess() {
        try {
            return NativeLibrary.getProcess();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Function fn(String name) {
        try {
            return PROC == null ? null : PROC.getFunction(name);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- JNA callback + struct types ---

    /** {@code void (^)(id self, SEL _cmd, NSEvent* event)} forwarding IMP. */
    public interface VoidEventImp extends Callback {
        void invoke(Pointer self, Pointer sel, Pointer event);
    }

    /** {@code BOOL (^)(id self, SEL _cmd)}. */
    public interface BoolNoArgImp extends Callback {
        byte invoke(Pointer self, Pointer sel);
    }

    /** {@code BOOL (^)(id self, SEL _cmd, NSEvent* event)}. */
    public interface BoolEventImp extends Callback {
        byte invoke(Pointer self, Pointer sel, Pointer event);
    }

    /** {@code dispatch_function_t}: {@code void (*)(void* ctx)}. */
    public interface DispatchFn extends Callback {
        void invoke(Pointer ctx);
    }

    /** {@code NSSize} / {@code CGSize} passed by value. */
    public static final class CGSize extends Structure implements Structure.ByValue {
        public double width;
        public double height;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("width", "height");
        }
    }

    /** {@code NSPoint} / {@code CGPoint} passed by value. */
    public static final class CGPoint extends Structure implements Structure.ByValue {
        public double x;
        public double y;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("x", "y");
        }
    }

    /** {@code NSRect} / {@code CGRect} passed by value. */
    public static final class CGRect extends Structure implements Structure.ByValue {
        public double x;
        public double y;
        public double width;
        public double height;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("x", "y", "width", "height");
        }
    }
}
