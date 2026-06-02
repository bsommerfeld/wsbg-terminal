// Wires the title-bar window-control buttons (minimize / maximize / close).
//
// Everything else — dragging the window, edge-resize, Aero Snap and
// double-click-to-maximize — is handled NATIVELY by Windows. The native window
// proc (WindowsCustomChrome) reports the title-bar strip as HTCAPTION and the
// top edge as HTTOP via WM_NCHITTEST, and the single same-thread GLCanvas (OSR)
// forwards those hits through with HTTRANSPARENT. So there is no JS drag/resize
// emulation here anymore.
//
// macOS keeps its own native NSWindow caption (the HTML lights are hidden);
// Linux uses the native OS title bar (the whole HTML bar is hidden).

export function initTitlebar(socket) {
  document.querySelectorAll('.light').forEach(b => {
    b.addEventListener('click', e => {
      e.stopPropagation();
      socket.send('window', { command: b.dataset.window });
    });
  });
}
