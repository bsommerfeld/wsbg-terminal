// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;

/**
 * Shields JCEF's {@link CefDropTargetListener} from platform drag quirks. On
 * macOS the drag-enter message can arrive before the transferable's data is
 * available — {@code getTransferData(javaFileListFlavor)} then answers
 * {@code null} and the JCEF listener NPEs on the EDT for any non-file drag
 * (text, image) crossing the window. A failed enter marks the whole drag
 * gesture inactive: the delegate never saw the enter, so it must not see the
 * follow-up over/drop events either (its internal drag data was never created).
 */
final class OsrDropTargetGuard implements DropTargetListener {

    private static final Logger log = LoggerFactory.getLogger(OsrDropTargetGuard.class);

    private final DropTargetListener delegate;
    private boolean active;

    OsrDropTargetGuard(DropTargetListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void dragEnter(DropTargetDragEvent event) {
        try {
            delegate.dragEnter(event);
            active = true;
        } catch (RuntimeException e) {
            active = false;
            log.debug("Rejected drag with unreadable transfer data: {}", e.toString());
            event.rejectDrag();
        }
    }

    @Override
    public void dragOver(DropTargetDragEvent event) {
        if (!active) {
            event.rejectDrag();
            return;
        }
        delegate.dragOver(event);
    }

    @Override
    public void dropActionChanged(DropTargetDragEvent event) {
        if (!active) {
            event.rejectDrag();
            return;
        }
        delegate.dropActionChanged(event);
    }

    @Override
    public void dragExit(DropTargetEvent event) {
        if (!active) return;
        active = false;
        delegate.dragExit(event);
    }

    @Override
    public void drop(DropTargetDropEvent event) {
        if (!active) {
            event.rejectDrop();
            return;
        }
        active = false;
        delegate.drop(event);
    }
}
