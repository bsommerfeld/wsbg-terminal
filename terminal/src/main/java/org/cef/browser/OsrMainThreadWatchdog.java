// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.lang.reflect.Method;

/**
 * Diagnostic (WSBG_FRAME_PROFILE): posts a no-op to the AppKit main thread every
 * 10 ms and, when it has not run after 15 ms, records the main thread's Java
 * stack ("W" rows) - under OSR that thread is also CEF's browser UI thread, and
 * a stall there is a stall of every frame. macOS only; silently inert elsewhere.
 */
final class OsrMainThreadWatchdog {

    private OsrMainThreadWatchdog() {}


    static void start() {
        if (!OsrFrameProfiler.ENABLED) return;
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        Method perform;
        try {
            Class<?> tk = Class.forName("sun.lwawt.macosx.LWCToolkit");
            perform = tk.getDeclaredMethod("performOnMainThreadAfterDelay", Runnable.class, long.class);
            perform.setAccessible(true);
        } catch (Throwable t) {
            System.err.println("[FRAME-PROFILE] main-thread watchdog unavailable: " + t);
            return;
        }
        Thread appKit = null;
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            if ("AppKit Thread".equals(th.getName())) { appKit = th; break; }
        }
        final Thread appKitThread = appKit;
        Thread wd = new Thread(() -> {
            final long[] ran = {0};
            while (true) {
                long posted = System.nanoTime();
                ran[0] = 0;
                try {
                    perform.invoke(null, (Runnable) () -> ran[0] = System.nanoTime(), 0L);
                } catch (Throwable t) {
                    return;
                }
                boolean reported = false;
                for (int i = 0; i < 200 && ran[0] == 0; i++) {   // up to ~2 s
                    try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                    long waited = (System.nanoTime() - posted) / 1_000_000;
                    if (!reported && waited >= 15 && ran[0] == 0 && appKitThread != null) {
                        StackTraceElement[] st = appKitThread.getStackTrace();
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < Math.min(6, st.length); k++) {
                            sb.append(k == 0 ? "" : " < ").append(st[k].getClassName().replaceAll(".*\\.", ""))
                              .append('.').append(st[k].getMethodName());
                        }
                        OsrFrameProfiler.mark("W", System.nanoTime(), waited + "ms-late," + (st.length == 0 ? "(native)" : sb));
                        reported = true;
                    }
                }
                if (ran[0] != 0) {
                    long lat = (ran[0] - posted) / 1000;
                    if (lat > 15_000) OsrFrameProfiler.mark("L", ran[0], "latency_us=" + lat);
                }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        }, "osr-main-thread-watchdog");
        wd.setDaemon(true);
        wd.start();
    }
}
