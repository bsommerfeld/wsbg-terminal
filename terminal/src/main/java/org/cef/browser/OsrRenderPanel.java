// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.VolatileImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import javax.swing.JComponent;

/**
 * The on-screen surface. Holds the latest CEF frame as a BufferedImage and blits
 * it (scaled to the component size) each Swing paint. Popups (e.g.
 * {@code <select>} dropdowns) arrive as a separate buffer composited on top.
 *
 * <p>Extracted verbatim from the {@link SwingCefBrowser} inner {@code RenderPanel}
 * — the whole raster/GPU pipeline — with its three back-references to the shell
 * routed through {@link RenderContext} ({@code scaleFactor_} → {@link
 * RenderContext#renderScale()}, {@code isClosed()} → {@link
 * RenderContext#isRenderClosed()}, {@code notifyAfterParentChanged()} → {@link
 * RenderContext#notifyParentChanged()}). The {@code synchronized(lock)} critical
 * section (CPU blit on the CEF thread + VRAM sync + screen blit on the EDT) is
 * preserved exactly, including the {@code getSubimage} shared-raster VIEW upload
 * and the grow-only step-padded allocation.
 */
final class OsrRenderPanel extends JComponent {

    // Paint-path diagnostics (WSBG_PAINT_PROFILE=true): logs slow EDT paints and
    // the CEF dirty-rect sizes, to tell "CEF reports full-frame damage" apart
    // from "the Swing blit is slow" when hunting size-dependent jank.
    private static final boolean PAINT_PROFILE =
            Boolean.parseBoolean(System.getenv("WSBG_PAINT_PROFILE"));

    private final RenderContext ctx;

    private final Object lock = new Object();
    // Grow-only, step-padded allocation: a live resize delivers a new
    // frame size on nearly every frame, and reallocating a ~100MB ARGB
    // raster plus a same-sized Metal texture per frame is GC/driver churn
    // that stutters the whole app. The live frame occupies the top-left
    // frameW_ x frameH_ of the (possibly larger) buffer; every blit is
    // stride-aware.
    private BufferedImage image_;
    private int frameW_, frameH_; // live CEF frame size (device px)
    private BufferedImage popupImage_;
    private final Rectangle popupRect_ = new Rectangle();
    private boolean popupVisible_ = false;

    // GPU-resident mirror of image_. image_'s raster is written directly,
    // which makes it permanently "unmanaged" for Java2D — drawing it to the
    // screen re-uploads the ENTIRE buffer as a texture on EVERY paint
    // (measured: a fixed ~6ms at 2.5K, ~24ms at 6.7K device px, regardless
    // of clip size — the big-window jank). Instead, paintComponent uploads
    // only the accumulated dirty region into this VolatileImage and blits
    // that to the screen: VRAM→VRAM, effectively free at any window size.
    private VolatileImage vram_;
    private final Rectangle vramDirty_ = new Rectangle(); // device px pending upload
    private boolean vramFull_ = true;                     // full re-upload needed

    OsrRenderPanel(RenderContext ctx) {
        this.ctx = ctx;
        setOpaque(true);
        setBackground(Color.BLACK);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        ctx.notifyParentChanged();
    }

    @Override
    public void removeNotify() {
        synchronized (lock) {
            if (vram_ != null) {
                vram_.flush();   // surface is tied to the peer being removed
                vram_ = null;
                vramFull_ = true;
            }
        }
        if (!ctx.isRenderClosed()) ctx.notifyParentChanged();
        super.removeNotify();
    }

    void setPopupRect(Rectangle r) {
        synchronized (lock) {
            popupRect_.setBounds(r);
            popupVisible_ = true;
        }
    }

    void clearPopup() {
        synchronized (lock) {
            popupVisible_ = false;
        }
        repaint();
    }

