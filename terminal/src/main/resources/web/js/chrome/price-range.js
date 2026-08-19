// Preisspanne — the price-band facet of the headline filter, and the zoom-dial
// it is set with.
//
// The problem: a stock wire spans six orders of magnitude. A linear slider that
// reaches a 700 000 € share has no resolution left for the 0,004 € pennystock
// the sub is actually shouting about; one that resolves tenths of a cent never
// reaches the top of the market. So two things are true at once here:
//
//   1. The SCALE is logarithmic — 0,01 … 1 000 000, plus an open cap at each
//      end ("0" below, "∞" above). A quarter of the track is the 0-1 region.
//   2. The GESTURE has two axes, and both are DIRECT. Sideways is the value:
//      the bound sits under the cursor and stays under it, at every zoom.
//      Downward (or upward) is the zoom: pull away from the track and the scale
//      spreads open AROUND the cursor, until a pixel buys a fraction of a cent.
//      Pull back and it closes again. Hold ⇧ to quarter the step once more.
//
// Nothing is inferred from drag speed: the hand says how fine it wants to be,
// and it can take that back by moving. The rounding step and the number of
// decimals are DERIVED from the zoom, so the readout never claims precision the
// gesture can't deliver.
//
// At rest the facet is a slim two-handle track in the filter panel. While a
// handle is being dragged that same row grows in place into a ruler — ticks,
// the density rug of the prices actually on the wire, and a lens on the whole
// domain — anchored so the ruler's axis lies exactly where the track was. It is
// a panel over its own row, never a full-screen stage: the drag that opened it
// is the drag that keeps working in it.
//
// The panel lives on <body> (fixed, measured against the track) because both of
// its hosts — the header popover and the rail popup — would otherwise clip or
// scroll it away.

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { getSpec, setRange } from '../widgets/headline-filter.js';
import { loadedPrices } from '../widgets/reddit.js';

// ---- the scale ------------------------------------------------------------

const LOG_MIN = -2;                     // 0,01
const LOG_MAX = 6;                      // 1 000 000
const SPAN = LOG_MAX - LOG_MIN;
// Pushing past a domain edge is what opens a bound ("0" below, "∞" above), and
// the wall is measured in PIXELS, not decades: at the fine zoom a half-decade
// of overshoot would be a 1400px shove, at the coarse zoom a flick. Pixels make
// the wall the same distance away at every zoom.
const CAP_PX = 88;                      // outward travel that flips the bound open
const PUSH_MAX = 140;                   // …and where the push stops accumulating
const PUSH_RUBBER = 0.35;               // how much of the push the ruler visibly gives
const GAP = 0.06;                       // smallest distance (decades) between the handles

// ---- the gesture ----------------------------------------------------------

// log10 per px at the two ends of the vertical pull. The coarse end is not a
// constant: it is "the whole domain across the ruler", so an untouched drag is
// exactly the plain slider the resting track looks like.
const UPP_MIN = 0.00008;                // fine: ~a tenth of a cent per 4px down at 0,40
const ZOOM_DEAD = 12;                   // px of vertical slop before the pull counts
const ZOOM_TRAVEL = 190;                // …and the pull that takes it all the way in
const ZOOM_LERP = 0.22;                 // per-frame approach of the animated zoom
// How close the handle may get to the rim before the SCALE starts moving under
// it instead. Zero when zoomed all the way out (the whole domain is on screen,
// there is nowhere to pan to) and it opens up as the zoom closes in.
const EDGE_HOLD = 0.17;
const SHIFT = 0.25;                     // ⇧ multiplier on top of the pull

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const smooth = s => s * s * (3 - 2 * s);
const locale = () => (currentLang() === 'de' ? 'de-DE' : 'en-US');

/** Position 0..1 on the domain for a bound value (0 = open low, null = open high). */
function fracOf(v, side) {
  if (side === 'min' && v === 0) return 0;
  if (side === 'max' && v === null) return 1;
  return clamp((Math.log10(v) - LOG_MIN) / SPAN, 0, 1);
}

/** The log position a bound sits at; an open bound stands ON its domain edge. */
function logOfBound(v, side) {
  if (side === 'min' && v === 0) return LOG_MIN;
  if (side === 'max' && v === null) return LOG_MAX;
  return clamp(Math.log10(v), LOG_MIN, LOG_MAX);
}

/** 1-2-5 step ladder — the classic "nice number" at or above x. */
function niceStep(x) {
  if (!(x > 0)) return 0.0001;
  const e = Math.pow(10, Math.floor(Math.log10(x)));
  const f = x / e;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * e;
}

/**
 * The smallest value change the current zoom can actually resolve: roughly four
 * pixels of travel, snapped to the 1-2-5 ladder. This is what turns "how far
 * did I pull down" into "how many Nachkommastellen do I get".
 */
function stepFor(value, upp) {
  return niceStep(value * Math.LN10 * upp * 4);
}

function decimalsFor(step) {
  return clamp(Math.ceil(-Math.log10(step)), 0, 4);
}

