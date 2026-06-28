// Donate surface behaviour.
//
// The heart icon itself is a plain anchor (always visible — the persistent
// "door") and opens the donate page on its own (its external href is routed to
// the OS browser by external-links.js). This module only adds the ambient hint:
// while the footer banner is on-screen, the heart pulses (.hint) to link the
// rotating banner to the durable donate door. No click handling, no supporter
// state — clicking the heart just opens the donate page like any other link.

export function initDonate() {
  // Footer banner visibility → toggle the heart hint pulse.
  window.addEventListener('wsbg:ad-visibility', e => {
    const on = !!(e.detail && e.detail.visible);
    document.querySelectorAll('.heart-btn').forEach(h => h.classList.toggle('hint', on));
  });
}