    // Copies only the dirty region out of CEF's buffer and schedules an
    // equally narrow Swing repaint. A full-frame copy at 60fps is a
    // multi-GB/s memcpy on a Retina display and was the app-wide scroll
    // bottleneck; the dirty path also shrinks the lock's critical section
    // to the changed rows, so CEF's paint thread and the EDT no longer
    // stall each other on busy frames.
    /** Returns true when this frame changed the buffer size (a resize render arrived). */
    boolean onPaint(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer,
            int width, int height) {
        if (width <= 0 || height <= 0) return false;
        IntBuffer src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        Rectangle repaintArea = null; // null = repaint the whole component
        boolean resizedFrame = false;
        long t0 = PAINT_PROFILE ? System.nanoTime() : 0;
        long tLocked = 0;
        synchronized (lock) {
            if (PAINT_PROFILE) tLocked = System.nanoTime();
            if (popup) {
                popupImage_ = blit(popupImage_, src, width, height);
                if (popupVisible_) {
                    // popupRect_ is already in component (logical) space.
                    repaintArea = grow(new Rectangle(popupRect_), 2);
                }
            } else if (image_ != null && frameW_ == width && frameH_ == height
                    && dirtyRects != null && dirtyRects.length > 0
                    && src.remaining() >= width * height) {
                Rectangle devicePx = blitDirty(src, width, dirtyRects, height);
                if (PAINT_PROFILE) {
                    long now = System.nanoTime();
                    System.out.println("[PAINT] cef dirty=" + devicePx.width + "x"
                            + devicePx.height + " of " + width + "x" + height
                            + " rects=" + dirtyRects.length
                            + " lockWait=" + (tLocked - t0) / 1_000_000 + "ms"
                            + " copy=" + (now - tLocked) / 1_000_000 + "ms");
                }
                if (devicePx.isEmpty()) return false; // nothing visible changed
                if (vramDirty_.isEmpty()) {
                    vramDirty_.setBounds(devicePx);
                } else {
                    vramDirty_.add(devicePx);
                }
                // The image is a device-pixel buffer drawn at bufferPx/scale;
                // map the dirty union into component space, padded for the
                // scale rounding.
                double s = ctx.renderScale() <= 0 ? 1.0 : ctx.renderScale();
                repaintArea = grow(new Rectangle(
                        (int) Math.floor(devicePx.x / s),
                        (int) Math.floor(devicePx.y / s),
                        (int) Math.ceil(devicePx.width / s),
                        (int) Math.ceil(devicePx.height / s)), 2);
            } else {
                // First frame, resize, or an unusable dirty list → full copy.
                resizedFrame = frameW_ != width || frameH_ != height;
                blitFrame(src, width, height);
                vramFull_ = true;
                if (PAINT_PROFILE) {
                    System.out.println("[PAINT] cef full=" + width + "x" + height
                            + " resized=" + resizedFrame
                            + " copy=" + (System.nanoTime() - tLocked) / 1_000_000 + "ms");
                }
            }
        }
        if (repaintArea == null) {
            repaint();
        } else {
            repaint(repaintArea);
        }
        return resizedFrame;
    }

    // Allocation rounding for image_/vram_: an outward drag re-allocates
    // once per 256 device px instead of once per frame.
    private static final int ALLOC_STEP = 256;

