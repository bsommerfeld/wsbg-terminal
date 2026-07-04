// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.awt.Rectangle;
import java.util.function.BooleanSupplier;

/**
 * Live-resize gate — the EDT-confined resize-ack state machine extracted from
 * {@link SwingCefBrowser}.
 *
 * <p>componentResized fires per AWT event (well above 60/s during a macOS live
 * resize) and CEF re-lays-out + re-rasters the FULL page for every size it
 * observes. Crucially, CEF polls getViewRect on its own schedule, so merely
 * throttling wasResized does NOT help — measured: an excessive drag on a heavy
 * view queued dozens of stale-size re-layouts in the renderer and the frames
 * drained up to ~50s late (the "crippled frame sits there after release"
 * zombie; further resizing only lengthened the queue). Therefore the LIVE size
 * is tracked in {@code desiredW/H} only, and {@link #browserRect} — the only
 * thing CEF ever sees — is committed exclusively inside {@link #signalResize()}:
 * one new size per gate cycle, renderer queue depth capped at ~1. The ack is the
 * arrival of a size-changed frame; the timeout frees the gate when CEF never
 * delivers one (size unchanged, frame coalesced away) so it can't wedge shut.
 *
 * <p>All gate fields are EDT-confined; {@link #browserRect} is read from CEF
 * threads but is mutated only on the EDT. It is the single instance CEF reads by
 * reference via {@link #viewRect()} — callers must NOT copy it on read.
 */
final class OsrResizeGate {

    /** Commits a new size to CEF: calls {@code wasResized(w,h)} then {@code invalidate()}. */
    interface Commit {
        void commit(int w, int h);
    }

    private static final int RESIZE_ACK_TIMEOUT_MS = 250;

    private final Rectangle browserRect = new Rectangle(0, 0, 1, 1); // CEF issue #1437.
    private int desiredW = 1, desiredH = 1; // live panel size (EDT)
    private boolean resizeSignalInFlight;
    private boolean resizePendingSignal;
    private final javax.swing.Timer resizeAckTimeout =
            new javax.swing.Timer(RESIZE_ACK_TIMEOUT_MS, e -> unblock());

    private final BooleanSupplier closed;
    private final Commit commit;

    OsrResizeGate(BooleanSupplier closed, Commit commit) {
        this.closed = closed;
        this.commit = commit;
        resizeAckTimeout.setRepeats(false);
    }

    /** The one instance CEF reads by reference — never returns a copy. */
    Rectangle viewRect() {
        return browserRect;
    }

    void onLiveResize(int w, int h) {
        desiredW = Math.max(1, w);
        desiredH = Math.max(1, h);
        if (browserRect.width == desiredW && browserRect.height == desiredH) {
            return; // already committed at this size
        }
        if (resizeSignalInFlight) {
            resizePendingSignal = true;
        } else {
            signalResize();
        }
    }

    // The in-flight resize render arrived (a size-changed frame, posted from
    // the CEF paint thread) or timed out — open the gate and commit the
    // newest size if it moved on.
    void unblock() {
        resizeAckTimeout.stop();
        resizeSignalInFlight = false;
        if (resizePendingSignal
                || browserRect.width != desiredW || browserRect.height != desiredH) {
            signalResize();
        }
    }

    // Commits the newest live size to browserRect (CEF's view of the world)
    // and signals it — the ONLY place either happens, so CEF sees exactly one
    // new size per gate cycle no matter how often it polls getViewRect.
    private void signalResize() {
        if (closed.getAsBoolean()) return;
        browserRect.setBounds(0, 0, desiredW, desiredH);
        resizeSignalInFlight = true;
        resizePendingSignal = false;
        resizeAckTimeout.restart();
        // wasResized + invalidate. Software OSR only composites on DAMAGE — on a
        // static view the resized frame otherwise waits for the next organic
        // repaint (the 1Hz footer clock was the only pacer on the settings view:
        // measured, the final size arrived a full second per gate cycle).
        // Invalidate marks the whole view dirty so the post-resize composite
        // happens NOW.
        commit.commit(desiredW, desiredH);
    }
}
