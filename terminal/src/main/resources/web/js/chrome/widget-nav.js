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

  // Focus-view content scale: window width relative to the screen (1 when
  // maximized — the fullscreen layout is the reference, like the grid's
  // miniature zoom). Floored so type never shrinks past readable; below the
  // floor the column reflows within its painted width instead.
  syncFocusZoom();
  window.addEventListener('resize', syncFocusZoom);

  document.querySelectorAll('.js-grid-toggle').forEach(b =>
    b.addEventListener('click', onGridButton));

  // Card geometry and wheel forwarding live in grid-layout.js; it calls
  // back here when a card is activated (click / Enter).
  initGridLayout(main, {
    onActivate: w => { if (view === 'grid') setView('focus', w); },
  });

  // The settings view replaces the centre widgets — it only ever opens over
  // the dashboard, so leave grid/focus instantly (no animation) first, but
  // remember where we were: the settings back arrow returns THERE.
  document.querySelectorAll('.js-settings-toggle').forEach(b =>
    b.addEventListener('click', () => {
      if (view !== 'dashboard') {
        settingsReturn = {
          view,
          focused: widgets.find(w => w.classList.contains('focused')) || null,
        };
        setView('dashboard', null, { instant: true });
      } else {
        settingsReturn = null;
      }
    }));

  // Settings closed (back arrow / Escape): restore the parked view.
  window.addEventListener('wsbg:settingsclosed', () => {
    const r = settingsReturn;
    settingsReturn = null;
    if (r) setView(r.view, r.focused, { instant: true });
  });

  document.addEventListener('keydown', onKey);
}

function syncFocusZoom() {
  const W = main.clientWidth;
  const ref = Math.max(W, (window.screen && window.screen.width) || 0);
  const z = Math.min(1, Math.max(0.55, W / ref));
  main.style.setProperty('--focus-zoom', z.toFixed(4));
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

function isVisible(el) {
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
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

  // FIRST: capture every currently visible widget's box.
  const before = new Map();
  for (const w of widgets) if (isVisible(w)) before.set(w, w.getBoundingClientRect());

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
  setTimeout(() => { busy = false; }, DUR + 60);

  // Radial anchor: zooming IN → the clicked card's old box; zooming OUT of
  // focus → the focused widget's new card box.
  let anchor = null;
  if (next === 'focus' && focusEl) anchor = centerOf(before.get(focusEl));
  if (prev === 'focus' && prevFocused) anchor = centerOf(prevFocused.getBoundingClientRect());

  // LAST + INVERT + PLAY per widget.
  for (const w of widgets) {
    const was = before.get(w) || null;
    const now = isVisible(w) ? w.getBoundingClientRect() : null;
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
    const dist = Math.max(window.innerWidth, window.innerHeight) * 0.5;
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
