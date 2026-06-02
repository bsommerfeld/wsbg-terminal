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
 * frame (native drag, Aero Snap, native maximize/minimize animations), we just
 * subclass its window procedure to make it borderless and report hit regions:
 * <ul>
 *   <li><b>WM_NCCALCSIZE</b> — claim the whole window as client area (no caption
 *   AND no resize borders), so the HTML draws flush on every edge with no native
 *   frame strip. Redraw is suspended across the recalc to cut resize flicker.
 *   When maximized we inset by the frame overhang so content isn't clipped.</li>
 *   <li><b>WM_NCHITTEST</b> — synthesize every resize edge/corner from a thin
 *   border band, report the title-bar strip as {@code HTCAPTION} (native drag +
 *   Aero Snap + double-click-maximize), and leave the buttons + theme toggle +
 *   page body as {@code HTCLIENT} so their clicks reach the HTML.</li>
 * </ul>
 *
 * <p>
 * The browser is a lightweight Swing component ({@code SwingCefBrowser}), so the
 * frame HWND receives all mouse/hit-test messages directly — there is no
 * heavyweight child to intercept them. {@link #bridgeChildren} stays as a safety
 * net (it forwards title-bar hits through any heavyweight child via
 * {@code HTTRANSPARENT}) but is a no-op with the software renderer.
 *
 * <p>Everything is best-effort: any failure degrades to the native caption.
 *
 * @see WindowsChrome for the dark-mode theming of the frame.
 */
final class WindowsCustomChrome {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsCustomChrome.class);

    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_SETREDRAW = 0x000B;
    private static final int WM_SYSCOMMAND = 0x0112;
    private static final int SC_MINIMIZE = 0xF020;
    private static final int GWLP_WNDPROC = -4;

    // Hit-test results.
    private static final int HTTRANSPARENT = -1;
    private static final int HTCLIENT = 1;
    private static final int HTCAPTION = 2;
    private static final int HTLEFT = 10, HTRIGHT = 11, HTTOP = 12, HTTOPLEFT = 13,
            HTTOPRIGHT = 14, HTBOTTOM = 15, HTBOTTOMLEFT = 16, HTBOTTOMRIGHT = 17;

    private static final int SM_CXSIZEFRAME = 32;
    private static final int SM_CYSIZEFRAME = 33;
    private static final int SM_CXPADDEDBORDER = 92;

    private static final int SWP_FRAMECHANGED = 0x0001 | 0x0002 | 0x0004 | 0x0020;

    // Title-bar geometry in logical (DIP) px — kept in sync with titlebar.css:
    //   --titlebar-h = 38; window buttons (right) 3×46 = 138; theme toggle (left).
    private static final int TITLEBAR_H_DIP = 38;
    private static final int BUTTONS_W_DIP = 138;
    private static final int THEME_W_DIP = 52;
    private static final int RESIZE_DIP = 6;

    // Strong refs to installed callbacks — a GC'd JNA callback crashes the next
    // time Windows dispatches a message to that window.
    private static final Map<Pointer, Object> INSTALLED = new ConcurrentHashMap<>();

    private WindowsCustomChrome() {}

    public interface WndProcCallback extends StdCallLibrary.StdCallCallback {
        long callback(Pointer hWnd, int uMsg, long wParam, long lParam);
    }

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

    /** Frame proc: borderless client (NCCALCSIZE) + hit regions (NCHITTEST). */
    private static final class FrameProc implements WndProcCallback {
        volatile Pointer oldProc;

        @Override
        public long callback(Pointer hWnd, int uMsg, long wParam, long lParam) {
            Pointer op = oldProc;
            if (op == null) return User32.INSTANCE.DefWindowProcW(hWnd, uMsg, wParam, lParam);

            switch (uMsg) {
                case WM_NCCALCSIZE -> {
                    if (wParam == 0) break;
                    // Claim the entire window as client: no caption, no resize
                    // borders → no native frame edge anywhere. Suspend redraw to
                    // avoid flicker across the recalc.
                    User32.INSTANCE.SendMessageW(hWnd, WM_SETREDRAW, 0, 0);
                    Pointer p = new Pointer(lParam);
                    if (User32.INSTANCE.IsZoomed(hWnd)) {
                        // Maximized windows overhang the monitor by the frame
                        // thickness; inset all sides by it so nothing is clipped.
                        int fx = User32.INSTANCE.GetSystemMetrics(SM_CXSIZEFRAME)
                                + User32.INSTANCE.GetSystemMetrics(SM_CXPADDEDBORDER);
                        int fy = User32.INSTANCE.GetSystemMetrics(SM_CYSIZEFRAME)
                                + User32.INSTANCE.GetSystemMetrics(SM_CXPADDEDBORDER);
                        p.setInt(0, p.getInt(0) + fx);
                        p.setInt(4, p.getInt(4) + fy);
                        p.setInt(8, p.getInt(8) - fx);
                        p.setInt(12, p.getInt(12) - fy);
                    }
                    User32.INSTANCE.SendMessageW(hWnd, WM_SETREDRAW, 1, 0);
                    User32.INSTANCE.InvalidateRect(hWnd, null, true);
                    return 0;
                }
                case WM_NCHITTEST -> {
                    return hitTest(hWnd, loword(lParam), hiword(lParam));
                }
                default -> { /* fall through */ }
            }
            return User32.INSTANCE.CallWindowProcW(op, hWnd, uMsg, wParam, lParam);
        }
    }

    /** Child safety net (no-op with the lightweight SwingCefBrowser). */
    private static final class ChildProc implements WndProcCallback {
        final Pointer frame;
        volatile Pointer oldProc;

        ChildProc(Pointer frame) { this.frame = frame; }

        @Override
        public long callback(Pointer hWnd, int uMsg, long wParam, long lParam) {
            Pointer op = oldProc;
            if (op == null) return User32.INSTANCE.DefWindowProcW(hWnd, uMsg, wParam, lParam);
            if (uMsg == WM_NCHITTEST && hitTest(frame, loword(lParam), hiword(lParam)) != HTCLIENT) {
                return HTTRANSPARENT; // fall through to the frame's hit-test
            }
            return User32.INSTANCE.CallWindowProcW(op, hWnd, uMsg, wParam, lParam);
        }
    }

    static void install(Window window) {
        if (!isWindows()) return;
        try {
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd == null) return;

            if (!INSTALLED.containsKey(hwnd)) {
                FrameProc fp = new FrameProc();
                INSTALLED.put(hwnd, fp);
                Pointer old = User32.INSTANCE.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, fp);
                if (old == null) {
                    INSTALLED.remove(hwnd);
                    LOG.debug("SetWindowLongPtrW(frame) returned null; keeping native caption.");
                    return;
                }
                fp.oldProc = old;
                User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAMECHANGED);
                LOG.info("Installed native custom window chrome (borderless + hit-test).");
            }
            bridgeChildren(hwnd);
        } catch (Throwable t) {
            LOG.debug("Could not install custom window chrome: {}", t.toString());
        }
    }

    /** Minimizes the window with the native animation (SC_MINIMIZE). */
    static boolean minimize(Window window) {
        if (!isWindows()) return false;
        try {
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd == null) return false;
            User32.INSTANCE.SendMessageW(hwnd, WM_SYSCOMMAND, SC_MINIMIZE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void bridgeChildren(Pointer frame) {
        try {
            User32.INSTANCE.EnumChildWindows(frame, (child, l) -> {
                if (!INSTALLED.containsKey(child)) {
                    ChildProc cp = new ChildProc(frame);
                    INSTALLED.put(child, cp);
                    Pointer old = User32.INSTANCE.SetWindowLongPtrW(child, GWLP_WNDPROC, cp);
                    if (old == null) INSTALLED.remove(child);
                    else cp.oldProc = old;
                }
                return true;
            }, null);
        } catch (Throwable t) {
            LOG.debug("Could not bridge child windows: {}", t.toString());
        }
    }

    /**
     * Classifies a screen point into a Win32 hit-test code against the frame's
     * client rect: a thin border band → resize edges/corners; the title-bar
     * strip (minus the left theme toggle and right window buttons) → HTCAPTION;
     * everything else → HTCLIENT.
     */
    private static int hitTest(Pointer frame, int screenX, int screenY) {
        try {
            Memory rc = new Memory(16);
            if (!User32.INSTANCE.GetClientRect(frame, rc)) return HTCLIENT;
            int cw = rc.getInt(8), ch = rc.getInt(12);

            Memory origin = new Memory(8);
            origin.setInt(0, 0);
            origin.setInt(4, 0);
            if (!User32.INSTANCE.ClientToScreen(frame, origin)) return HTCLIENT;
            int x = screenX - origin.getInt(0);
            int y = screenY - origin.getInt(4);
            if (x < 0 || y < 0 || x >= cw || y >= ch) return HTCLIENT;

            int dpi = User32.INSTANCE.GetDpiForWindow(frame);
            if (dpi <= 0) dpi = 96;
            int tbH = TITLEBAR_H_DIP * dpi / 96;
            int btnW = BUTTONS_W_DIP * dpi / 96;
            int themeW = THEME_W_DIP * dpi / 96;
            int rs = RESIZE_DIP * dpi / 96;

            if (!User32.INSTANCE.IsZoomed(frame)) {
                boolean left = x < rs, right = x >= cw - rs, top = y < rs, bottom = y >= ch - rs;
                if (top && left) return HTTOPLEFT;
                if (top && right) return HTTOPRIGHT;
                if (bottom && left) return HTBOTTOMLEFT;
                if (bottom && right) return HTBOTTOMRIGHT;
                if (left) return HTLEFT;
                if (right) return HTRIGHT;
                if (top) return HTTOP;
                if (bottom) return HTBOTTOM;
            }

            if (y < tbH) {
                if (x < themeW || x >= cw - btnW) return HTCLIENT; // theme + buttons
                return HTCAPTION;
            }
            return HTCLIENT;
        } catch (Throwable t) {
            return HTCLIENT;
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
