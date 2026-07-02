// Shared modal-overlay behaviour — the open/close mechanics every in-app
// overlay (changelog, news sources, …) has in common: the close X, a click
// on the backdrop, Escape, and the clap-shut exit animation (CSS in
// overlay.css). Feature modules own their content rendering and call
// open()/close() around it.

export function attachOverlay(overlay, { onClose } = {}) {
  let closeTimer = null;

  function close() {
    if (overlay.hidden || overlay.classList.contains('closing')) return;
    if (onClose) onClose();
    // Clap-shut exit: .closing plays the CSS fade/shrink, then the overlay
    // actually hides. Timeout instead of transitionend — robust even if the
    // transition is skipped (e.g. reduced painting).
    overlay.classList.add('closing');
    closeTimer = setTimeout(() => {
      overlay.hidden = true;
      overlay.classList.remove('closing');
    }, 240);
  }

  function open() {
    // An open mid-close must not be swallowed by the pending hide.
    clearTimeout(closeTimer);
    overlay.classList.remove('closing');
    overlay.hidden = false;
  }

  const closeBtn = overlay.querySelector('.overlay-close');
  if (closeBtn) closeBtn.addEventListener('click', close);
  overlay.addEventListener('click', e => { if (e.target === overlay) close(); });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !overlay.hidden) close();
  });

  return { open, close };
}
