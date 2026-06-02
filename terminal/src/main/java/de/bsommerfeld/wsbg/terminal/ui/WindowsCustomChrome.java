package de.bsommerfeld.wsbg.terminal.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Window;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windows-only <b>native</b> custom title bar. The window stays a real native
 * frame (resize borders, Aero Snap, native maximize/minimize animations, drop
 * shadow); we only subclass its window procedure to:
 * <ul>
 *   <li><b>WM_NCCALCSIZE</b> — strip the OS caption so the HTML title bar draws
 *   flush at the top (left/right/bottom resize borders stay non-client). Redraw
 *   is suspended across the recalculation to kill resize flicker.</li>
 *   <li><b>WM_NCHITTEST</b> — report the title-bar strip as {@code HTCAPTION}
 *   (native drag + Aero Snap + double-click-maximize) and the very top edge as
 *   {@code HTTOP} (native top-resize). Windows then does all the window
 *   management itself — exactly like a JavaFX/nfx-lib window.</li>
 * </ul>
 *
 * <h3>The OSR bridge</h3>
 * Under OSR the browser renders into a single, same-thread {@code GLCanvas}
 * heavyweight child that fills the client area and would otherwise eat the
 * mouse before the frame's hit-test runs. So each child window is subclassed to
 * return {@code HTTRANSPARENT} over the title-bar strip — the hit falls through
 * to the frame, whose WM_NCHITTEST returns HTCAPTION/HTTOP. Over the buttons and
 * the page body the child stays {@code HTCLIENT}, so OSR delivers those clicks
 * to the HTML.
 *
 * <p>
 * Everything is best-effort: any failure (non-Windows, JNA unavailable, peer not
 * realised) is swallowed and the window keeps its native caption.
 *
 * @see WindowsChrome for the dark-mode theming of the (remaining) frame.
 */
