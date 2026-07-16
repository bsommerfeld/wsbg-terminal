// Fixed-grid overview layout for the grid view.
//
// Every card sits anchored in a deterministic grid: uniform card size, fixed
// slot order, rows centred in the container. The whole arrangement is a pure
// function of the container size — recomputed on entry and on every window
// resize — so cards can never overlap and never drift, at any window size.
// Nothing is draggable and nothing persists (the former free-form
// Mission-Control layout with drag & drop lived here; it degraded into
// overlaps as the widget count grew).
//
// widget-nav.js owns the views and calls applyGridLayout()/clearGridLayout()
// around its FLIP measurements; this module owns geometry + activation.
// Wheel over a card still scrolls the widget's own body (delta divided by
// the miniature zoom); click / Enter on the .grid-hit activates the card.

const EDGE = 28;                // min padding to the container edges
const COL_GAP = 36;             // horizontal gap between cards
const ROW_GAP = 30;             // vertical air between rows (beyond the label zone)
const LABEL_H = 46;             // room below every card for its icon-on-edge + name pill
const COLS = 4;                 // slots per row; the last row centres its remainder
const ASPECT = 1.12;            // card width / height — near-square, slightly landscape
const MIN_W = 96;               // degenerate-sliver floor for absurdly small windows
const TAG_REF_W = 430;          // card width at which the icon + name pill render at design size
const TAG_MIN_SCALE = 0.68;     // readability floor: the pill never shrinks below this

// Fixed slot order: the two live panes lead, the AI tools complete the top
// row, the compact market/report tiles fill the second row.
// 'widget-watchlist' and 'widget-weather' are held back for a later release
// (their sections are hidden in index.html and their backend loops are
// disabled) — re-add them here to restore the cards.
const ORDER = [
  'widget-reddit', 'widget-fj', 'widget-deepdive',
  'widget-fg', 'widget-eurusd',
];

let main = null;
let onActivate = null;

function cards() {
  const byId = new Map(
    [...main.querySelectorAll(':scope > .widget')].map(w => [w.id, w]));
  return ORDER.map(id => byId.get(id)).filter(Boolean);
}

function inGrid() {
  return main && main.dataset.view === 'grid';
}

/**
 * A widget's natural pane width: half the SCREEN, not half the current
 * window. The screen resolution is the fixed reference viewport every
 * miniature lays out against, so the painted zoom (card width / natural
 * width) tracks the window size — shrink the window and text/images shrink
 * WITH the card. Math.max guards a bogus/unavailable screen value under OSR:
 * the reference is never smaller than the window.
 */
function naturalPaneW(W) {
  return 0.5 * Math.max(W, (window.screen && window.screen.width) || 0);
}

/**
 * Lays the cards out for the current container size: uniform card size fitted
 * to the grid (largest box that fits every column and row at the fixed
 * aspect), rows centred, label zone reserved below every card.
 */
export function applyGridLayout() {
  if (!main) return;
  const W = main.clientWidth;
  const H = main.clientHeight;
  if (!W || !H) return;
  const list = cards();
  if (!list.length) return;

  const rows = [];
  for (let i = 0; i < list.length; i += COLS) rows.push(list.slice(i, i + COLS));

  const cellW = (W - 2 * EDGE - (COLS - 1) * COL_GAP) / COLS;
  const cellH = (H - 2 * EDGE - rows.length * LABEL_H - (rows.length - 1) * ROW_GAP)
              / rows.length;
  let w = Math.min(cellW, cellH * ASPECT);
  w = Math.max(w, MIN_W);
  const h = w / ASPECT;

  // One shared miniature zoom for all cards: every card's layout viewport is
  // its natural screen-pane size, painted at whatever the card measures —
  // widget-grid.css consumes the var. Stays set after leaving the grid so
  // exiting cards keep their scale mid-flight. The icon + name pill live
  // OUTSIDE the zoomed body at fixed design sizes; they shrink with the
  // cards but SUBLINEARLY (sqrt) and floored — on a small window the labels
  // stay readable instead of vanishing proportionally (capped at 1 — they
  // never grow past design).
  main.style.setProperty('--grid-zoom', (w / naturalPaneW(W)).toFixed(4));
  const tagScale = Math.max(TAG_MIN_SCALE, Math.min(1, Math.sqrt(w / TAG_REF_W)));
  main.style.setProperty('--grid-tag-scale', tagScale.toFixed(4));

  const blockH = rows.length * (h + LABEL_H) + (rows.length - 1) * ROW_GAP;
  const y0 = Math.max(EDGE, (H - blockH) / 2);
  rows.forEach((row, r) => {
    const rowW = row.length * w + (row.length - 1) * COL_GAP;
    const x0 = Math.max(EDGE, (W - rowW) / 2);
    const top = y0 + r * (h + LABEL_H + ROW_GAP);
    row.forEach((el, c) => {
      el.style.left = `${Math.round(x0 + c * (w + COL_GAP))}px`;
      el.style.top = `${Math.round(top)}px`;
      el.style.width = `${Math.round(w)}px`;
      el.style.height = `${Math.round(h)}px`;
    });
  });
}

/** Removes the grid inline geometry when leaving the view. */
export function clearGridLayout() {
  if (!main) return;
  for (const c of cards()) {
    c.style.left = '';
    c.style.top = '';
    c.style.width = '';
    c.style.height = '';
  }
}

export function initGridLayout(mainEl, opts = {}) {
  main = mainEl;
  onActivate = opts.onActivate || (() => {});

  window.addEventListener('resize', () => { if (inGrid()) applyGridLayout(); });

  for (const card of cards()) {
    const hit = card.querySelector('.grid-hit');
    if (!hit) continue;

    // Wheel over a card scrolls INSIDE the widget: the hit overlay owns the
    // pointer (the miniature content is inert), so it forwards the wheel to
    // the card's .widget-body — the one scroller every widget has. Delta is
    // divided by the miniature zoom so the PAINTED scroll speed matches the
    // wheel 1:1 (scrollTop lives in the body's own, un-zoomed units). Cards
    // with a dedicated .grid-thumb have their body hidden — nothing scrolls,
    // which is the honest behaviour for a static tile.
    hit.addEventListener('wheel', e => {
      if (!inGrid()) return;
      const body = card.querySelector('.widget-body');
      if (!body) return;
      e.preventDefault();
      const zoom = parseFloat(main.style.getPropertyValue('--grid-zoom')) || 1;
      body.scrollTop += e.deltaY / zoom;
    }, { passive: false });

    hit.addEventListener('click', () => {
      if (inGrid()) onActivate(card);
    });
  }
}
