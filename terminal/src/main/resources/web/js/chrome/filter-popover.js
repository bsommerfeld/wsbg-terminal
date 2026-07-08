// Headline filter popover — the funnel button in the Schlagzeilen header and
// the little panel it opens. Pure view layer: it renders the facet controls
// from the spec (headline-filter.js) and routes every click back into that
// module's setters. The wire re-renders via the module's own change
// notification (reddit.js subscribes), so this file never touches the list.
//
// Facet UX:
//   - Highlight  one toggle chip ("Nur Rot"): on ⇄ off.
//   - Price/News mutually-exclusive PAIR — when one value is active the sibling
//     is truly disabled (not just styled), so "mit" and "ohne" can never both be
//     picked; clicking the active one clears the facet. Exactly the contradiction
//     rule the user asked for.
//   - Sentiment/Asset  multi-select chips — any subset, OR within the facet.

import { t } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import {
  getSpec, onFilterChange, isActive,
  setHighlight, togglePair, resetFilter,
} from '../widgets/headline-filter.js';

let btn = null;
let pop = null;

export function initHeadlineFilter() {
  btn = document.getElementById('headline-filter-btn');
  pop = document.getElementById('headline-filter-popover');
  if (!btn || !pop) return;

  btn.addEventListener('click', e => { e.stopPropagation(); toggle(); });

  // Click on a facet control (event delegation over the whole panel). Stop the
  // event here so it never reaches the document-level outside-click handler —
  // otherwise the panel rebuild (renderPanel) detaches the clicked node before
  // that handler runs, it reads the click as "outside", and the popover closes
  // on every selection. The panel stays open until an explicit outside click.
  pop.addEventListener('click', e => {
    e.stopPropagation();
    const el = e.target.closest('[data-facet], .filter-reset');
    if (!el || el.disabled) return;
    if (el.classList.contains('filter-reset')) { resetFilter(); return; }
    apply(el.dataset.facet, el.dataset.val);
  });

  // Dismiss: outside click / Escape.
  document.addEventListener('click', e => {
    if (!isOpen()) return;
    if (e.target === btn || pop.contains(e.target) || btn.contains(e.target)) return;
    close();
  });
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && isOpen()) close(); });

  // Keep the button badge + (if open) the panel in sync with every spec change,
  // and re-label on a live language switch.
  onFilterChange(() => { updateButton(); if (isOpen()) renderPanel(); });
  window.addEventListener('wsbg:languagechange', () => { if (isOpen()) renderPanel(); });

  updateButton();
}

function apply(facet, val) {
  if (facet === 'highlight') setHighlight(val === getSpec().highlight ? 'ALL' : val);
  else togglePair(facet, val); // price | news
}

function isOpen() { return !pop.hasAttribute('hidden'); }

function toggle() { isOpen() ? close() : open(); }

function open() {
  renderPanel();
  pop.removeAttribute('hidden');
  btn.setAttribute('aria-expanded', 'true');
}

function close() {
  pop.setAttribute('hidden', '');
  btn.setAttribute('aria-expanded', 'false');
}

// Light each icon line whose facet is active — the icon IS the status readout.
function updateButton() {
  const s = getSpec();
  const on = {
    highlight: s.highlight !== 'ALL',
    price: s.price !== null,
    news: s.news !== null,
  };
  btn.querySelectorAll('.f-line').forEach(line => {
    line.classList.toggle('active', !!on[line.dataset.facet]);
  });
}

function renderPanel() {
  const s = getSpec();
  pop.innerHTML = `
    <div class="filter-head">
      <span class="filter-title">${escapeHtml(t('filter.title'))}</span>
      <button type="button" class="filter-reset"${isActive() ? '' : ' disabled'}>${escapeHtml(t('filter.reset'))}</button>
    </div>
    ${toggleRow(t('filter.highlight.label'), [
      chip('highlight', 'IMPORTANT', t('filter.highlight.red'), s.highlight === 'IMPORTANT', false, 'red'),
    ])}
    ${toggleRow(t('filter.price.label'), pairChips('price', s.price))}
    ${toggleRow(t('filter.news.label'), pairChips('news', s.news))}
  `;
}

function toggleRow(label, chips) {
  return `<div class="filter-row">
    <span class="filter-label">${escapeHtml(label)}</span>
    <div class="filter-opts">${chips.join('')}</div>
  </div>`;
}

// A mutually-exclusive pair: the sibling of an active value is DISABLED, so the
// two can never both be on.
function pairChips(facet, current) {
  return [
    chip(facet, 'with', t('filter.opt.with'), current === 'with', current === 'without'),
    chip(facet, 'without', t('filter.opt.without'), current === 'without', current === 'with'),
  ];
}

function chip(facet, val, label, active, disabled, tone) {
  const cls = ['chip'];
  if (active) cls.push('active');
  if (tone) cls.push('chip-' + tone);
  return `<button type="button" class="${cls.join(' ')}" data-facet="${facet}" data-val="${val}"
    aria-pressed="${active}"${disabled ? ' disabled' : ''}>${escapeHtml(label)}</button>`;
}
