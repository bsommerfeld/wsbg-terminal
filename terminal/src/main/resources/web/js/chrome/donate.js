// Donate surface behaviour.
//
// The heart icon itself is a plain anchor (always visible — the persistent
// "door") and opens the donate page on its own. This module only adds the two
// dynamic behaviours around it:
//   1. Hint pulse — while the gated footer banner is on-screen, the heart
//      pulses (.hint) to link the ambient banner to the durable donate door.
//   2. Engagement → snooze — clicking any donate target (the heart or the
//      banner link) counts as engagement and snoozes the active nudge layer
//      for a long cooldown (persisted server-side), so we stop knocking once
//      the user has answered. The door stays; only the banner/pulse go quiet.

import { setDonationAdEnabled } from './slider.js';

let socket = null;

export function initDonate(sock) {
  socket = sock;

  // Any donate click (heart or banner link) → snooze the nudge layer. The
  // anchor's own navigation to the donate page is unaffected.
  document.addEventListener('click', e => {
    if (e.target.closest('[data-donate]')) snooze();
  });

  // Footer banner visibility → toggle the heart hint pulse.
  window.addEventListener('wsbg:ad-visibility', e => {
    const on = !!(e.detail && e.detail.visible);
    document.querySelectorAll('.heart-btn').forEach(h => h.classList.toggle('hint', on));
  });
}

function snooze() {
  // Stop the banner locally for instant feedback; the server persists the
  // cooldown and re-pushes the (now suppressed) gate state to confirm.
  setDonationAdEnabled(false);
  document.querySelectorAll('.heart-btn').forEach(h => h.classList.remove('hint'));
  if (socket) socket.send('donation', { action: 'snooze' });
}
