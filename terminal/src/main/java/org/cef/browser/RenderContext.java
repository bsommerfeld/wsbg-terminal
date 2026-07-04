// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

/**
 * The tiny slice of the {@link SwingCefBrowser} shell that {@link OsrRenderPanel}
 * needs: the live device scale factor, whether the browser is closed, and the
 * parent-changed focus notification. Extracted so the raster/GPU pipeline
 * ({@code OsrRenderPanel}) is a standalone component with no back-reference to
 * the {@code CefRenderHandler} protocol shell.
 *
 * <p>{@link #renderScale()} is read live on every paint — it must reflect the
 * shell's current {@code scaleFactor_}, not a value captured at construction.
 */
interface RenderContext {
    /** Current device scale factor (HiDPI). Read live per paint. */
    double renderScale();

    /** Whether the owning browser has been closed. */
    boolean isRenderClosed();

    /** Sends the OSR after-parent-changed focus notification. */
    void notifyParentChanged();
}
