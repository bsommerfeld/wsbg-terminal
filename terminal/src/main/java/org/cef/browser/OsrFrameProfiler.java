// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

/**
 * The OSR pipeline's flight recorder ({@code WSBG_FRAME_PROFILE=<csv path>}).
 * Off unless the variable is set; {@link #ENABLED} is a compile-time constant
 * for the JIT, so the hooks cost nothing in normal runs.
 *
 * <p>It writes one line per event, timestamps in {@code System.nanoTime()/1000}.
 * On macOS that is the same monotonic clock Chromium's trace events use, so a
 * CSV can be laid directly over a DevTools trace (port 9222) of the same run -
 * which is how the 2026-08-21 stutter hunt was done:
 * <ul>
 *   <li>{@code C,t,dirtyW,dirtyH,full,thread,lockWaitUs,copyUs} - a CEF frame
 *       arrived (end of onPaint); inter-arrival = Chromium's delivery cadence.</li>
 *   <li>{@code P,tStart,tEnd,clipW,clipH,lockWaitUs,syncUs,blitUs} - an EDT
 *       paint; sync = sw→VRAM upload, blit = VRAM→screen.</li>
 *   <li>{@code H,t} - a 4 ms EDT heartbeat; gaps show when the EDT was busy
 *       (under OSR the CEF message pump is scheduled through the EDT).</li>
 *   <li>{@code W,t,<late>,<stack>} / {@code L,t,latency_us=...} - the AppKit
 *       main thread (CEF's browser UI thread on macOS) did not run a posted
 *       no-op within 15 ms; W carries its Java stack at that moment, L the
 *       final latency ({@link OsrMainThreadWatchdog}).</li>
 * </ul>
 */
final class OsrFrameProfiler {

    private static final String PATH = System.getenv("WSBG_FRAME_PROFILE");
    static final boolean ENABLED = PATH != null && !PATH.isBlank();

    private static final Object LOCK = new Object();
    private static BufferedWriter out;
    private static long lines;

    static {
        if (ENABLED) {
            try {
                out = new BufferedWriter(new FileWriter(PATH), 1 << 16);
                Thread flusher = new Thread(() -> {
                    while (true) {
                        try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                        flush();
                    }
                }, "osr-frame-profiler-flush");
                flusher.setDaemon(true);
                flusher.start();
                Runtime.getRuntime().addShutdownHook(new Thread(OsrFrameProfiler::flush));
            } catch (IOException e) {
                System.err.println("[FRAME-PROFILE] cannot open " + PATH + ": " + e);
            }
        }
    }

    private OsrFrameProfiler() {}

    /** C row: end-of-onPaint time, dirty size, full flag, thread, lock wait µs, copy µs. */
    static void cefFrame(long tNanos, int dirtyW, int dirtyH, boolean full, long lockWaitUs, long copyUs) {
        if (!ENABLED) return;
        write("C," + (tNanos / 1000) + "," + dirtyW + "," + dirtyH + "," + (full ? 1 : 0)
                + "," + Thread.currentThread().getName() + "," + lockWaitUs + "," + copyUs);
    }

    /** P row: paint start/end, clip size, lock wait µs, vram sync (upload) µs, screen blit µs. */
    static void edtPaint(long tStartNanos, long tEndNanos, int clipW, int clipH,
            long lockWaitUs, long syncUs, long blitUs) {
        if (!ENABLED) return;
        write("P," + (tStartNanos / 1000) + "," + (tEndNanos / 1000) + "," + clipW + "," + clipH
                + "," + lockWaitUs + "," + syncUs + "," + blitUs);
    }

    /** Free-form marker, e.g. from a repaint request or a heartbeat. */
    static void mark(String kind, long tNanos, String detail) {
        if (!ENABLED) return;
        write(kind + "," + (tNanos / 1000) + "," + detail);
    }

    private static void write(String line) {
        synchronized (LOCK) {
            if (out == null) return;
            try {
                out.write(line);
                out.newLine();
                lines++;
            } catch (IOException ignored) {}
        }
    }

    private static void flush() {
        synchronized (LOCK) {
            if (out == null) return;
            try { out.flush(); } catch (IOException ignored) {}
        }
    }
}
