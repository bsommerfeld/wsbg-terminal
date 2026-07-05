// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.callback.CefDragData;

import java.awt.Component;
import java.awt.Point;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;

import javax.swing.JComponent;

/**
 * The OSR drag <em>source</em> — CEF-initiated drags (page → OS). Extracted from
 * {@link SwingCefBrowser}: pure DnD, no coupling to the render or resize paths.
 * The drag-end callbacks route back into the browser's inherited
 * {@code dragSourceEndedAt}/{@code dragSourceSystemDragEnded} (package-visible
 * here since this class lives in {@code org.cef.browser}).
 */
final class OsrDragSource {

    private OsrDragSource() {}

    static boolean startDragging(JComponent panel, SwingCefBrowser browser,
            CefDragData dragData, int mask, int x, int y) {
        int action = getDndAction(mask);
        MouseEvent triggerEvent =
                new MouseEvent(panel, MouseEvent.MOUSE_DRAGGED, 0, 0, x, y, 0, false);
        DragGestureEvent ev = new DragGestureEvent(
                new SyntheticDragGestureRecognizer(panel, action, triggerEvent), action,
                new Point(x, y), new ArrayList<>(Arrays.asList(triggerEvent)));
        DragSource.getDefaultDragSource().startDrag(ev, /*dragCursor=*/null,
                new StringSelection(dragData.getFragmentText()), new DragSourceAdapter() {
                    @Override
                    public void dragDropEnd(DragSourceDropEvent dsde) {
                        browser.dragSourceEndedAt(dsde.getLocation(), action);
                        browser.dragSourceSystemDragEnded();
                    }
                });
        return true;
    }

    private static int getDndAction(int mask) {
        if ((mask & CefDragData.DragOperations.DRAG_OPERATION_COPY)
                == CefDragData.DragOperations.DRAG_OPERATION_COPY) {
            return DnDConstants.ACTION_COPY;
        } else if ((mask & CefDragData.DragOperations.DRAG_OPERATION_MOVE)
                == CefDragData.DragOperations.DRAG_OPERATION_MOVE) {
            return DnDConstants.ACTION_MOVE;
        } else if ((mask & CefDragData.DragOperations.DRAG_OPERATION_LINK)
                == CefDragData.DragOperations.DRAG_OPERATION_LINK) {
            return DnDConstants.ACTION_LINK;
        }
        return DnDConstants.ACTION_NONE;
    }

    private static final class SyntheticDragGestureRecognizer extends DragGestureRecognizer {
        SyntheticDragGestureRecognizer(Component c, int action, MouseEvent triggerEvent) {
            super(new DragSource(), c, action);
            appendEvent(triggerEvent);
        }

        protected void registerListeners() {}

        protected void unregisterListeners() {}
    }
}
