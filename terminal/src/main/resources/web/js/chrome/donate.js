// Donate surface behaviour.
//
// The heart icon itself is a plain anchor (always visible — the persistent
// "door") and opens the donate page on its own. This module adds the dynamic
// behaviours around it:
//   1. Hint pulse — while the gated footer banner is on-screen, the heart
//      pulses (.hint) to link the ambient banner to the durable donate door.
//   2. Engagement → snooze — clicking any donate target (the heart or the
//      banner link) counts as engagement and snoozes the active nudge layer
//      for a long cooldown (persisted server-side), so we stop knocking once
//      the user has answered. The door stays; only the banner/pulse go quiet.
//   3. Supporter gilding — a click that actually opened the donate page turns
//      the heart gold, permanently (honor system: Ko-fi is external and there
//      are no accounts, so the click-through is the only signal there is).
//      Gilded locally for instant feedback; the server persists the flag and
//      re-pushes it in every donation-gate payload.

import { setDonationAdEnabled } from './slider.js';

let socket = null;

export function initDonate(sock) {
  socket = sock;

  // Any donate click → snooze the nudge layer; a click on a /donate target
  // additionally gilds the heart. The anchor's own navigation is unaffected.
  document.addEventListener('click', e => {
    const target = e.target.closest('[data-donate]');
    if (!target) return;
    const donated = (target.href || '').includes('/donate');
    if (donated) setSupporter(true);
    snooze(donated);
  });

  // Footer banner visibility → toggle the heart hint pulse.
  window.addEventListener('wsbg:ad-visibility', e => {
    const on = !!(e.detail && e.detail.visible);
    document.querySelectorAll('.heart-btn').forEach(h => h.classList.toggle('hint', on));
  });
}

// Supporter state ("Ehrenaffe"): gold heart, driven by the donation-gate
// payload on every push and set optimistically on click above.
export function setSupporter(on) {
  document.querySelectorAll('.heart-btn').forEach(h => h.classList.toggle('supporter', !!on));
}

function snooze(donated) {
  // Stop the banner locally for instant feedback; the server persists the
  // cooldown (and the supporter flag) and re-pushes the gate state to confirm.
  setDonationAdEnabled(false);
  document.querySelectorAll('.heart-btn').forEach(h => h.classList.remove('hint'));
  if (socket) socket.send('donation', { action: 'snooze', donated: !!donated });
}
