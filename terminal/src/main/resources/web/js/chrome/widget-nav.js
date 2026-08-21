// Widget system navigation: dashboard ⇄ grid (fixed-raster overview) ⇄
// focus (one widget fullscreen). The state lives as [data-view] on .main plus
// .focused on the fullscreen widget; widget-grid.css owns each state's static
// geometry. This module owns the TRANSITIONS: every view switch is
// FLIP-animated (measure before → mutate → invert → play), so a widget's box
// visibly morphs between its layouts instead of snapping — the Clash-of-Clans
// zoom feel. Widgets entering/leaving a view fly radially relative to the
// focused card (zoom-in: neighbours rush outward past the camera; zoom-out:
// they settle back in). All animations are transient one-shots — nothing
// loops (software-OSR paint rule).

import { initGridLayout, applyGridLayout, clearGridLayout } from './grid-layout.js';

const DUR = 460;                                // box morph
const EASE = 'cubic-bezier(.22,.86,.3,1)';      // fast start, soft landing
const EXIT_DUR = 340;
const EXIT_EASE = 'cubic-bezier(.5,0,.8,.4)';   // accelerate away

let main = null;
let widgets = [];
let view = 'dashboard';
let busy = false;
// Where to return when the settings view closes: the settings only ever render
// over the dashboard, so opening them from grid/focus parks the view — and
// closing them must land back where the user actually was.
let settingsReturn = null;

const reducedMotion = () =>
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export function initWidgetNav() {
  main = document.querySelector('.main');
  if (!main) return;
  widgets = [...main.querySelectorAll(':scope > .widget')];

  // Focus-view content scale + column width, recomputed on every resize.
  syncFocusLayout();
  window.addEventListener('resize', syncFocusLayout);

  document.querySelectorAll('.js-grid-toggle').forEach(b =>
    b.addEventListener('click', onGridButton));

  // Card geometry and wheel forwarding live in grid-layout.js; it calls
  // back here when a card is activated (click / Enter).
  initGridLayout(main, {
    onActivate: w => { if (view === 'grid') setView('focus', w); },
  });

  // The settings view replaces the centre widgets — it only ever opens over
  // the dashboard, so leave grid/focus instantly (no animation), but remember
  // where we were: the settings back arrow returns THERE. Driven by the OPEN
  // event, not the gear's click: the gear toggles now, and on a close the
  // restore below must be the only thing that runs.
  window.addEventListener('wsbg:settingsopen', () => {
    if (view !== 'dashboard') {
      settingsReturn = {
        view,
        focused: widgets.find(w => w.classList.contains('focused')) || null,
      };
      setView('dashboard', null, { instant: true });
    } else {
      settingsReturn = null;
    }
  });

  // Settings closed (back arrow / Escape): restore the parked view.
  window.addEventListener('wsbg:settingsclosed', () => {
    const r = settingsReturn;
    settingsReturn = null;
    if (r) setView(r.view, r.focused, { instant: true });
  });

  document.addEventListener('keydown', onKey);
}

/**
 * Focus-view geometry: how large the content PAINTS (--focus-zoom) and how wide
 * the reading column is (--focus-col). Both are pure functions of the window.
 *
 * This used to be `main.clientWidth / max(clientWidth, screen.width)` — the
 * same shape of mistake the post-mortem on naturalPaneW() in grid-layout.js
 * describes, with the window on BOTH sides of the ratio. Under the OSR browser
 * getScreenInfo() reports the BROWSER VIEW RECT as the screen (SwingCefBrowser
 * .getScreenInfo), and .main spans 100vw, so both terms were the SAME NUMBER:
 * measured live at a 1735px window, screen.width was also 1735 and the zoom
 * came out 1.0000. It could never be anything else, at any window size — the
 * scale has never once fired. And even repaired the formula only ever CAPPED at
 * 1, so it could shrink, never grow: the one thing a large window needs.
 *
 * Now the reference is a CONSTANT design width, like the grid's. Above it the
 * content grows, sublinearly (sqrt, the same idiom as tagScaleFor) and capped —
 * a 4K window should read larger, not doubled. Below it nothing happens: the
 * design width IS the small-window layout, and shrinking type there would make
 * a narrow window worse, not better.
 */
const REF_FOCUS_W = 1920;     // window width the focus layout is designed for
const FOCUS_ZOOM_MAX = 1.3;   // type never grows past a third over design
const FOCUS_COL_REF = 920;    // design width of the reading column
const FOCUS_COL_MAX = 1440;   // …and its ceiling, in the same (zoomed) units
const FOCUS_FILL = 0.62;      // share of the window the column may claim

