// Footer renderer: stadium-banner market chips + slide cycle.
//
// Chips are built once from the calendar payload (or pre-seeded with
// "—" until the first push arrives). A per-second tick recomputes
// the visible state purely client-side, so the WebSocket sees no
// per-second traffic at all.

import { regionStatus, onCalendarUpdate, regions } from '../markets/state.js';
import { fmtDuration } from '../format/time.js';
import { initSlideCycle } from './slider.js';
import { t } from '../i18n/i18n.js';

const DEFAULT_SYMBOLS = ['DE', 'US', 'ASIEN', 'AUSTRALIEN'];

// Localised chip label for a region code (DE/US stay as-is; ASIEN/AUSTRALIEN
// translate). The raw code stays in data-sym as the stable identity.
function regionLabel(sym) {
  return t('region.' + sym, sym);
}

export function initFooter() {
  const markets = document.getElementById('markets');
  ensureChips(markets, DEFAULT_SYMBOLS);

  // Calendar push from the server: only rebuild the chip set if the
  // symbol list itself changed. Otherwise the existing chips stay in
  // place and updateChips() refreshes their state on the next tick —
  // no "—" flash from a momentary re-render.
  onCalendarUpdate(() => {
    ensureChips(markets, regions().length ? regions() : DEFAULT_SYMBOLS);
    updateChips(markets);   // immediate refresh with the new calendar
  });

  // 1s tick is plenty — the countdown ends in seconds; sub-second
  // smoothness adds nothing.
  setInterval(() => updateChips(markets), 1000);
  updateChips(markets);

  // Live language switch: re-label the chips (their .sym text) in place. The
  // "FREI"/countdown text follows on the next 1s tick via updateChips.
  window.addEventListener('wsbg:languagechange', () => {
    markets.querySelectorAll('.cell.exch').forEach(el => {
      const label = el.querySelector('.sym');
      if (label) label.textContent = regionLabel(el.dataset.sym);
    });
    updateChips(markets);
  });

  initSlideCycle();
}

/**
 * Renders chips only on first call or when the symbol list changes.
 * On every other invocation it's a no-op, which means the existing
 * DOM (and its currently-displayed countdown text) survives.
 */
function ensureChips(host, symbols) {
  const current = Array.from(host.querySelectorAll('.cell.exch')).map(el => el.dataset.sym);
  if (current.length === symbols.length && current.every((s, i) => s === symbols[i])) {
    return;
  }
  host.innerHTML = symbols.map(sym => `
    <div class="cell exch closed" data-sym="${sym}">
      <span class="dot"></span>
      <span class="sym">${regionLabel(sym)}</span>
      <span class="cd"></span>
    </div>`).join('');
}

function updateChips(host) {
  host.querySelectorAll('.cell.exch').forEach(el => {
    const sym = el.dataset.sym;
    const { state, remainMs, longClosure } = regionStatus(sym);
    const prev = el.dataset.state;
    // On a phase transition, retrigger the flicker animation by
    // removing the class, forcing a reflow, then re-adding it.
    // Skip on first paint (prev undefined) so the page doesn't
    // open with every chip flashing.
    if (prev && prev !== state) {
      el.classList.remove('phase-shift');
      void el.offsetWidth;
      el.classList.add('phase-shift');
    }
    el.dataset.state = state;
    el.classList.remove('pre', 'main', 'post', 'closed');
    el.classList.add(state);
    // Closed for a whole non-trading day (weekend/holiday): show the
    // static label, not a countdown. Otherwise the live countdown —
    // phase end while trading, next open during a normal overnight gap.
    // Keep the previous text on the rare empty tick (state has just
    // transitioned, next session not yet resolved) — better to show
    // a one-second-stale value than to flash a placeholder.
    const cd = el.querySelector('.cd');
    if (state === 'closed' && longClosure) cd.textContent = t('footer.closed');
    else if (remainMs > 0) cd.textContent = fmtDuration(remainMs);
    else if (!cd.textContent) cd.textContent = '—';
  });
}
