// Free-form Mission-Control layout for the grid view.
//
// Every card has a normalized centre position (fractions of the container),
// applied as inline px left/top/width while the grid is open. The default
// arrangement mirrors the macOS reference: one large window in the centre,
// smaller ones flanking it at slightly different heights — organic, not a
// strict row. Cards are drag-&-droppable; the arrangement persists in
// localStorage. A minimum gap between cards (and to the edges) is enforced
// EVERY time rects are computed — after a drop, on entry and on resize — so
// no window size can ever make two cards touch.
//
// widget-nav.js owns the views and calls applyGridLayout()/clearGridLayout()
// around its FLIP measurements; this module owns geometry + drag only. Click
// vs drag is decided here (a >6px move suppresses the activate click), so the
// card's .grid-hit keeps working for keyboard activation too.

const STORE_KEY = 'wsbg.gridLayout.v1';
const GAP = 28;                 // min px between cards and to the container edge
const LABEL_H = 46;             // room below every card for its icon-on-edge + name pill
const SCALE = 0.58;             // uniform miniature scale of a widget's real width
const HSCALE = 0.52;            // height runs slightly shorter than a strict pane — full
                                // .58 read as too tall next to the .29 width
const MIN_W = 96;               // degenerate-sliver floor ONLY — small enough that the
                                // fit guards, not the floor, decide on any real window
                                // (a big floor re-inflating past the guards is what used
                                // to make cards overlap on small windows)

// Per-widget size factor on the common card size: Fear & Greed and EUR/USD
// show only their hero (gauge / rate + spark) in the overview, so their cards
// are compact square-ish tiles — the size variety real Mission Control
// windows have.
const SIZE_F = {
  'widget-reddit': 1,
  'widget-fj': 1,
  'widget-fg': 0.62,
  'widget-eurusd': 0.62,
  'widget-weather': 0.62,
  'widget-watchlist': 0.8,
  'widget-deepdive': 0.8,
};

// Normalized default centres per widget id.
//
// The seeds follow the Exposé/Mission-Control principle (open-source twin:
// KWin's ExpoLayout "Natural" mode): every window STARTS at its real on-screen
// position and is then iteratively nudged apart until nothing overlaps — the
// spatial relationship survives the zoom-out. For us that means: Schlagzeilen
// seeds where its dashboard pane lives (left half), Financial Juice right
// half. The two compact gauges — Fear & Greed and EUR/USD, neither of which
// has a dashboard pane — sit side by side as a top row above the panes
// (user-mandated: the small tiles live "oben"). separate() below is that same
// iterative relaxation; user drags simply replace the seed and go through the
// identical pipeline.
const DEFAULTS = {
  'widget-reddit': { x: 0.250, y: 0.56 },
  'widget-fj':     { x: 0.750, y: 0.56 },
  'widget-fg':     { x: 0.320, y: 0.17 },
  'widget-eurusd': { x: 0.500, y: 0.17 },
  'widget-weather': { x: 0.680, y: 0.17 },
  // The watchlist has no dashboard pane to inherit a position from — it seeds
  // dead centre between the two big panes; separate() makes room.
  'widget-watchlist': { x: 0.500, y: 0.62 },
  // The KI-DD seeds beside the watchlist (its on-demand sibling).
  'widget-deepdive': { x: 0.780, y: 0.62 },
};

let main = null;
let onActivate = null;
let positions = load();        // { id: {x, y} } — widths stay code-defined

function load() {
  try {
    const p = JSON.parse(localStorage.getItem(STORE_KEY) || '{}');
    const out = {};
    for (const id of Object.keys(DEFAULTS)) {
      const v = p[id];
      out[id] = v && isFinite(v.x) && isFinite(v.y)
        ? { x: Math.min(1, Math.max(0, v.x)), y: Math.min(1, Math.max(0, v.y)) }
        : { x: DEFAULTS[id].x, y: DEFAULTS[id].y };
    }
    return out;
  } catch {
    const out = {};
    for (const id of Object.keys(DEFAULTS)) out[id] = { x: DEFAULTS[id].x, y: DEFAULTS[id].y };
    return out;
  }
}

function save() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(positions)); } catch { /* ignore */ }
}

function cards() {
  return [...main.querySelectorAll(':scope > .widget')].filter(w => DEFAULTS[w.id]);
}

function inGrid() {
  return main && main.dataset.view === 'grid';
}

/**
 * A widget's natural pane width: half the SCREEN, not half the current
 * window. The screen resolution is the fixed reference viewport every
 * miniature lays out against, so the painted zoom (card width / natural
 * width) tracks the window size — shrink the window and text/images shrink
 * WITH the card. (The old fixed CSS zoom kept content at a constant size
 * while the card scaled around it.) Math.max guards a bogus/unavailable
 * screen value under OSR: the reference is never smaller than the window.
 */
