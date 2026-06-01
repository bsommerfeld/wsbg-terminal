// Wires the titlebar window-control buttons.
//
// Window drag is handled natively on every platform now: macOS keeps a
// decorated NSWindow (the transparent title-bar region drags for free),
// and Windows/Linux use the native OS title bar (decorated frame). So
// there is no JS drag forwarding here anymore — it only ever existed to
// emulate drag on the old undecorated Win/Linux frame.
//
// The HTML traffic-light buttons are hidden on every platform (native
// controls take over), so the click handlers below are effectively
// dormant; they're kept harmlessly in case the HTML chrome is ever
// re-enabled for a platform.

export function initTitlebar(socket) {
  document.querySelectorAll('.light').forEach(b => {
    b.addEventListener('click', e => {
      e.stopPropagation();
      socket.send('window', { command: b.dataset.window });
    });
  });
}
