// Edge/corner window resize for the undecorated Win/Linux frame.
//
// macOS keeps a decorated NSWindow, so the OS draws a native resize
// border and this module is a no-op there. On Windows/Linux the frame is
// undecorated (setUndecorated(true) strips the native border), so the
// page paints eight invisible hit-zones along the window edges and
// forwards the gesture to the host over the socket — the same pattern
// titlebar.js uses for window drag. The Java side (WindowDragHandler)
// runs the actual setBounds follow-loop.

// Thin strips on the four edges + slightly larger squares in the corners.
// Corners are listed last so they are appended last and therefore win the
// hit-test where they overlap the edge strips (equal z-index → DOM order).
// The 10px titlebar padding keeps the corner squares clear of the window
// buttons.
const ZONES = [
  { edge: 'n',  css: 'top:0;left:0;right:0;height:6px;cursor:ns-resize;' },
  { edge: 's',  css: 'bottom:0;left:0;right:0;height:6px;cursor:ns-resize;' },
  { edge: 'e',  css: 'top:0;right:0;bottom:0;width:6px;cursor:ew-resize;' },
  { edge: 'w',  css: 'top:0;left:0;bottom:0;width:6px;cursor:ew-resize;' },
  { edge: 'nw', css: 'top:0;left:0;width:12px;height:12px;cursor:nwse-resize;' },
  { edge: 'ne', css: 'top:0;right:0;width:12px;height:12px;cursor:nesw-resize;' },
  { edge: 'sw', css: 'bottom:0;left:0;width:12px;height:12px;cursor:nesw-resize;' },
  { edge: 'se', css: 'bottom:0;right:0;width:12px;height:12px;cursor:nwse-resize;' },
];

export function initResize(socket) {
  // Native resize handles this on macOS.
  if (document.documentElement.dataset.platform === 'mac') return;

  for (const { edge, css } of ZONES) {
    const zone = document.createElement('div');
    zone.style.cssText = 'position:fixed;z-index:9999;' + css;
    zone.addEventListener('mousedown', e => {
      // Stop the titlebar drag handler from also firing on the top edge.
      e.preventDefault();
      e.stopPropagation();
      socket.send('window', { command: 'resize-start', edge });
    });
    document.body.appendChild(zone);
  }

  // A single global mouseup ends whichever gesture was active. Sending
  // resize-stop after a drag (or vice-versa) is a harmless no-op host-side.
  window.addEventListener('mouseup', () => {
    socket.send('window', { command: 'resize-stop' });
  });
}
