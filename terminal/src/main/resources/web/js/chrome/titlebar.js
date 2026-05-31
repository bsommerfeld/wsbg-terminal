// Wires the titlebar buttons + window drag.
//
// macOS-style traffic lights = window controls. Drag works by asking
// the host to move the frame via the socket bridge on Win/Linux; on
// macOS the OS handles drag natively for free.
//
// Settings flyout is wired separately in chrome/settings.js.

export function initTitlebar(socket) {
  // Traffic-light buttons (close / minimize / maximize-toggle).
  document.querySelectorAll('.light').forEach(b => {
    b.addEventListener('click', e => {
      e.stopPropagation();
      socket.send('window', { command: b.dataset.window });
    });
  });

  // Window drag.
  //
  // On macOS the JFrame stays decorated (JCEF reparenting requires a
  // standard NSWindow) so the OS handles drag natively — anything in
  // the transparent title bar area moves the window for free. Skipping
  // our JS handler avoids fighting the native drag and keeps it at
  // zero latency.
  //
  // On Windows/Linux the frame is undecorated, so we forward drag
  // gestures to the host via the socket.
  if (document.documentElement.dataset.platform !== 'mac') {
    const bar = document.getElementById('titlebar');
    bar.addEventListener('mousedown', e => {
      if (e.target.closest('button, a, .iconbtn, .lights')) return;
      socket.send('window', { command: 'drag-start' });
    });
    window.addEventListener('mouseup', () => {
      socket.send('window', { command: 'drag-stop' });
    });
  }
}