function naturalPaneW(W) {
  return 0.5 * Math.max(W, (window.screen && window.screen.width) || 0);
}

/**
 * The uniform card size: a card is the widget at its ORIGINAL terminal size
 * (a dashboard pane, half the width × full height), scaled by one common
 * factor so all of them fit — relative, exactly like Mission Control scales
 * real window sizes. Fit guards shrink the whole miniature (ratio kept) when
 * the container can't hold a row of three or the height.
 */
function cardSize(W, H) {
  let w = 0.5 * W * SCALE;
  let h = H * HSCALE;
  const maxW = (W - 4 * GAP) / 3;
  if (w > maxW) { h *= maxW / w; w = maxW; }
  // The arrangement stacks TWO rows — the compact tiles above the two big
  // panes — so the height guard must fit both rows plus their label zones.
  // (The old single-row guard let the tile row overlap the panes as soon as
  // the window got short.)
  const tileF = SIZE_F['widget-fg'];
  const maxH = (H - 3 * GAP - 2 * LABEL_H) / (1 + tileF);
  if (h > maxH) { w *= maxH / h; h = maxH; }
  if (w < MIN_W) { h *= MIN_W / w; w = MIN_W; }
  return { w, h };
}

/** Current px rect of a card from the stored normalized centre. */
function rectFor(id, W, H) {
  const base = cardSize(W, H);
  const f = SIZE_F[id] ?? 1;
  const w = base.w * f;
  const h = base.h * f;
  return {
    id, w, h,
    left: positions[id].x * W - w / 2,
    top: positions[id].y * H - h / 2,
  };
}

function clamp(r, W, H) {
  r.left = Math.min(Math.max(r.left, GAP), Math.max(GAP, W - GAP - r.w));
  // The bottom keeps LABEL_H free: the icon straddles the card's lower edge
  // and the name pill hangs below it.
  r.top = Math.min(Math.max(r.top, GAP), Math.max(GAP, H - GAP - LABEL_H - r.h));
}

/**
 * Pushes overlapping cards apart until every pair keeps GAP (best effort on
 * absurdly small containers). `pinnedId` (the just-dropped card) never moves —
 * the others yield — unless clamping forces everyone into the same corner.
 */
function separate(rects, W, H, pinnedId = null) {
  for (let pass = 0; pass < 40; pass++) {
    let moved = false;
    for (let i = 0; i < rects.length; i++) {
      for (let j = i + 1; j < rects.length; j++) {
        const a = rects[i];
        const b = rects[j];
        // Vertical extents include the label zone below each card, so a
        // card can never sit inside another's icon + name pill.
        const ox = Math.min(a.left + a.w, b.left + b.w) - Math.max(a.left, b.left) + GAP;
        const oy = Math.min(a.top + a.h + LABEL_H, b.top + b.h + LABEL_H)
                 - Math.max(a.top, b.top) + GAP;
        if (ox <= 0 || oy <= 0) continue;
        moved = true;
        // Push along the axis of least penetration, away from each other.
        const acx = a.left + a.w / 2, bcx = b.left + b.w / 2;
        const acy = a.top + a.h / 2, bcy = b.top + b.h / 2;
        const wa = a.id === pinnedId ? 0 : 1;
        const wb = b.id === pinnedId ? 0 : 1;
        const total = wa + wb || 1;
        if (ox < oy) {
          const dir = acx <= bcx ? 1 : -1;
          a.left -= (ox * wa / total) * dir;
          b.left += (ox * wb / total) * dir;
        } else {
          const dir = acy <= bcy ? 1 : -1;
          a.top -= (oy * wa / total) * dir;
          b.top += (oy * wb / total) * dir;
        }
        clamp(a, W, H);
        clamp(b, W, H);
      }
    }
    if (!moved) return;
  }
}

/**
 * Lays the cards out for the current container size: normalized centres → px
 * rects, gap enforced, inline styles applied. `settle` animates the move (used
 * after a drop so displaced neighbours glide, not jump).
 */
