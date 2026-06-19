// Donate surface behaviour.
//
// The heart icon itself is a plain anchor (always visible — the persistent
// "door") and opens the donate page on its own. This module adds the dynamic
// behaviours around it:
//   1. Hint pulse — while the gated footer banner is on-screen, the heart
//      pulses (.hint) to link the ambient banner to the durable donate door.
//   2. Engagement → snooze — clicking a BANNER link counts as "nudge answered"
//      and snoozes the active nudge layer for a cooldown (persisted
//      server-side). The heart is deliberately exempt: it is the door the
//      user opens on their own — punishing that with silence buried the
//      banner for testers and donors alike. The door stays; only the
//      banner/pulse go quiet.
//   3. Supporter gilding — a click that actually opened the donate page turns
//      the heart gold, permanently (honor system: Ko-fi is external and there
//      are no accounts, so the click-through is the only signal there is).
//      Gilded locally for instant feedback; the server persists the flag and
//      re-pushes it in every donation-gate payload.

import { setDonationAdEnabled } from './slider.js';

let socket = null;

export function initDonate(sock) {
  socket = sock;

  document.addEventListener('click', e => {
    const target = e.target.closest('[data-donate]');
    if (!target) return;
    if (target.closest('.heart-btn')) {
      // Heart: voluntary interest — gild, never snooze.
      setSupporter(true);
      if (socket) socket.send('donation', { action: 'gild' });
      return;
    }
    // Banner link: the nudge was answered — gild if it was the donate page,
    // and rest the banner either way.
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