function syncFocusLayout() {
  const W = main.clientWidth;
  if (!W) return;
  const zoom = Math.min(FOCUS_ZOOM_MAX, Math.max(1, Math.sqrt(W / REF_FOCUS_W)));
  // The column lives INSIDE the zoomed subtree, so it is expressed in zoomed
  // units: painted width is col * zoom. Growing both is what fills a wide
  // window — the zoom alone would leave the same gutters, only with bigger
  // type, and a wider column alone would set 12px text across 1800px.
  const col = Math.min(FOCUS_COL_MAX, Math.max(FOCUS_COL_REF, (W * FOCUS_FILL) / zoom));
  main.style.setProperty('--focus-zoom', zoom.toFixed(4));
  main.style.setProperty('--focus-col', `${Math.round(col)}px`);
  // The detail widgets (F&G, EUR/USD) set their charts NARROWER than the
  // reading column by design; keep that proportion instead of a second
  // constant that would drift away from it.
  main.style.setProperty('--detail-col', `${Math.round(col * 0.78)}px`);
}

function onGridButton() {
  // The grid button toggles the overview from anywhere: dashboard → grid,
  // grid → dashboard, focus → grid.
  closeSettingsView();
  if (view === 'dashboard') setView('grid');
  else if (view === 'grid') setView('dashboard');
  else setView('grid');
}

function closeSettingsView() {
  const sv = document.getElementById('settings-view');
  if (sv && !sv.hidden) {
    sv.hidden = true;
    main.classList.remove('settings-open');
    // Deliberate navigation to the grid — drop the parked return view (and
    // don't dispatch wsbg:settingsclosed, which would race the restore).
    settingsReturn = null;
  }
}

function onKey(e) {
  if (e.key !== 'Escape' || view === 'dashboard') return;
  // Layered chrome closes first — its own Escape handlers own the key while
  // an overlay / the settings view / a popup is open.
  if (document.querySelector('.overlay:not([hidden])')) return;
  if (main.classList.contains('settings-open')) return;
  if (document.querySelector('.rail-item.open')) return;
  const filterPop = document.getElementById('headline-filter-popover');
  if (filterPop && !filterPop.hasAttribute('hidden')) return;
  setView(view === 'focus' ? 'grid' : 'dashboard');
}

function isVisible(r) {
  return !!r && r.width > 0 && r.height > 0;
}

/**
 * Every widget's box in ONE layout pass. Reading a rect is only cheap while no
 * style has been written since the last one — interleaving reads and writes
 * makes the browser re-run layout for EVERY read (see setView's PLAY loop).
 */
function measureAll() {
  const m = new Map();
  for (const w of widgets) m.set(w, w.getBoundingClientRect());
  return m;
}

function centerOf(r) {
  return r ? { x: r.left + r.width / 2, y: r.top + r.height / 2 } : null;
}

/**
 * Switches the view with a FLIP transition. `focusEl` names the widget to
 * fullscreen when `next` is "focus". `opts.instant` skips the animation
 * (settings hand-off, reduced motion).
 */
function setView(next, focusEl = null, opts = {}) {
  if (busy) return;
  if (view === next) return;
  const prev = view;
  const prevFocused = widgets.find(w => w.classList.contains('focused'));

  // FIRST: capture every widget's box (one layout pass; invisible ones come
  // back as zero rects and are filtered at use).
  const before = measureAll();

  // MUTATE: flip the state, let CSS lay out the target view. The grid's card
  // geometry is inline px (fixed raster) — applied on entry, removed on
  // exit, both BEFORE the "after" rects are measured below.
  view = next;
  main.dataset.view = next;
  for (const w of widgets) w.classList.toggle('focused', next === 'focus' && w === focusEl);
  if (next === 'grid') applyGridLayout();
  else if (prev === 'grid') clearGridLayout();
  syncGridButtons();
  window.dispatchEvent(new CustomEvent('wsbg:viewchange', { detail: { view: next } }));

  if (opts.instant || reducedMotion()) return;

  busy = true;
  // Hover chrome is suppressed for the duration (widget-grid.css): the pointer
  // is almost always parked over a card that is about to move, and animating
  // an 80px-blur shadow under a layer in flight is a card-sized repaint per
  // frame that nobody can see mid-transition.
  main.classList.add('view-busy');
  setTimeout(() => {
    busy = false;
    main.classList.remove('view-busy');
  }, DUR + 60);

  // LAST: the new boxes, again in a SINGLE pass and BEFORE any animation runs.
  // flip()/enter()/exit() all write inline styles (exit() even sets
  // position:fixed), so a rect read after one of them forces a fresh
  // full-document layout. Measuring inside the play loop therefore cost one
  // forced layout PER WIDGET — measured at 20.7ms of blocking script with 220
  // forced style+layout passes for a single view switch, which is exactly the
  // hitch felt at the moment the grid is triggered.
  const after = measureAll();

  // Radial anchor: zooming IN → the clicked card's old box; zooming OUT of
  // focus → the focused widget's new card box.
  let anchor = null;
  if (next === 'focus' && focusEl) anchor = centerOf(before.get(focusEl));
  if (prev === 'focus' && prevFocused) anchor = centerOf(after.get(prevFocused));

  // INVERT + PLAY per widget — writes only, no reads.
  for (const w of widgets) {
    const was = isVisible(before.get(w)) ? before.get(w) : null;
    const now = isVisible(after.get(w)) ? after.get(w) : null;
    if (was && now) flip(w, was, now, w === focusEl || w === prevFocused);
    else if (!was && now) enter(w, anchor, now);
    else if (was && !now) exit(w, was, anchor);
  }
}

