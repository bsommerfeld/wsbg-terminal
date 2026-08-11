// Headline filter popover — the funnel button in the Schlagzeilen header and
// the little panel it opens. Pure view layer: it renders the facet controls
// from the spec (headline-filter.js) and routes every click back into that
// module's setters. The wire re-renders via the module's own change
// notification (reddit.js subscribes), so this file never touches the list.
//
// The panel itself is shared: mountFilterPanel() renders + wires the facet
// controls into ANY container, so the header popover (dashboard) and the
// focus-mode rail popup (widget-rail.js) show the same filter — one spec,
// two anchors.
//
// Facet UX:
//   - Highlight  one toggle chip ("Nur Rot"): on ⇄ off.
//   - Price/News mutually-exclusive PAIR — when one value is active the sibling
//     is truly disabled (not just styled), so "mit" and "ohne" can never both be
//     picked; clicking the active one clears the facet. Exactly the contradiction
//     rule the user asked for.
//   - Preisspanne — a continuous band, so it owns its own control (price-range.js)
//     instead of a chip row: the panel only hands it a slot.

import { t } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import {
  getSpec, onFilterChange, isActive, activeFacets,
  setHighlight, togglePair, resetFilter,
} from '../widgets/headline-filter.js';
import { mountPriceRange } from './price-range.js';

let btn = null;
let pop = null;

/**
 * Renders the facet panel into `container` and keeps it live: clicks route to
 * the filter setters, and the markup re-renders on every spec change and on a
 * live language switch. Re-rendering a hidden panel is harmless (cheap
 * innerHTML), so no visibility bookkeeping is needed.
 */
export function mountFilterPanel(container) {
  const render = () => {
    container.innerHTML = panelHtml();
    // The band control renders itself into its slot; `container` is the stable
    // anchor it keys focus restoration off (the slot itself is replaced here).
    mountPriceRange(container.querySelector('.js-price-range'), container);
  };

  // Stop the click here so it never reaches document-level outside-click
  // handlers — the re-render detaches the clicked node before they run, which
  // would read as "outside" and close the hosting popover on every selection.
  container.addEventListener('click', e => {
    e.stopPropagation();
    const el = e.target.closest('[data-facet], .filter-reset');
    if (!el || el.disabled) return;
    if (el.classList.contains('filter-reset')) { resetFilter(); return; }
    apply(el.dataset.facet, el.dataset.val);
  });

  onFilterChange(render);
  window.addEventListener('wsbg:languagechange', render);
  render();
}

export function initHeadlineFilter() {
  btn = document.getElementById('headline-filter-btn');
  pop = document.getElementById('headline-filter-popover');
  if (!btn || !pop) return;

  mountFilterPanel(pop);

  btn.addEventListener('click', e => { e.stopPropagation(); toggle(); });

  // Dismiss: outside click / Escape.
  document.addEventListener('click', e => {
    if (!isOpen()) return;
    if (e.target === btn || pop.contains(e.target) || btn.contains(e.target)) return;
    close();
  });
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && isOpen()) close(); });
  // A view switch (grid/focus) re-homes the filter to the rail — don't leave
  // the header popover floating over the miniature card.
  window.addEventListener('wsbg:viewchange', () => { if (isOpen()) close(); });

  // Keep the button badge in sync with every spec change.
  onFilterChange(updateButton);
  updateButton();
}

function apply(facet, val) {
  if (facet === 'highlight') setHighlight(val === getSpec().highlight ? 'ALL' : val);
  else togglePair(facet, val); // price | news
}

function isOpen() { return !pop.hasAttribute('hidden'); }

function toggle() { isOpen() ? close() : open(); }

function open() {
  pop.removeAttribute('hidden');
  btn.setAttribute('aria-expanded', 'true');
}

function close() {
  pop.setAttribute('hidden', '');
  btn.setAttribute('aria-expanded', 'false');
}

// Light each icon line whose facet is active — the icon IS the status readout.
function updateButton() {
  const on = activeFacets();
  btn.querySelectorAll('.f-line').forEach(line => {
    line.classList.toggle('active', !!on[line.dataset.facet]);
  });
}

function panelHtml() {
  const s = getSpec();
  // Head + reset use the SHARED rail-popup grammar (.rail-pop-head /
  // .rail-pop-action, widget-grid.css) — the panel no longer brings its own
  // title styling, so both of its anchors look like every other rail panel.
  // .filter-reset stays purely as this file's click hook.
  return `
    <div class="rail-pop-head">
      <span>${escapeHtml(t('filter.title'))}</span>
      <button type="button" class="rail-pop-action filter-reset"${isActive() ? '' : ' disabled'}>${escapeHtml(t('filter.reset'))}</button>
    </div>
    ${toggleRow(t('filter.highlight.label'), seg([
      // "Wichtigkeit" is a binary too — ALL vs IMPORTANT — so it wears the same
      // switch as the other two instead of being a lone chip in a wide panel.
      chip('highlight', 'ALL', t('filter.opt.all'), s.highlight === 'ALL'),
      chip('highlight', 'IMPORTANT', t('filter.highlight.red'), s.highlight === 'IMPORTANT', false, 'red'),
    ]))}
    ${toggleRow(t('filter.price.label'), pairGroup('price', s.price))}
    <div class="filter-row js-price-range"></div>
    ${toggleRow(t('filter.news.label'), pairGroup('news', s.news))}
  `;
}

function toggleRow(label, optsHtml) {
  return `<div class="filter-row">
    <span class="filter-label">${escapeHtml(label)}</span>
    <div class="filter-opts">${optsHtml}</div>
  </div>`;
}

function seg(chips) {
  return `<div class="filter-seg" role="group">${chips.join('')}</div>`;
}

// A mutually-exclusive pair, rendered as ONE full-width segmented switch. The
// exclusivity is now STRUCTURAL — two halves of one control, and togglePair()
// swaps straight from one to the other — so the sibling is no longer disabled:
// inside a single switch a dead half reads as a broken button, and it forced
// two clicks (clear, then pick) for what is one decision. Clicking the active
// half still clears the facet back to "egal".
function pairGroup(facet, current) {
  return seg([
    chip(facet, 'with', t('filter.opt.with'), current === 'with'),
    chip(facet, 'without', t('filter.opt.without'), current === 'without'),
  ]);
}

function chip(facet, val, label, active, disabled, tone) {
  const cls = ['chip'];
  if (active) cls.push('active');
  if (tone) cls.push('chip-' + tone);
  return `<button type="button" class="${cls.join(' ')}" data-facet="${facet}" data-val="${val}"
    aria-pressed="${active}"${disabled ? ' disabled' : ''}>${escapeHtml(label)}</button>`;
}
