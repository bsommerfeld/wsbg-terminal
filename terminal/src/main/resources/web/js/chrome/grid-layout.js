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
// Two things make a resize land where the eye expects it, and they are easy to
// confuse: the ARRANGEMENT and the SCALE.
//   · The arrangement — how many cards share a row — is derived from the box
//     (chooseRaster), not pinned to a constant. A fixed 4-up put five widgets
//     into a 4+1 raster with a dead band underneath and crushed them into
//     slivers on a narrow window.
//   · The scale — how large the content inside a card paints — is the card
//     width over a CONSTANT design width (naturalPaneW). Deriving that
//     reference from the window is what made the miniatures ignore the resize;
//     the long note on that function is the post-mortem.
//
// widget-nav.js owns the views and calls applyGridLayout()/clearGridLayout()
// around its FLIP measurements; this module owns geometry + activation.
// Wheel over a card still scrolls the widget's own body (delta divided by
// the miniature zoom); click / Enter on the .grid-hit activates the card.

const EDGE = 28;                // min padding to the container edges
const COL_GAP = 36;             // horizontal gap between cards
const ROW_GAP = 30;             // vertical air between rows (beyond the label zone)
const LABEL_H = 46;             // room below every card for its icon-on-edge + name pill
const ASPECT = 1.12;            // card width / height — near-square, slightly landscape
const MIN_W = 96;               // degenerate-sliver floor for absurdly small windows
const TAG_REF_W = 430;          // card width at which the icon + name pill render at design size
const TAG_MIN_SCALE = 0.68;     // readability floor: the pill never shrinks below this

// The reference viewport a miniature lays out against: the width a widget's
// body is DESIGNED for (half of a 1920 pane). It is a CONSTANT on purpose —
// see naturalPaneW() for why deriving it from the window or the screen is the
// one thing that must not happen here.
const REF_PANE_W = 960;

// Cards never grow past their own design width: beyond it the miniature would
// be an UPSCALED widget (zoom > 1), i.e. blurry chrome and oversized type on
// a very large display — a card is a scaled-down view, never a magnified one.
const MAX_W = REF_PANE_W;

// Fixed slot order: the two live panes lead, then the AI tool, then the
// compact market tiles. The order never changes — only how many of them the
// current window puts on one shelf.
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
 * Icon + name pill scale for a card of width `w`. They live OUTSIDE the zoomed
 * body at fixed design sizes, so they shrink with the cards but SUBLINEARLY
 * (sqrt) and floored — on a small window the labels stay readable instead of
 * vanishing proportionally. Capped at 1: they never grow past design.
 */
function tagScaleFor(w) {
  return Math.max(TAG_MIN_SCALE, Math.min(1, Math.sqrt(w / TAG_REF_W)));
}

/**
 * The reference viewport every miniature lays out against — a CONSTANT.
 *
 * This used to be `0.5 * max(window, screen.width)`, and that is precisely
 * what broke the scaling: under the OSR browser `getScreenInfo()` reports the
 * BROWSER VIEW RECT as the screen, so `screen.width === window width` and the
 * reference collapsed to half the window. The zoom then read
 * `card / (0.5 * window)` — a ratio in which the window appears on BOTH sides,
 * so the painted content stayed roughly the same size while the card grew and
 * shrank around it. Measured on the F&G card: the gauge filled 80 % of the
 * card at a 1600px window, 100 % at 1280px, 128 % at 1000px and 149 % at
 * 860px — it ran over the rim on a small window and drowned in empty space on
 * a large one.
 *
 * With a constant reference the zoom is `card / design width` and nothing
 * else: every painted length inside a card scales EXACTLY with the card, at
 * every window size, by construction. Never make this a function of the
 * window or of `screen` again.
 */
function naturalPaneW() {
  return REF_PANE_W;
}

/** Largest card width that fits `cols × rows` cards into the container. */
function fitCardW(W, H, cols, rows, labelH) {
  const cellW = (W - 2 * EDGE - (cols - 1) * COL_GAP) / cols;
  const cellH = (H - 2 * EDGE - rows * labelH - (rows - 1) * ROW_GAP) / rows;
  return Math.min(cellW, cellH * ASPECT);
}

/**
 * The raster is no longer a fixed 4-up: the column count is DERIVED from the
 * container by asking every possible arrangement how big a card it can afford
 * and taking the most generous one. No breakpoints, no media queries — the
 * arrangement is a pure function of the box, which is what makes a resize land
 * where the eye expects it.
 *
 * The card order stays fixed (ORDER), so widgets never swap places; only how
 * many of them share a row changes. Among arrangements that are within a hair
 * of the best card size the FLATTER one wins — five cards on one shelf read as
 * one overview, five stacked rows read as a list.
 */
function chooseRaster(W, H, n, labelH) {
  const cands = [];
  for (let cols = 1; cols <= n; cols++) {
    const rows = Math.ceil(n / cols);
    cands.push({ cols, rows, w: fitCardW(W, H, cols, rows, labelH) });
  }
  const best = Math.max(...cands.map(c => c.w));
  const near = cands.filter(c => c.w >= best * 0.985);
  near.sort((a, b) => a.rows - b.rows || b.w - a.w);
  return near[0];
}

/**
 * Lays the cards out for the current container size: the raster that affords
 * the largest card, one uniform card size at the fixed aspect, rows centred,
 * label zone reserved below every card.
 */
export function applyGridLayout() {
  if (!main) return;
  const W = main.clientWidth;
  const H = main.clientHeight;
  if (!W || !H) return;
  const list = cards();
  if (!list.length) return;

  // Two passes, because the label zone and the card size depend on each other:
  // the name pills shrink with the cards (--grid-tag-scale), so a small window
  // needs less room below each card than the design LABEL_H. Pass one sizes the
  // cards against the full label zone, pass two hands the reclaimed pixels back
  // — on a short window that is the difference between a usable card and a
  // sliver. One correction is enough; iterating further only chases rounding.
  const first = chooseRaster(W, H, list.length, LABEL_H);
  const labelH = Math.round(LABEL_H * tagScaleFor(first.w));
  const raster = chooseRaster(W, H, list.length, labelH);

  const rows = [];
  for (let i = 0; i < list.length; i += raster.cols) {
    rows.push(list.slice(i, i + raster.cols));
  }

  const w = Math.min(MAX_W, Math.max(raster.w, MIN_W));
  const h = w / ASPECT;

  // One shared miniature zoom for all cards: every card's layout viewport is
  // the design pane width, painted at whatever the card measures —
  // widget-grid.css consumes the var. All three vars stay set after leaving
  // the grid so exiting cards keep their scale mid-flight.
  main.style.setProperty('--grid-zoom', (w / naturalPaneW()).toFixed(4));
  // The raw card width, unitless, for the two hero cards: they divide it by
  // their own design width (--hero-ref-w) instead of the pane width, because
  // what has to fill their tile is ONE compact object, not a whole pane.
  main.style.setProperty('--grid-card-w', String(Math.round(w)));
  main.style.setProperty('--grid-tag-scale', tagScaleFor(w).toFixed(4));

  const blockH = rows.length * (h + labelH) + (rows.length - 1) * ROW_GAP;
  const y0 = Math.max(EDGE, (H - blockH) / 2);
  rows.forEach((row, r) => {
    const rowW = row.length * w + (row.length - 1) * COL_GAP;
    const x0 = Math.max(EDGE, (W - rowW) / 2);
    const top = y0 + r * (h + labelH + ROW_GAP);
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