function fmtNumLoc(v, dec) {
  return v.toLocaleString(locale(), { minimumFractionDigits: dec, maximumFractionDigits: dec });
}

/** Axis label: thousands/millions get a suffix, but only when nothing is lost. */
function fmtTick(v, dec) {
  if (dec === 0 && v >= 1e6 && v % 1e6 === 0) return fmtNumLoc(v / 1e6, 0) + ' M';
  if (dec === 0 && v >= 1e4 && v % 1e3 === 0) return fmtNumLoc(v / 1e3, 0) + ' k';
  return fmtNumLoc(v, dec);
}

/** Readout precision for a value nobody is currently dragging. */
function restingDecimals(v) {
  return v < 1 ? 3 : v < 1000 ? 2 : 0;
}

function fmtBound(v, side, dec) {
  if (side === 'min' && v === 0) return '0';
  if (side === 'max' && v === null) return '∞';
  return fmtNumLoc(v, dec == null ? restingDecimals(v) : dec);
}

// ---------------------------------------------------------------------------
// The resting control (inside the filter panel)
// ---------------------------------------------------------------------------

// Set right before a keyboard adjust writes the spec — the panel re-renders
// synchronously inside that write, which tears the focused handle out of the
// DOM. The freshly mounted twin picks the focus back up. Keyed by the panel
// ANCHOR (the stable container the panel renders into), never by the row
// element: that one is exactly what the re-render replaces.
let pendingFocus = null;    // { anchor, side }

/**
 * Renders the price-range row into `host` (called by the filter panel on every
 * render); `anchor` is the panel container that survives the re-render. Reads
 * the band straight off the spec, so the two panel anchors stay in sync for
 * free.
 */
export function mountPriceRange(host, anchor) {
  if (!host) return;
  host._prAnchor = anchor;
  const band = getSpec().range;
  const min = band ? band.min : 0;
  const max = band ? band.max : null;

  host.innerHTML = `
    <div class="pr-mini-head">
      <span class="filter-label">${escapeHtml(t('filter.range.label'))}</span>
      <button type="button" class="rail-pop-action pr-clear"${band ? '' : ' disabled'}>${escapeHtml(t('filter.range.clear'))}</button>
    </div>
    <div class="pr-track" title="${escapeHtml(t('filter.range.open'))}">
      <div class="pr-rail"></div>
      <div class="pr-band"></div>
      <button type="button" class="pr-handle" data-side="min" role="slider"
              aria-label="${escapeHtml(t('filter.range.min.aria'))}"></button>
      <button type="button" class="pr-handle" data-side="max" role="slider"
              aria-label="${escapeHtml(t('filter.range.max.aria'))}"></button>
    </div>
    <div class="pr-read">
      <span class="pr-read-lo"></span>
      <span class="pr-read-hi"></span>
    </div>`;

  const fLo = fracOf(min, 'min');
  const fHi = fracOf(max, 'max');
  const band$ = host.querySelector('.pr-band');
  band$.style.left = (fLo * 100) + '%';
  band$.style.width = ((fHi - fLo) * 100) + '%';
  band$.classList.toggle('on', !!band);

  const handles = {};
  host.querySelectorAll('.pr-handle').forEach(el => {
    const side = el.dataset.side;
    const v = side === 'min' ? min : max;
    handles[side] = el;
    el.style.left = (fracOf(v, side) * 100) + '%';
    el.classList.toggle('open-cap', side === 'min' ? v === 0 : v === null);
    el.setAttribute('aria-valuetext', fmtBound(v, side));
  });

  host.querySelector('.pr-read-lo').textContent = `${t('filter.range.from')} ${fmtBound(min, 'min')}`;
  host.querySelector('.pr-read-hi').textContent = `${t('filter.range.to')} ${fmtBound(max, 'max')}`;

  host.querySelector('.pr-clear').addEventListener('click', () => setRange(0, null));

  // Pointer-down anywhere on the track grabs a handle — the two 12px knobs stay
  // a visual cue, the whole strip is the hit area. Grabbing a KNOB keeps the
  // value under the finger; pressing the bare track brings the nearer bound TO
  // the finger. After that first instant the two are the same gesture: the
  // value is wherever the cursor is.
  const track = host.querySelector('.pr-track');
  track.addEventListener('pointerdown', e => {
    e.preventDefault();
    const box = track.getBoundingClientRect();
    if (!(box.width > 0)) return;
    const f = clamp((e.clientX - box.left) / box.width, 0, 1);
    const knob = e.target.closest('.pr-handle');
    const side = knob?.dataset.side || (Math.abs(f - fLo) <= Math.abs(f - fHi) ? 'min' : 'max');
    // Where on screen the value is pinned: the knob's own centre when a knob
    // was grabbed, the cursor itself when the track was pressed.
    const anchorX = knob
      ? box.left + fracOf(side === 'min' ? min : max, side) * box.width
      : e.clientX;
    startDrag(side, min, max, e, {
      track: box,
      anchorX,
      jumpLog: knob ? null : LOG_MIN + f * SPAN,
    });
  });

  host.addEventListener('keydown', onKey);

  if (pendingFocus && pendingFocus.anchor === anchor) {
    handles[pendingFocus.side]?.focus({ preventScroll: true });
    pendingFocus = null;
  }
}