    // BGRA little-endian bytes read as a little-endian int yield 0xAARRGGBB
    // = ARGB, so the buffer copies straight into a TYPE_INT_ARGB raster.
    // (Popup buffers only — the main frame goes through blitFrame.)
    private BufferedImage blit(BufferedImage img, IntBuffer src, int w, int h) {
        if (img == null || img.getWidth() != w || img.getHeight() != h) {
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        src.get(dst, 0, Math.min(dst.length, src.remaining()));
        return img;
    }

    /**
     * Copies a full CEF frame into the grow-only main buffer, growing it
     * (step-padded) only when the frame outgrows the allocation.
     */
    private void blitFrame(IntBuffer src, int w, int h) {
        if (image_ == null || image_.getWidth() < w || image_.getHeight() < h) {
            int aw = Math.max(pad(w), image_ == null ? 0 : image_.getWidth());
            int ah = Math.max(pad(h), image_ == null ? 0 : image_.getHeight());
            image_ = new BufferedImage(aw, ah, BufferedImage.TYPE_INT_ARGB);
        }
        int stride = image_.getWidth();
        int[] dst = ((DataBufferInt) image_.getRaster().getDataBuffer()).getData();
        int rows = Math.min(h, src.remaining() / w);
        if (stride == w) {
            src.get(dst, 0, rows * w);
        } else {
            for (int row = 0; row < rows; row++) {
                src.position(row * w);
                src.get(dst, row * stride, w);
            }
        }
        frameW_ = w;
        frameH_ = h;
    }

    private static int pad(int px) {
        return ((px + ALLOC_STEP - 1) / ALLOC_STEP) * ALLOC_STEP;
    }

    /**
     * Copies only {@code dirtyRects} (device px; src rows are w wide, dst
     * rows are the allocation stride) into the existing image and returns
     * their union, empty when no rect intersected the buffer. Only called
     * when the frame size matches frameW_ x frameH_, so every dst offset
     * is within the allocation.
     */
    private Rectangle blitDirty(IntBuffer src, int w, Rectangle[] dirtyRects, int h) {
        int stride = image_.getWidth();
        int[] dst = ((DataBufferInt) image_.getRaster().getDataBuffer()).getData();
        Rectangle bounds = new Rectangle(0, 0, w, h);
        Rectangle union = null;
        for (Rectangle r : dirtyRects) {
            Rectangle c = r.intersection(bounds);
            if (c.isEmpty()) continue;
            for (int row = 0; row < c.height; row++) {
                src.position((c.y + row) * w + c.x);
                src.get(dst, (c.y + row) * stride + c.x, c.width);
            }
            union = union == null ? c : union.union(c);
        }
        return union == null ? new Rectangle() : union;
    }

    private Rectangle grow(Rectangle r, int pad) {
        r.grow(pad, pad);
        return r;
    }

    /**
     * Brings {@link #vram_} up to date with {@link #image_} (dirty-region
     * sub-rect upload) and returns the surface to draw — {@code vram_}
     * normally, {@code image_} itself when no volatile surface is available
     * (component not displayable, incompatible surface). Runs on the EDT
     * under {@code lock}.
     */
    private java.awt.Image syncVram() {
        // vram_ mirrors the ALLOCATION (grow-only, step-padded), so a live
        // resize within the padding never re-creates the Metal surface;
        // only outgrowing the allocation does (once per 256px of drag).
        int aw = image_.getWidth(), ah = image_.getHeight();
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) return image_;
        if (vram_ == null || vram_.getWidth() != aw || vram_.getHeight() != ah) {
            if (vram_ != null) vram_.flush();
            vram_ = createVolatileImage(aw, ah);
            vramFull_ = true;
        }
        if (vram_ == null) return image_;
        int state = vram_.validate(gc);
        if (state == VolatileImage.IMAGE_INCOMPATIBLE) {
            vram_.flush();
            vram_ = createVolatileImage(aw, ah);
            vramFull_ = true;
            if (vram_ == null) return image_;
            if (vram_.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) return image_;
        } else if (state == VolatileImage.IMAGE_RESTORED) {
            vramFull_ = true;
        }
        Rectangle frame = new Rectangle(0, 0, frameW_, frameH_);
        Rectangle up = vramFull_ ? frame : vramDirty_.intersection(frame);
        if (!up.isEmpty()) {
            long m0 = PAINT_PROFILE ? System.nanoTime() : 0;
            Graphics2D vg = vram_.createGraphics();
            long m1 = PAINT_PROFILE ? System.nanoTime() : 0;
            try {
                vg.setComposite(AlphaComposite.Src);
                // Metal's sw→VRAM blit re-uploads the ENTIRE source image
                // for an unmanaged source — a clip does NOT bound it
                // (measured: a 113x36 clipped draw cost the same ~9ms as
                // full-frame at 6.7K device px). A subimage VIEW (shared
                // raster, no pixel copy) bounds the source itself, so the
                // upload is dirty- (or frame-)sized, never allocation-sized.
                vg.drawImage(image_.getSubimage(up.x, up.y, up.width, up.height),
                        up.x, up.y, null);
            } finally {
                vg.dispose();
            }
            if (PAINT_PROFILE) {
                long m2 = System.nanoTime();
                if ((m2 - m0) / 1_000_000 >= 3) {
                    System.out.println("[PAINT] vram-up rect=" + up.width + "x" + up.height
                            + " mkG=" + (m1 - m0) / 1_000_000 + "ms"
                            + " draw+disp=" + (m2 - m1) / 1_000_000 + "ms"
                            + " full=" + vramFull_);
                }
            }
        }
        vramDirty_.setBounds(0, 0, 0, 0);
        vramFull_ = false;
        return vram_;
    }

