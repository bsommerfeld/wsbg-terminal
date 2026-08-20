// AI-endpoint health — the red titlebar alarm and the overlay behind it.
//
// Why this exists at all: with the managed local runtime a dead model server is
// a state the app REPAIRS (it starts one), so it never needed a UI. Point the
// terminal at a machine in the user's own network and it becomes a state only
// THEY can repair — the box is asleep, the address has a typo, the model was
// never pulled. The terminal does not crash on any of those; it just stops
// producing headlines, which without this indicator looks like nothing at all.
//
// Payload (topic 'ai-status', pushed on every transition + on client open):
// { state: 'OK' | 'UNREACHABLE' | 'REJECTED', endpoint, managed, reason }.

import { attachOverlay } from './overlay.js';
import { t } from '../i18n/i18n.js';

let ctl = null;
let last = null;

export function initAiStatus() {
  const overlay = document.getElementById('ai-status-overlay');
  if (!overlay) return;
  ctl = attachOverlay(overlay);
  document.querySelectorAll('.js-ai-status').forEach(btn =>
    btn.addEventListener('click', () => ctl.open()));
}

/**
 * Renders a health payload: the button appears/disappears, and the overlay
 * content is prepared so a click shows the current state rather than whatever
 * was last painted. Re-callable with the cached payload on a language switch.
 */
export function applyAiStatus(payload) {
  if (payload) last = payload;
  const p = last;
  const btn = document.querySelector('.js-ai-status');
  if (!btn || !p) return;

  const broken = p.state && p.state !== 'OK';
  btn.hidden = !broken;
  // A healthy endpoint closes an overlay left open from the outage: the panel
  // would otherwise keep explaining a problem that has just fixed itself.
  if (!broken) {
    if (ctl) ctl.close();
    return;
  }

  const kind = p.state === 'REJECTED' ? 'rejected' : 'unreachable';
  const who = p.managed ? 'managed' : 'remote';

  setText('.js-ai-status-title', t(`ai.status.title.${kind}`));
  setText('.js-ai-status-text', t(`ai.status.body.${who}.${kind}`));
  // Verbatim, both of them: the address because a typo IS the failure often
  // enough that showing our idea of it is the fix, and the reason because it is
  // the server's own wording — translating it would put our words on someone
  // else's error.
  setText('.js-ai-status-endpoint', p.endpoint || '-');
  setText('.js-ai-status-reason', p.reason || '-');
  setText('.js-ai-status-hint', t(`ai.status.hint.${who}`));
  btn.title = t(`ai.status.title.${kind}`);
}

function setText(selector, value) {
  const el = document.querySelector(selector);
  if (el) el.textContent = value;
}