export function applyGridLayout(settle = false, pinnedId = null) {
  if (!main) return;
  const W = main.clientWidth;
  const H = main.clientHeight;
  if (!W || !H) return;
  // One shared miniature zoom for all cards: every card's layout viewport is
  // its natural (screen-pane × SIZE_F) size, painted at whatever the card
  // measures right now — widget-grid.css consumes the var. Stays set after
  // leaving the grid so exiting cards keep their scale mid-flight.
  const zoom = cardSize(W, H).w / naturalPaneW(W);
  main.style.setProperty('--grid-zoom', zoom.toFixed(4));
  // The icon + name pill live OUTSIDE the zoomed body at fixed design sizes
  // (tuned for a maximized window, zoom == SCALE). Scale them by the same
  // ratio the cards shrink, else the nowrap pills collide long before the
  // cards do on small windows. Capped at 1 — they never grow past design.
  main.style.setProperty('--grid-tag-scale', Math.min(1, zoom / SCALE).toFixed(4));
  const rects = cards().map(c => rectFor(c.id, W, H));
  rects.forEach(r => clamp(r, W, H));
  separate(rects, W, H, pinnedId);
  for (const r of rects) {
    const el = document.getElementById(r.id);
    el.classList.toggle('settle', settle && r.id !== pinnedId);
    el.style.left = `${Math.round(r.left)}px`;
    el.style.top = `${Math.round(r.top)}px`;
    el.style.width = `${Math.round(r.w)}px`;
    el.style.height = `${Math.round(r.h)}px`;
  }
  if (settle) setTimeout(() => cards().forEach(c => c.classList.remove('settle')), 400);
}

/** Removes the grid inline geometry when leaving the view. */
export function clearGridLayout() {
  if (!main) return;
  for (const c of cards()) {
    c.classList.remove('settle', 'dragging');
    c.style.left = '';
    c.style.top = '';
    c.style.width = '';
    c.style.height = '';
  }
}

/** Reads a card's on-screen rect back into its normalized centre. */
function persistFrom(el) {
  const W = main.clientWidth;
  const H = main.clientHeight;
  positions[el.id] = {
    x: (el.offsetLeft + el.offsetWidth / 2) / W,
    y: (el.offsetTop + el.offsetHeight / 2) / H,
  };
}

export function initGridLayout(mainEl, opts = {}) {
  main = mainEl;
  onActivate = opts.onActivate || (() => {});

  window.addEventListener('resize', () => { if (inGrid()) applyGridLayout(); });

  for (const card of cards()) {
    const hit = card.querySelector('.grid-hit');
    if (!hit) continue;
    let drag = null;          // { startX, startY, left, top, moved }

    // Wheel over a card scrolls INSIDE the widget: the hit overlay owns the
    // pointer (the miniature content is inert), so it forwards the wheel to
    // the card's .widget-body — the one scroller every widget has. Delta is
    // divided by the miniature zoom so the PAINTED scroll speed matches the
    // wheel 1:1 (scrollTop lives in the body's own, un-zoomed units). Cards
    // with a dedicated .grid-thumb have their body hidden — nothing scrolls,
    // which is the honest behaviour for a static tile.
    hit.addEventListener('wheel', e => {
      if (!inGrid() || drag) return;
      const body = card.querySelector('.widget-body');
      if (!body) return;
      e.preventDefault();
      const zoom = parseFloat(main.style.getPropertyValue('--grid-zoom')) || 1;
      body.scrollTop += e.deltaY / zoom;
    }, { passive: false });

    hit.addEventListener('pointerdown', e => {
      if (!inGrid() || e.button !== 0) return;
      try { hit.setPointerCapture(e.pointerId); } catch { /* capture is best-effort */ }
      drag = {
        startX: e.clientX, startY: e.clientY,
        left: card.offsetLeft, top: card.offsetTop,
        moved: false,
      };
    });

    hit.addEventListener('pointermove', e => {
      if (!drag) return;
      const dx = e.clientX - drag.startX;
      const dy = e.clientY - drag.startY;
      if (!drag.moved && Math.hypot(dx, dy) < 6) return;
      if (!drag.moved) {
        drag.moved = true;
        card.classList.add('dragging');
        // Raster once, translate in the compositor while the pointer moves.
        card.style.willChange = 'left, top';
      }
      const W = main.clientWidth;
      const H = main.clientHeight;
      card.style.left = `${Math.min(Math.max(drag.left + dx, GAP), W - GAP - card.offsetWidth)}px`;
      card.style.top = `${Math.min(Math.max(drag.top + dy, GAP), H - GAP - LABEL_H - card.offsetHeight)}px`;
    });

    const finish = e => {
      if (!drag) return;
      const wasDrag = drag.moved;
      drag = null;
      card.classList.remove('dragging');
      card.style.willChange = '';
      if (!wasDrag) return;      // plain click → the click handler activates
      e.preventDefault();
      persistFrom(card);
      // Neighbours yield to the dropped card, everything keeps the gap.
      applyGridLayout(true, card.id);
      for (const c of cards()) persistFrom(c);
      save();
      suppressClick = true;
    };
    let suppressClick = false;
    hit.addEventListener('pointerup', finish);
    hit.addEventListener('pointercancel', () => {
      if (drag && drag.moved) { card.classList.remove('dragging'); card.style.willChange = ''; applyGridLayout(); }
      drag = null;
    });
    hit.addEventListener('click', () => {
      if (suppressClick) { suppressClick = false; return; }
      if (inGrid()) onActivate(card);
    });
  }
}