// Keyboard is the non-gestural path to the same band: one nice step at the fine
// zoom, ⇧ for a coarse jump, Home/End for the open cap.
function onKey(e) {
  const handle = e.target.closest('.pr-handle');
  if (!handle) return;
  const side = handle.dataset.side;
  const band = getSpec().range;
  let min = band ? band.min : 0;
  let max = band ? band.max : null;
  const cur = side === 'min' ? min : max;

  let next;
  if (e.key === 'Home') next = side === 'min' ? 0 : Math.pow(10, LOG_MIN);
  else if (e.key === 'End') next = side === 'min' ? Math.pow(10, LOG_MAX) : null;
  else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    const dir = e.key === 'ArrowRight' ? 1 : -1;
    const upp = e.shiftKey ? 0.0085 : 0.00022;
    const atCap = side === 'min' ? cur === 0 : cur === null;
    if (atCap) {
      // Stepping off the wall lands ON the domain edge — pressing further into
      // it does nothing, there is no scale out there.
      if (side === 'min') next = dir > 0 ? Math.pow(10, LOG_MIN) : 0;
      else next = dir < 0 ? Math.pow(10, LOG_MAX) : null;
    } else {
      const lg = Math.log10(cur) + dir * upp * (e.shiftKey ? 120 : 60);
      if (side === 'min' && lg < LOG_MIN) next = 0;
      else if (side === 'max' && lg > LOG_MAX) next = null;
      else next = boundAt(side, lg, upp);
    }
  } else return;

  e.preventDefault();
  if (side === 'min') min = next; else max = next;
  pendingFocus = { anchor: handle.closest('.js-price-range')?._prAnchor, side };
  setRange(min, max);
}

/** The bound a log position maps to, quantised to what that zoom can resolve. */
function boundAt(side, logV, upp) {
  if (side === 'min' && logV <= LOG_MIN) return 0;
  if (side === 'max' && logV >= LOG_MAX) return null;
  const raw = Math.pow(10, clamp(logV, LOG_MIN, LOG_MAX));
  const step = stepFor(raw, upp);
  return Math.max(step, Math.round(raw / step) * step);
}

// ---------------------------------------------------------------------------
// The panel — built on the first drag, then reused
// ---------------------------------------------------------------------------

// Where the ruler's baseline sits inside its canvas. The panel is placed so
// that this line lands on the track it grew out of.
const BASE_UP = 26;

let panel = null;
let drag = null;

function buildPanel() {
  const root = document.createElement('div');
  root.className = 'pr-live';
  root.hidden = true;
  root.innerHTML = `
    <div class="pr-heads">
      <span class="pr-head" data-side="min"><i class="pr-head-cap"></i><b class="pr-head-val">0</b></span>
      <span class="pr-head-dash">–</span>
      <span class="pr-head" data-side="max"><i class="pr-head-cap"></i><b class="pr-head-val">∞</b></span>
    </div>
    <div class="pr-ruler-wrap">
      <canvas class="pr-ruler"></canvas>
      <div class="pr-cursor"></div>
    </div>
    <canvas class="pr-lens"></canvas>
    <div class="pr-foot">
      <span class="pr-zoom">
        <i class="pr-zoom-cap"></i>
        <i class="pr-zoom-bar"><b></b></i>
        <i class="pr-zoom-step"></i>
      </span>
      <span class="pr-count"></span>
    </div>
    <div class="pr-hint"></div>`;
  // It hangs off <body> and is pointer-transparent by design (CSS): the gesture
  // that opened it already owns the pointer through window listeners, and a
  // panel that swallowed events would only be a lid over its own track.
  document.body.appendChild(root);

  panel = {
    root,
    heads: {
      min: root.querySelector('.pr-head[data-side="min"]'),
      max: root.querySelector('.pr-head[data-side="max"]'),
    },
    wrap: root.querySelector('.pr-ruler-wrap'),
    ruler: root.querySelector('.pr-ruler'),
    cursor: root.querySelector('.pr-cursor'),
    lens: root.querySelector('.pr-lens'),
    zoomBar: root.querySelector('.pr-zoom-bar > b'),
    zoomCap: root.querySelector('.pr-zoom-cap'),
    zoomStep: root.querySelector('.pr-zoom-step'),
    count: root.querySelector('.pr-count'),
    hint: root.querySelector('.pr-hint'),
    hideTimer: null,
  };
  return panel;
}

/**
 * Places the panel over the row it belongs to: as wide as the track plus its
 * host's padding, and shifted vertically until the ruler's baseline lies on the
 * track itself — so the slim rail visibly BECOMES the ruler instead of being
 * replaced by something elsewhere on screen.
 */
