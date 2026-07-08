// KI-notice overlay — the EU AI Act transparency disclosure behind the
// "KI-generiert" header badge.
//
// The badge is always visible on the headline feed's header (the one-time,
// clear-and-distinguishable disclosure Art. 50(4) UAbs. 2 asks for); clicking
// it opens a plain-language overlay explaining the headlines are AI-composed
// without human editorial review and can err on wording, facts and prices.
// Content is static + data-i18n-localized in index.html; this module only wires
// the open/close (shared skeleton in overlay.css / chrome/overlay.js).

import { attachOverlay } from './overlay.js';

export function initAiNotice() {
  const overlay = document.getElementById('ai-notice-overlay');
  if (!overlay) return;
  const ctl = attachOverlay(overlay);
  document.querySelectorAll('.js-ai-notice').forEach(btn =>
    btn.addEventListener('click', () => ctl.open()));
}