/** Visible before AND after: morph the box from the old rect to the new one. */
function flip(el, was, now, elevated) {
  const dx = was.left - now.left;
  const dy = was.top - now.top;
  const sx = was.width / now.width;
  const sy = was.height / now.height;
  if (Math.abs(dx) < 1 && Math.abs(dy) < 1 && Math.abs(sx - 1) < 0.01 && Math.abs(sy - 1) < 0.01) return;

  el.style.transformOrigin = '0 0';
  // Promote to a compositor layer for the animation: the content rasters
  // once and only the transform changes per frame. Without this, every frame
  // re-rasters the whole widget — visibly janky while gemma saturates the
  // machine. Cleared on finish (a permanently promoted fullscreen layer
  // would cost memory and dull text rendering).
  el.style.willChange = 'transform';
  if (elevated) el.style.zIndex = '10';
  el.animate([
    { transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})` },
    { transform: 'none' },
  ], { duration: DUR, easing: EASE }).onfinish = () => {
    el.style.transformOrigin = '';
    el.style.zIndex = '';
    el.style.willChange = '';
  };

  // The content re-flows between the miniature and the full layout at the
  // moment of mutation — a quick body crossfade masks the reflow inside the
  // morphing box. Short and opacity-only (compositor-cheap).
  const body = el.querySelector('.widget-body');
  if (body) body.animate(
    [{ opacity: 0.35 }, { opacity: 1 }],
    { duration: 240, easing: 'ease-out' });
}

/** Not visible before, visible now: settle in (radially from `from`, if any). */
function enter(el, from, now) {
  let start;
  if (from) {
    const c = centerOf(now);
    const dx = (c.x - from.x) * 0.6;
    const dy = (c.y - from.y) * 0.6;
    start = { transform: `translate(${dx}px, ${dy}px) scale(.86)`, opacity: 0 };
  } else {
    start = { transform: 'scale(.72)', opacity: 0 };
  }
  el.style.transformOrigin = '50% 50%';
  el.style.willChange = 'transform, opacity';
  el.animate([start, { transform: 'none', opacity: 1 }],
      { duration: DUR * 0.85, delay: 40, easing: EASE, fill: 'backwards' })
    .onfinish = () => {
      el.style.transformOrigin = '';
      el.style.willChange = '';
    };
}

/**
 * Visible before, hidden now: the new state would display:none it, so pin it
 * position:fixed at its old box (.exiting keeps it displayed, .exit-card keeps
 * the card look) and fly it out — radially away from the anchor (the camera
 * dives past the neighbours) or a plain fade-shrink without one.
 */
function exit(el, was, awayFrom) {
  el.classList.add('exiting', 'exit-card');
  Object.assign(el.style, {
    position: 'fixed',
    left: `${was.left}px`,
    top: `${was.top}px`,
    width: `${was.width}px`,
    height: `${was.height}px`,
    zIndex: '8',
    willChange: 'transform, opacity',
  });
  let end;
  if (awayFrom) {
    const cx = was.left + was.width / 2;
    const cy = was.top + was.height / 2;
    const vx = cx - awayFrom.x;
    const vy = cy - awayFrom.y;
    const len = Math.hypot(vx, vy) || 1;
    // Travel is CAPPED, not a share of the window. It used to be
    // `max(innerWidth, innerHeight) * 0.5` — 640px of sweep on a small window
    // against 1720px on a wide one, and every pixel a card sweeps is viewport
    // the software-OSR pipeline has to copy again that frame. That is the
    // grid→focus case, the most expensive one measured. The cards fade to zero
    // on the way out, so past a few hundred pixels the extra distance buys
    // damage and nothing else.
    const dist = Math.min(520, Math.max(window.innerWidth, window.innerHeight) * 0.5);
    end = {
      transform: `translate(${(vx / len) * dist}px, ${(vy / len) * dist}px) scale(1.12)`,
      opacity: 0,
    };
  } else {
    end = { transform: 'scale(.82)', opacity: 0 };
  }
  el.animate([{ transform: 'none', opacity: 1 }, end],
      { duration: EXIT_DUR, easing: EXIT_EASE })
    .onfinish = () => {
      el.classList.remove('exiting', 'exit-card');
      el.style.position = '';
      el.style.left = '';
      el.style.top = '';
      el.style.width = '';
      el.style.height = '';
      el.style.zIndex = '';
      el.style.willChange = '';
    };
}

function syncGridButtons() {
  const pressed = String(view !== 'dashboard');
  document.querySelectorAll('.js-grid-toggle').forEach(b =>
    b.setAttribute('aria-pressed', pressed));
}