function placePanel(box) {
  const root = panel.root;
  const w = clamp(box.width + 56, 280, window.innerWidth - 16);
  const left = Math.round(clamp(box.left + box.width / 2 - w / 2, 8, window.innerWidth - w - 8));
  root.style.width = w + 'px';
  root.style.left = left + 'px';

  // Where the baseline sits INSIDE the panel. Measured through offsetTop /
  // offsetHeight, never getBoundingClientRect: the panel is mid-transition and
  // still wearing its entry transform, which would fold into a rect read.
  const baseOff = panel.wrap.offsetTop + panel.ruler.offsetHeight - BASE_UP;
  const h = root.offsetHeight;
  const top = clamp(box.top + box.height / 2 - baseOff, 8, Math.max(8, window.innerHeight - h - 8));
  root.style.top = Math.round(top) + 'px';
  // …and the same line is what the panel unfolds from, so it grows out of the
  // track rather than appearing over it.
  root.style.setProperty('--pr-pivot', baseOff + 'px');
  // Nothing in the gesture can move or resize the ruler, so every geometry the
  // paint loop needs is read ONCE, here — every frame afterwards is arithmetic.
  drag.rulerLeft = left + panel.wrap.offsetLeft;
  drag.rSize = [panel.ruler.clientWidth, panel.ruler.clientHeight];
  drag.lSize = [panel.lens.clientWidth, panel.lens.clientHeight];
}

/**
 * Sizes a canvas to its CSS box at device resolution. The box is passed in from
 * the measurement taken when the panel was placed — nothing in the gesture can
 * resize it, and reading it back per frame would force a layout inside the
 * paint loop. Returns [ctx, w, h].
 */
function fitCanvas(cv, [w, h]) {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) {
    cv.width = Math.round(w * dpr);
    cv.height = Math.round(h * dpr);
  }
  const ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return [ctx, w, h];
}

// ---- palette --------------------------------------------------------------

// Canvas takes the tokens verbatim (they are oklch()); if this engine ever
// rejects a colour string the assignment silently doesn't stick, so we probe
// once and fall back to a flat hex twin rather than painting everything black.
const FALLBACK = {
  dark:  { txt: '#ece7dd', mute: '#8a8375', mute2: '#5f5a51', line: '#494540', amber: '#f5c268', bg1: '#2a2825' },
  light: { txt: '#1f1c19', mute: '#736f6b', mute2: '#97938f', line: '#c9c6c2', amber: '#b56400', bg1: '#edebe7' },
};

function palette() {
  const probe = document.createElement('canvas').getContext('2d');
  const cs = getComputedStyle(document.documentElement);
  const fb = FALLBACK[document.documentElement.dataset.theme === 'light' ? 'light' : 'dark'];
  const pick = (token, key) => {
    const raw = cs.getPropertyValue(token).trim();
    if (!raw) return fb[key];
    probe.fillStyle = '#000000';
    probe.fillStyle = raw;
    return probe.fillStyle === '#000000' ? fb[key] : raw;
  };
  return {
    txt: pick('--txt', 'txt'),
    mute: pick('--mute', 'mute'),
    mute2: pick('--mute-2', 'mute2'),
    line: pick('--line', 'line'),
    amber: pick('--amber', 'amber'),
    bg1: pick('--bg-1', 'bg1'),
  };
}

// ---------------------------------------------------------------------------
// The drag
// ---------------------------------------------------------------------------

function startDrag(side, min, max, e, at) {
  if (drag) return;
  const p = panel || buildPanel();
  const prices = loadedPrices().sort((a, b) => a - b);
  const cur = side === 'min' ? min : max;

  drag = {
    side, min, max,
    from: { min, max },                          // for Escape
    box: at.track,                               // the row this grew out of
    logV: at.jumpLog == null ? logOfBound(cur, side) : clamp(at.jumpLog, LOG_MIN, LOG_MAX),
    // The value is pinned to a point on SCREEN, and that point travels with the
    // pointer — that is the whole of the horizontal gesture.
    anchorX: at.anchorX,
    grabDX: e.clientX - at.anchorX,
    startY: e.clientY,
    // A bound that is ALREADY open starts leaning fully into its wall, so the
    // first pull inward brings it straight back onto the scale. Jumping onto
    // the scale lands on it, so no lean.
    push: at.jumpLog == null && (side === 'min' ? min === 0 : max === null)
      ? (side === 'min' ? -CAP_PX : CAP_PX) : 0,
    rulerLeft: at.track.left,                    // refined the moment the panel is placed
    upp: 0,                                      // set on the first frame, from the ruler width
    zoom: 0,                                     // 0 = whole domain, 1 = finest
    shift: e.shiftKey,
    lastX: e.clientX,
    prices,
    pal: palette(),
    step: 0.01,
    dec: 2,
    raf: 0,
    bloomed: false,
    bloomTimer: 0,
  };
  applyValue();

  document.documentElement.classList.add('pr-dragging');
  clearTimeout(p.hideTimer);
  p.root.hidden = false;
  p.hint.textContent = t('filter.range.hint');
  // Laid out (and measured) at once, still invisible: the ruler's width IS the
  // coarsest zoom, so the very first frame of the gesture has to know it.
  placePanel(drag.box);
  // The panel blooms on the first real MOVEMENT, or after a short hold — a
  // plain click on the track (which just sets the bound where you clicked)
  // must not flash a ruler in and straight back out.
  drag.bloomTimer = setTimeout(bloom, 90);

  window.addEventListener('pointermove', onDragMove);
  window.addEventListener('pointerup', onDragEnd);
  window.addEventListener('pointercancel', onDragCancel);
  window.addEventListener('keydown', onDragKey, true);
  window.addEventListener('keyup', onDragKey, true);

  drag.raf = requestAnimationFrame(frame);
}