    @Override
    protected void paintComponent(Graphics g) {
        long t0 = PAINT_PROFILE ? System.nanoTime() : 0;
        long tLocked = 0;
        super.paintComponent(g); // fills the (black) background
        Rectangle clip = g.getClipBounds();
        synchronized (lock) {
            if (PAINT_PROFILE) tLocked = System.nanoTime();
            if (image_ != null) {
                // Sync the GPU mirror: upload only the dirty device-px
                // region of image_ into vram_ (a sub-rect sw→VRAM upload),
                // then blit vram_ to the screen — VRAM→VRAM, cheap at any
                // window size. Drawing image_ directly would re-upload the
                // whole (unmanaged) buffer every paint. Falls back to the
                // direct draw when a volatile surface isn't available.
                java.awt.Image toDraw = syncVram();

                // image_ is a device-pixel buffer; its intended on-screen
                // size is bufferPx / deviceScale. Never draw it LARGER than
                // that — on a big jump (maximize) stretching the stale buffer
                // to the new bounds balloons the text for a frame until CEF
                // repaints. Cap to the native logical size (the freshly-sized
                // frame arrives in a tick and fills the rest of the bg).
                // Only the clip region is drawn, mapped to its source rect
                // with per-coordinate rounding so adjacent clips share
                // identical source edges (no seams).
                long tSync = PAINT_PROFILE ? System.nanoTime() : 0;
                double s = ctx.renderScale() <= 0 ? 1.0 : ctx.renderScale();
                int logW = (int) Math.round(frameW_ / s);
                int logH = (int) Math.round(frameH_ / s);
                int maxW = Math.min(getWidth(), logW);
                int maxH = Math.min(getHeight(), logH);
                int dx1 = 0, dy1 = 0, dx2 = maxW, dy2 = maxH;
                if (clip != null) {
                    dx1 = Math.max(0, clip.x);
                    dy1 = Math.max(0, clip.y);
                    dx2 = Math.min(maxW, clip.x + clip.width);
                    dy2 = Math.min(maxH, clip.y + clip.height);
                }
                if (dx1 < dx2 && dy1 < dy2) {
                    g.drawImage(toDraw, dx1, dy1, dx2, dy2,
                            (int) Math.round(dx1 * s), (int) Math.round(dy1 * s),
                            (int) Math.round(dx2 * s), (int) Math.round(dy2 * s), null);
                }
                if (toDraw == vram_ && vram_.contentsLost()) {
                    vramFull_ = true; // surface evicted mid-paint → redo fully
                    repaint();
                }
                if (PAINT_PROFILE) {
                    long now = System.nanoTime();
                    long total = (now - t0) / 1_000_000;
                    if (total >= 4) {
                        System.out.println("[PAINT] edt-split sync="
                                + (tSync - tLocked) / 1_000_000 + "ms blit="
                                + (now - tSync) / 1_000_000 + "ms vram=" + (toDraw == vram_));
                    }
                }
            }
            if (popupVisible_ && popupImage_ != null) {
                g.drawImage(popupImage_, popupRect_.x, popupRect_.y,
                        popupRect_.width, popupRect_.height, null);
            }
        }
        if (PAINT_PROFILE && clip != null) {
            long now = System.nanoTime();
            long ms = (now - t0) / 1_000_000;
            if (ms >= 4) {
                System.out.println("[PAINT] edt clip=" + clip.width + "x" + clip.height
                        + " took=" + ms + "ms"
                        + " lockWait=" + (tLocked - t0) / 1_000_000 + "ms"
                        + " draw=" + (now - tLocked) / 1_000_000 + "ms");
            }
        }
    }

    BufferedImage snapshot(boolean nativeResolution, double scale) {
        synchronized (lock) {
            if (image_ == null || frameW_ <= 0 || frameH_ <= 0) return null;
            // The live frame is the top-left frameW_ x frameH_ of the
            // (possibly larger, step-padded) allocation.
            BufferedImage frame = image_.getSubimage(0, 0, frameW_, frameH_);
            if (nativeResolution || scale == 1.0) {
                BufferedImage copy = new BufferedImage(
                        frameW_, frameH_, BufferedImage.TYPE_INT_ARGB);
                copy.getGraphics().drawImage(frame, 0, 0, null);
                return copy;
            }
            int w = (int) (frameW_ / scale);
            int h = (int) (frameH_ / scale);
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            scaled.getGraphics().drawImage(frame, 0, 0, w, h, null);
            return scaled;
        }
    }
}
