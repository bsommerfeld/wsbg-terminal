// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import de.bsommerfeld.wsbg.terminal.ui.scroll.WheelScroll;
import de.bsommerfeld.wsbg.terminal.ui.scroll.WheelScrollPolicy;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.dnd.DropTarget;

import javax.swing.JComponent;
import javax.swing.MenuSelectionManager;

/**
 * Wires the OSR render panel's AWT input (mouse / motion / wheel / key / focus /
 * component listeners + the DnD drop target) into the {@link SwingCefBrowser}
 * shell, and translates AWT wheel events into CEF unit-scroll events via the
 * {@link WheelScrollPolicy} seam. Extracted from {@code SwingCefBrowser} so the
 * shell keeps only the {@code CefRenderHandler} protocol.
 *
 * <p>The browser's {@code sendMouseEvent}/{@code sendKeyEvent}/
 * {@code sendMouseWheelEvent}/{@code setFocus} are the inherited
 * {@code CefBrowser_N} protocol methods, package-visible here.
 */
final class OsrInputRouter {

    private final JComponent panel;
    private final SwingCefBrowser browser;
    // The wheel-scroll seam. OSR has no native window, so AWT wheel events are
    // re-scaled here before being handed to CEF — see WheelScrollPolicy.
    private final WheelScrollPolicy wheelScrollPolicy;

    OsrInputRouter(JComponent panel, SwingCefBrowser browser, WheelScrollPolicy wheelScrollPolicy) {
        this.panel = panel;
        this.browser = browser;
        this.wheelScrollPolicy = wheelScrollPolicy;
    }

    void install() {
        MouseListener ml = new MouseListener() {
            public void mousePressed(MouseEvent e) { panel.requestFocusInWindow(); browser.sendMouseEvent(e); }
            public void mouseReleased(MouseEvent e) { browser.sendMouseEvent(e); }
            public void mouseEntered(MouseEvent e) { browser.sendMouseEvent(e); }
            public void mouseExited(MouseEvent e) { browser.sendMouseEvent(e); }
            public void mouseClicked(MouseEvent e) { browser.sendMouseEvent(e); }
        };
        panel.addMouseListener(ml);
        panel.addMouseMotionListener(new MouseMotionListener() {
            public void mouseMoved(MouseEvent e) { browser.sendMouseEvent(e); }
            public void mouseDragged(MouseEvent e) { browser.sendMouseEvent(e); }
        });
        // OSR has no native window, so AWT delivers the wheel event and JCEF's
        // native bridge forwards getUnitsToScroll() (OS scroll-lines) straight
        // into CEF, which reads it as pixels → ~3px/notch, "sticky" scroll. The
        // WheelScrollPolicy decides direction + magnitude; forwardScroll does the
        // delivery. (Replaces the old as-is forwarding; see ui/scroll/.)
        panel.addMouseWheelListener(new MouseWheelListener() {
            public void mouseWheelMoved(MouseWheelEvent e) {
                forwardScroll(wheelScrollPolicy.translate(e), e);
            }
        });
        panel.addKeyListener(new KeyListener() {
            public void keyTyped(KeyEvent e) { browser.sendKeyEvent(e); }
            public void keyPressed(KeyEvent e) { browser.sendKeyEvent(e); }
            public void keyReleased(KeyEvent e) { browser.sendKeyEvent(e); }
        });
        panel.setFocusable(true);
        panel.addFocusListener(new FocusListener() {
            public void focusLost(FocusEvent e) { browser.setFocus(false); }
            public void focusGained(FocusEvent e) {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                browser.setFocus(true);
            }
        });
        panel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { browser.handleComponentResized(); }
            @Override public void componentMoved(ComponentEvent e) { browser.handleComponentMoved(); }
        });
        new DropTarget(panel, new CefDropTargetListener(browser));
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
            browser.sendMouseWheelEvent(buildUnitScroll(src, scroll.unitsY(), false));
        }
        if (scroll.unitsX() != 0) {
            browser.sendMouseWheelEvent(buildUnitScroll(src, scroll.unitsX(), true));
        }
    }

    private MouseWheelEvent buildUnitScroll(MouseWheelEvent src, int units, boolean horizontal) {
        int mods = horizontal
                ? src.getModifiersEx() | InputEvent.SHIFT_DOWN_MASK
                : src.getModifiersEx() & ~InputEvent.SHIFT_DOWN_MASK;
        int rotation = units >= 0 ? 1 : -1;
        int amount = Math.abs(units); // getUnitsToScroll() = amount × rotation = units
        return new MouseWheelEvent(panel, src.getID(), src.getWhen(), mods,
                src.getX(), src.getY(), 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, amount, rotation);
    }
}