function bloom() {
  if (!drag || drag.bloomed) return;
  drag.bloomed = true;
  clearTimeout(drag.bloomTimer);
  panel.root.classList.add('on');
}

function onDragKey(e) {
  if (!drag) return;
  if (e.key === 'Escape' && e.type === 'keydown') {
    // Escape belongs to the drag while the drag runs — it must not also reach
    // the popover/rail dismissal underneath and tear the panel away with it.
    e.preventDefault();
    e.stopPropagation();
    onDragCancel();
    return;
  }
  drag.shift = e.shiftKey;
}

function onDragMove(e) {
  if (!drag) return;
  if (!drag.bloomed
      && (Math.abs(e.clientX - drag.lastX) >= 2 || Math.abs(e.clientY - drag.startY) >= 3)) bloom();

  const dx = e.clientX - drag.lastX;
  drag.lastX = e.clientX;
  // The move carries the modifier too — a Shift pressed while another element
  // holds the keyboard never reaches our keydown, but it is right here.
  drag.shift = e.shiftKey;
  // The pinned point follows the pointer 1:1 — always, at every zoom. What the
  // zoom changes is how many decades that pixel travel is worth.
  drag.anchorX = e.clientX - drag.grabDX;

  // The vertical pull is the zoom, in either direction: away from the track is
  // finer. It is a POSITION, not an accumulation, so moving back undoes it.
  const dy = Math.abs(e.clientY - drag.startY);
  drag.zoom = smooth(clamp((dy - ZOOM_DEAD) / ZOOM_TRAVEL, 0, 1));

  const upp = liveUpp();
  const side = drag.side;
  // Neither handle may cross the other; GAP keeps a sliver of band between them.
  const other = side === 'min'
    ? logOfBound(drag.max, 'max') - GAP
    : logOfBound(drag.min, 'min') + GAP;
  const lo = side === 'min' ? LOG_MIN : Math.max(LOG_MIN, other);
  const hi = side === 'min' ? Math.min(LOG_MAX, other) : LOG_MAX;

  const target = drag.logV + dx * upp;
  // Movement that runs into a wall stops being value and becomes PUSH: the
  // pixels pile up against the domain edge, and past CAP_PX the bound opens.
  // Only the edge that faces open-ended-ness counts (there is no "0" above and
  // no "∞" below), and pushing against the other handle just stalls.
  if (target < lo) {
    drag.logV = lo;
    drag.push = side === 'min' && lo === LOG_MIN ? clamp(drag.push + dx, -PUSH_MAX, 0) : 0;
  } else if (target > hi) {
    drag.logV = hi;
    drag.push = side === 'max' && hi === LOG_MAX ? clamp(drag.push + dx, 0, PUSH_MAX) : 0;
  } else {
    drag.logV = target;
    drag.push = 0;
  }

  applyValue();
}

/** Turns the current log position + zoom into the bound the readout shows. */
function applyValue() {
  const upp = liveUpp();
  const side = drag.side;
  const ref = Math.pow(10, drag.logV);
  drag.step = stepFor(ref, upp);
  drag.dec = decimalsFor(drag.step);
  const open = side === 'min' ? drag.push <= -CAP_PX : drag.push >= CAP_PX;
  const v = open ? (side === 'min' ? 0 : null) : boundAt(side, drag.logV, upp);
  if (side === 'min') drag.min = v; else drag.max = v;
}

/** Units-per-pixel as the gesture means it right now (⇧ included). */
function liveUpp() {
  const base = drag.upp || (SPAN / Math.max(1, drag.box.width));
  return base * (drag.shift ? SHIFT : 1);
}

function onDragEnd() {
  if (!drag) return;
  const { min, max } = drag;
  stopDrag();
  setRange(min, max);
}

function onDragCancel() {
  if (!drag) return;
  const { from } = drag;
  stopDrag();
  setRange(from.min, from.max);
}

function stopDrag() {
  cancelAnimationFrame(drag.raf);
  clearTimeout(drag.bloomTimer);
  const bloomed = drag.bloomed;
  drag = null;
  document.documentElement.classList.remove('pr-dragging');
  window.removeEventListener('pointermove', onDragMove);
  window.removeEventListener('pointerup', onDragEnd);
  window.removeEventListener('pointercancel', onDragCancel);
  window.removeEventListener('keydown', onDragKey, true);
  window.removeEventListener('keyup', onDragKey, true);

  // Fold away: the class drives the CSS exit, the timer only flips [hidden]
  // afterwards so a re-grab mid-fold re-opens the same node. A panel that never
  // bloomed was never visible — it just goes.
  panel.root.classList.remove('on');
  clearTimeout(panel.hideTimer);
  if (!bloomed) panel.root.hidden = true;
  else panel.hideTimer = setTimeout(() => { panel.root.hidden = true; }, 220);
}