final class WindowsCustomChrome {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsCustomChrome.class);

    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_SETREDRAW = 0x000B;
    private static final int GWLP_WNDPROC = -4;

    // Hit-test results.
    private static final int HTTRANSPARENT = -1;
    private static final int HTCLIENT = 1;
    private static final int HTCAPTION = 2;
    private static final int HTTOP = 12;

    private static final int SM_CYSIZEFRAME = 33;
    private static final int SM_CXPADDEDBORDER = 92;

    // SetWindowPos flags: NOSIZE | NOMOVE | NOZORDER | FRAMECHANGED.
    private static final int SWP_FRAMECHANGED = 0x0001 | 0x0002 | 0x0004 | 0x0020;

    // Title-bar geometry in logical (DIP) px — kept in sync with titlebar.css:
    //   --titlebar-h = 38; three 46px window buttons on the right = 138.
    private static final int TITLEBAR_H_DIP = 38;
    private static final int BUTTONS_W_DIP = 138;
    private static final int TOP_RESIZE_DIP = 6;

    // Hit regions (frame-client relative).
    private static final int R_CLIENT = 0;
    private static final int R_CAPTION = 1;
    private static final int R_TOP = 2;
    private static final int R_BUTTON = 3;

    // Strong refs to installed callbacks — a GC'd JNA callback crashes the next
    // time Windows dispatches a message to that window.
    private static final Map<Pointer, Object> INSTALLED = new ConcurrentHashMap<>();

    private WindowsCustomChrome() {}

    /** Win32 window-procedure callback (stdcall). */
    public interface WndProcCallback extends StdCallLibrary.StdCallCallback {
        long callback(Pointer hWnd, int uMsg, long wParam, long lParam);
    }

    /** EnumChildWindows callback (stdcall). */
    public interface EnumChildCallback extends StdCallLibrary.StdCallCallback {
        boolean callback(Pointer hWnd, Pointer lParam);
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        Pointer SetWindowLongPtrW(Pointer hWnd, int nIndex, WndProcCallback proc);

        long CallWindowProcW(Pointer proc, Pointer hWnd, int msg, long wParam, long lParam);

        long DefWindowProcW(Pointer hWnd, int msg, long wParam, long lParam);

        long SendMessageW(Pointer hWnd, int msg, long wParam, long lParam);

        boolean IsZoomed(Pointer hWnd);

        int GetSystemMetrics(int nIndex);

        int GetDpiForWindow(Pointer hWnd);

        boolean GetClientRect(Pointer hWnd, Pointer rect);

        boolean ClientToScreen(Pointer hWnd, Pointer point);

        boolean InvalidateRect(Pointer hWnd, Pointer rect, boolean erase);

        boolean SetWindowPos(Pointer hWnd, Pointer after, int x, int y, int cx, int cy, int flags);

        boolean EnumChildWindows(Pointer parent, EnumChildCallback cb, Pointer lParam);
    }

    /** Frame proc: caption strip (NCCALCSIZE) + title-bar hit regions (NCHITTEST). */
    private static final class FrameProc implements WndProcCallback {
        volatile Pointer oldProc;

        @Override
        public long callback(Pointer hWnd, int uMsg, long wParam, long lParam) {
            Pointer op = oldProc;
            if (op == null) return User32.INSTANCE.DefWindowProcW(hWnd, uMsg, wParam, lParam);

            switch (uMsg) {
                case WM_NCCALCSIZE -> {
                    if (wParam == 0) break;
                    // Suspend painting across the recalc to avoid resize flicker.
                    User32.INSTANCE.SendMessageW(hWnd, WM_SETREDRAW, 0, 0);
                    Pointer p = new Pointer(lParam);
                    int windowTop = p.getInt(4);
                    long res = User32.INSTANCE.CallWindowProcW(op, hWnd, uMsg, wParam, lParam);
                    int newTop = windowTop; // drop the caption: client flush to top
                    if (User32.INSTANCE.IsZoomed(hWnd)) {
                        // Maximized windows overhang the monitor by the frame
                        // thickness; inset the top by exactly that to avoid clip.
                        newTop += User32.INSTANCE.GetSystemMetrics(SM_CYSIZEFRAME)
                                + User32.INSTANCE.GetSystemMetrics(SM_CXPADDEDBORDER);
                    }
                    p.setInt(4, newTop);
                    User32.INSTANCE.SendMessageW(hWnd, WM_SETREDRAW, 1, 0);
                    User32.INSTANCE.InvalidateRect(hWnd, null, true);
                    return res;
                }
                case WM_NCHITTEST -> {
                    // Let the default proc claim the L/R/bottom borders + corners.
                    long def = User32.INSTANCE.CallWindowProcW(op, hWnd, uMsg, wParam, lParam);
                    if (def != HTCLIENT) return def;
                    return switch (regionAt(hWnd, loword(lParam), hiword(lParam))) {
                        case R_TOP -> HTTOP;
                        case R_CAPTION -> HTCAPTION;
                        default -> HTCLIENT; // buttons + body
                    };
                }
                default -> { /* fall through */ }
            }
            return User32.INSTANCE.CallWindowProcW(op, hWnd, uMsg, wParam, lParam);
        }
    }

    /** Child (GLCanvas) proc: punch the title-bar strip through to the frame. */
    private static final class ChildProc implements WndProcCallback {
        final Pointer frame;
        volatile Pointer oldProc;

        ChildProc(Pointer frame) { this.frame = frame; }

        @Override
        public long callback(Pointer hWnd, int uMsg, long wParam, long lParam) {
            Pointer op = oldProc;
            if (op == null) return User32.INSTANCE.DefWindowProcW(hWnd, uMsg, wParam, lParam);
            if (uMsg == WM_NCHITTEST) {
                int r = regionAt(frame, loword(lParam), hiword(lParam));
                if (r == R_CAPTION || r == R_TOP) {
                    return HTTRANSPARENT; // fall through to the frame's hit-test
                }
            }
            return User32.INSTANCE.CallWindowProcW(op, hWnd, uMsg, wParam, lParam);
        }
    }

    /**
     * Installs the native chrome on {@code window} and bridges its OSR child
     * canvas. Safe to call more than once; children that appear later (the
     * GLCanvas is realised lazily) are picked up by a second call.
     */
    static void install(Window window) {
        if (!isWindows()) return;
        try {
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd == null) return;

            if (!INSTALLED.containsKey(hwnd)) {
                FrameProc fp = new FrameProc();
                INSTALLED.put(hwnd, fp); // ref BEFORE install
                Pointer old = User32.INSTANCE.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, fp);
                if (old == null) {
                    INSTALLED.remove(hwnd);
                    LOG.debug("SetWindowLongPtrW(frame) returned null; keeping native caption.");
                    return;
                }
                fp.oldProc = old;
                User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAMECHANGED);
                LOG.info("Installed native custom window chrome (caption-less + hit-test).");
            }

            bridgeChildren(hwnd);
        } catch (Throwable t) {
            LOG.debug("Could not install custom window chrome: {}", t.toString());
        }
    }

    /** Subclasses every (not-yet-bridged) child window to forward title-bar hits. */
    private static void bridgeChildren(Pointer frame) {
        try {
            User32.INSTANCE.EnumChildWindows(frame, (child, l) -> {
                if (!INSTALLED.containsKey(child)) {
                    ChildProc cp = new ChildProc(frame);
                    INSTALLED.put(child, cp);
                    Pointer old = User32.INSTANCE.SetWindowLongPtrW(child, GWLP_WNDPROC, cp);
                    if (old == null) {
                        INSTALLED.remove(child);
                    } else {
                        cp.oldProc = old;
                    }
                }
                return true; // keep enumerating
            }, null);
        } catch (Throwable t) {
            LOG.debug("Could not bridge child windows: {}", t.toString());
        }
    }

    /**
     * Classifies a screen point against the frame's client area: the top
     * {@link #TITLEBAR_H_DIP} px is the title bar — its very top edge is a resize
     * strip ({@link #R_TOP}), its right {@link #BUTTONS_W_DIP} px are the window
     * buttons ({@link #R_BUTTON}, treated as client), the rest is the draggable
     * caption ({@link #R_CAPTION}). Everything else is {@link #R_CLIENT}.
     */
    private static int regionAt(Pointer frame, int screenX, int screenY) {
        try {
            Memory rc = new Memory(16);
            if (!User32.INSTANCE.GetClientRect(frame, rc)) return R_CLIENT;
            int cw = rc.getInt(8);  // right
            int ch = rc.getInt(12); // bottom

            Memory origin = new Memory(8); // POINT{0,0}
            origin.setInt(0, 0);
            origin.setInt(4, 0);
            if (!User32.INSTANCE.ClientToScreen(frame, origin)) return R_CLIENT;
            int relX = screenX - origin.getInt(0);
            int relY = screenY - origin.getInt(4);

            int dpi = User32.INSTANCE.GetDpiForWindow(frame);
            if (dpi <= 0) dpi = 96;
            int tbH = TITLEBAR_H_DIP * dpi / 96;
            int btnW = BUTTONS_W_DIP * dpi / 96;
            int topR = TOP_RESIZE_DIP * dpi / 96;

            if (relX < 0 || relX >= cw || relY < 0 || relY >= ch) return R_CLIENT;
            if (relY >= tbH) return R_CLIENT;
            if (relY < topR) return R_TOP;
            if (relX >= cw - btnW) return R_BUTTON;
            return R_CAPTION;
        } catch (Throwable t) {
            return R_CLIENT;
        }
    }

    private static int loword(long lParam) {
        return (short) (lParam & 0xFFFF);
    }

    private static int hiword(long lParam) {
        return (short) ((lParam >> 16) & 0xFFFF);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