// ---------------------------------------------------------------------------
// The frame
// ---------------------------------------------------------------------------

function frame() {
  if (!drag) return;
  drag.raf = requestAnimationFrame(frame);

  // The ruler is the reference for "zoomed all the way out": the whole domain,
  // edge to edge. Everything finer is a slide down from there.
  const out = SPAN / Math.max(1, drag.rSize[0]);
  const target = out * Math.pow(UPP_MIN / out, drag.zoom);
  // Only the zoom is animated — the value follows the pointer in the SAME frame
  // it moved, so the drag itself never lags behind the hand.
  drag.upp = drag.upp ? drag.upp + (target - drag.upp) * ZOOM_LERP : target;
  applyValue();

  // ONE geometry read per frame — every painter works off the same numbers, so
  // the ruler and the lens can never disagree about where the window is.
  const v = view();
  paintHeads();
  drawRuler(v);
  drawLens(v);
}

/**
 * The geometry every painter shares: where the handle stands on the ruler, and
 * the log→pixel mapping that follows from it.
 *
 * The handle IS the cursor — until the cursor comes near the rim, where it is
 * held back and the SCALE starts sliding underneath instead. That hold is what
 * makes a zoomed-in ruler navigable: you never run out of track, you just push
 * against the edge and the world scrolls past. Zoomed all the way out there is
 * nothing to scroll to, so the hold is zero and the handle reaches the ends.
 */
function view() {
  const W = Math.max(1, drag.rSize[0]);
  const upp = liveUpp();
  const hold = W * EDGE_HOLD * drag.zoom;
  const hx = clamp(drag.anchorX - drag.rulerLeft, hold, W - hold);
  // Pushing into a wall doesn't move the value, but the scale gives a little —
  // the rubber band is the only thing that says "you are pushing", and it is
  // what makes the open cap feel like a place you arrive at.
  const origin = drag.logV - hx * upp + drag.push * upp * PUSH_RUBBER;
  return { W, upp, hx, origin, xOf: lg => (lg - origin) / upp };
}

function paintHeads() {
  const p = panel;
  for (const side of ['min', 'max']) {
    const el = p.heads[side];
    const active = side === drag.side;
    const v = side === 'min' ? drag.min : drag.max;
    const open = side === 'min' ? v === 0 : v === null;
    el.classList.toggle('on', active);
    el.classList.toggle('open-cap', open);
    el.querySelector('.pr-head-val').textContent = fmtBound(v, side, active && !open ? drag.dec : null);
    el.querySelector('.pr-head-cap').textContent = open
      ? t(side === 'min' ? 'filter.range.nolow' : 'filter.range.nohigh')
      : t(side === 'min' ? 'filter.range.from' : 'filter.range.to');
  }

  p.zoomBar.style.width = (drag.zoom * 100).toFixed(1) + '%';
  p.zoomCap.textContent = t(drag.zoom < 0.5 ? 'filter.range.coarse' : 'filter.range.fine');
  p.zoomStep.textContent = `${t('filter.range.step')} ${fmtNumLoc(drag.step, drag.dec)}`;

  const hit = drag.prices.filter(p2 =>
    p2 >= drag.min && (drag.max === null || p2 <= drag.max)).length;
  p.count.textContent = t('filter.range.count')
    .replace('{n}', hit).replace('{m}', drag.prices.length);
}

// The ruler: the scale as it stands under the cursor. Everything on it is drawn
// from the SAME log→pixel mapping, so the zoom is a single number and the whole
// picture follows it.
function drawRuler(v) {
  const [ctx, W, H] = fitCanvas(panel.ruler, drag.rSize);
  if (W < 2) return;
  const P = drag.pal;
  const { upp, hx, origin, xOf } = v;
  const logL = origin;
  const logR = origin + W * upp;
  const baseY = H - BASE_UP;

  ctx.clearRect(0, 0, W, H);
  // Whole pixels, and only on a change: this is the one DOM write in the paint
  // loop, and a per-frame style set that says nothing new is a per-frame style
  // invalidation for nothing.
  const gx = Math.round(hx) + 'px';
  if (panel.cursor.style.left !== gx) panel.cursor.style.left = gx;

  // Past the domain edges there is nothing to pick — that ground is dead, and
  // standing on it IS the open cap.
  const xLo = xOf(LOG_MIN), xHi = xOf(LOG_MAX);
  ctx.fillStyle = P.bg1;
  if (xLo > 0) ctx.fillRect(0, 0, xLo, H);
  if (xHi < W) ctx.fillRect(xHi, 0, W - xHi, H);
  // …and it warms up as the push travels toward opening the bound, so the wall
  // announces what it is about to do instead of flipping without warning.
  const heat = clamp(Math.abs(drag.push) / CAP_PX, 0, 1);
  if (heat > 0) {
    ctx.globalAlpha = 0.05 + 0.15 * heat;
    ctx.fillStyle = P.amber;
    if (drag.push < 0 && xLo > 0) ctx.fillRect(0, 0, xLo, H);
    if (drag.push > 0 && xHi < W) ctx.fillRect(xHi, 0, W - xHi, H);
    ctx.globalAlpha = 1;
  }

  // The band, straight from the two bounds — open ends simply run off-canvas.
  const bL = drag.min === 0 ? -1e6 : xOf(Math.log10(drag.min));
  const bR = drag.max === null ? 1e6 : xOf(Math.log10(drag.max));
  const l = clamp(bL, 0, W), r = clamp(bR, 0, W);
  if (r > l) {
    ctx.globalAlpha = 0.13;
    ctx.fillStyle = P.amber;
    ctx.fillRect(l, 0, r - l, baseY);
    ctx.globalAlpha = 1;
  }

  drawTicks(ctx, W, baseY, xOf, logL, logR, P);
  drawRug(ctx, W, baseY, xOf, P);

  // Baseline + the domain end-caps.
  ctx.strokeStyle = P.line;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, baseY + 0.5); ctx.lineTo(W, baseY + 0.5);
  ctx.stroke();
  capMark(ctx, xLo, baseY, W, '0', drag.min === 0, P);
  capMark(ctx, xHi, baseY, W, '∞', drag.max === null, P);

  // The passive handle: a dashed post where it stands, or a chevron with its
  // value pinned to the edge it went out on.
  const other = drag.side === 'min' ? drag.max : drag.min;
  const oSide = drag.side === 'min' ? 'max' : 'min';
  const ox = other === null ? 1e6 : other === 0 ? -1e6 : xOf(Math.log10(other));
  ctx.font = '500 11px "JetBrains Mono", monospace';
  ctx.fillStyle = P.mute;
  if (ox > 8 && ox < W - 8) {
    ctx.save();
    ctx.setLineDash([3, 4]);
    ctx.strokeStyle = P.mute2;
    ctx.beginPath(); ctx.moveTo(ox, 4); ctx.lineTo(ox, baseY); ctx.stroke();
    ctx.restore();
    ctx.textAlign = 'center';
    ctx.fillText(fmtBound(other, oSide), ox, 13);
  } else {
    const right = ox >= W;
    ctx.textAlign = right ? 'right' : 'left';
    ctx.fillText((right ? '▸ ' : '◂ ') + fmtBound(other, oSide), right ? W - 6 : 6, 13);
  }

  // The handle post last — it belongs on top of everything it is picking.
  ctx.strokeStyle = P.amber;
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(hx, 2); ctx.lineTo(hx, baseY + 7);
  ctx.stroke();
  ctx.fillStyle = P.amber;
  ctx.beginPath();
  ctx.moveTo(hx - 5, 2); ctx.lineTo(hx + 5, 2); ctx.lineTo(hx, 10);
  ctx.closePath(); ctx.fill();
}

// Two tick regimes, because a log axis and a near-linear window want different
// ladders: zoomed OUT the decades carry it (1,2,…,9 per decade), zoomed IN the
// window is short enough that plain nice numbers read better — which is exactly
// where the Nachkommastellen appear.
function drawTicks(ctx, W, baseY, xOf, logL, logR, P) {
  const ticks = (logR - logL) > 1.15 ? logTicks(logL, logR) : linTicks(logL, logR);
  ctx.font = '400 10px "JetBrains Mono", monospace';
  ctx.textAlign = 'center';
  let lastX = -1e9;
  for (const tk of ticks) {
    const x = xOf(tk.log);
    if (x < -40 || x > W + 40) continue;
    // Minors fade in as they get room instead of popping into existence —
    // the whole point of an animated zoom is that nothing snaps.
    const alpha = tk.major ? 1 : clamp((x - lastX - 5) / 12, 0, 1);
    if (alpha <= 0.03) continue;
    lastX = x;
    ctx.globalAlpha = alpha;
    ctx.strokeStyle = tk.major ? P.mute : P.mute2;
    ctx.lineWidth = 1;
    const top = baseY - (tk.major ? 20 : 9);
    ctx.beginPath();
    ctx.moveTo(Math.round(x) + 0.5, top);
    ctx.lineTo(Math.round(x) + 0.5, baseY);
    ctx.stroke();
    if (tk.major) {
      ctx.fillStyle = P.txt;
      ctx.fillText(fmtTick(tk.v, tk.dec), x, baseY + 15);
    }
    ctx.globalAlpha = 1;
  }
}

function logTicks(logL, logR) {
  const out = [];
  for (let d = Math.floor(logL) - 1; d <= Math.ceil(logR) + 1; d++) {
    for (let k = 1; k <= 9; k++) {
      const lg = d + Math.log10(k);
      if (lg < logL - 0.3 || lg > logR + 0.3) continue;
      out.push({ v: k * Math.pow(10, d), log: lg, major: k === 1, dec: Math.max(0, -d) });
    }
  }
  return out;
}

function linTicks(logL, logR) {
  const v0 = Math.pow(10, logL), v1 = Math.pow(10, logR);
  const step = niceStep((v1 - v0) / 9);
  const dec = decimalsFor(step);
  const out = [];
  const k0 = Math.ceil(v0 / step);
  for (let i = 0; i < 300; i++) {
    const k = k0 + i;
    const v = k * step;
    if (v > v1) break;
    if (v <= 0) continue;
    out.push({ v, log: Math.log10(v), major: k % 5 === 0, dec });
  }
  return out;
}

// The prices actually on the wire, as a rug under the axis: where the band is
// worth putting is a fact about the data, not a guess.
function drawRug(ctx, W, baseY, xOf, P) {
  ctx.lineWidth = 1;
  ctx.globalAlpha = 0.5;
  for (const p of drag.prices) {
    const x = Math.round(xOf(Math.log10(p))) + 0.5;
    if (x < 0 || x > W) continue;
    const inBand = p >= drag.min && (drag.max === null || p <= drag.max);
    ctx.strokeStyle = inBand ? P.amber : P.mute2;
    ctx.beginPath();
    ctx.moveTo(x, baseY - 7);
    ctx.lineTo(x, baseY - 1);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;
}

function capMark(ctx, x, baseY, W, glyph, on, P) {
  if (x < -20 || x > W + 20) return;
  ctx.save();
  ctx.strokeStyle = on ? P.amber : P.line;
  ctx.lineWidth = on ? 2 : 1;
  ctx.beginPath();
  ctx.moveTo(Math.round(x) + 0.5, 2);
  ctx.lineTo(Math.round(x) + 0.5, baseY);
  ctx.stroke();
  ctx.fillStyle = on ? P.amber : P.mute;
  ctx.font = '600 12px "JetBrains Mono", monospace';
  ctx.textAlign = glyph === '0' ? 'right' : 'left';
  ctx.fillText(glyph, x + (glyph === '0' ? -5 : 5), baseY + 15);
  ctx.restore();
}

// The lens: the WHOLE domain at a glance with a bright window on the slice the
// ruler is currently showing. Without it a zoomed-in ruler is a keyhole — this
// is the thing that makes the zoom legible instead of disorienting.
function drawLens(v) {
  const [ctx, W, H] = fitCanvas(panel.lens, drag.lSize);
  if (W < 2) return;
  const P = drag.pal;
  const { W: rw, upp, origin } = v;
  const xf = f => 8 + f * (W - 16);
  const fOf = lg => clamp((lg - LOG_MIN) / SPAN, 0, 1);
  const axisY = H - 12;

  ctx.clearRect(0, 0, W, H);

  ctx.strokeStyle = P.line;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(xf(0), axisY + 0.5); ctx.lineTo(xf(1), axisY + 0.5);
  ctx.stroke();

  // Decade ticks + the two open caps.
  ctx.font = '400 8px "JetBrains Mono", monospace';
  ctx.textAlign = 'center';
  for (let d = LOG_MIN; d <= LOG_MAX; d++) {
    const x = xf(fOf(d));
    ctx.strokeStyle = P.mute2;
    ctx.beginPath(); ctx.moveTo(x, axisY - 3); ctx.lineTo(x, axisY); ctx.stroke();
    ctx.fillStyle = P.mute;
    ctx.fillText(fmtTick(Math.pow(10, d), Math.max(0, -d)), x, axisY + 9);
  }

  // The band across the full domain.
  const bl = xf(fracOf(drag.min, 'min')), br = xf(fracOf(drag.max, 'max'));
  ctx.globalAlpha = 0.18;
  ctx.fillStyle = P.amber;
  ctx.fillRect(bl, 4, Math.max(1, br - bl), axisY - 7);
  ctx.globalAlpha = 1;

  // The rug again, compressed — the market's shape in one strip.
  ctx.globalAlpha = 0.45;
  ctx.strokeStyle = P.mute2;
  for (const p of drag.prices) {
    const x = Math.round(xf(fOf(Math.log10(p)))) + 0.5;
    ctx.beginPath(); ctx.moveTo(x, axisY - 6); ctx.lineTo(x, axisY - 1); ctx.stroke();
  }
  ctx.globalAlpha = 1;

  // The window itself: this rectangle visibly closes down as the pull goes in
  // and opens back up when it comes back.
  const wl = xf(fOf(origin));
  const wr = xf(fOf(origin + rw * upp));
  ctx.strokeStyle = P.amber;
  ctx.lineWidth = 1.5;
  ctx.strokeRect(Math.max(wl, 1), 2.5, Math.max(2, wr - wl), axisY - 2.5);
  ctx.globalAlpha = 0.08;
  ctx.fillStyle = P.amber;
  ctx.fillRect(Math.max(wl, 1), 2.5, Math.max(2, wr - wl), axisY - 2.5);
  ctx.globalAlpha = 1;
}
