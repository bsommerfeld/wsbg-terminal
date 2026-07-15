// Shared interaction layer for the server-rendered report figures (weather
// report + KI-DD): the frozen SVGs stay the archive/PDF truth — everything
// here is a UI overlay derived from what the SVG ALREADY contains (worldmap.js
// tooltip conventions, spark-hover generalized).
//
//   wireFigureHover(container)  — data marks (bars, dots, tiles) answer the
//     cursor with a stroke emphasis plus a tooltip whose content is strictly
//     the nearest <text> labels inside the SVG (the figures direct-label their
//     values, so the honest readout is already in the picture). Line series
//     (polylines) carry NO per-point value text, so they get emphasis only —
//     nothing is interpolated or invented.
//   wireFigureJumps(container)  — the figure's title row jumps to the report
//     section the figure belongs to (the nearest preceding heading — figures
//     are injected right after their section's prose by ordinal).
//
// All animations are one-shot and event-driven (the OSR paint rule): the
// emphasis is a class toggle, the flash a finite keyframe.

import { t } from '../i18n/i18n.js';

/** The two figure hosts this layer serves (weather report + KI-DD report). */
const FIGURES = 'figure.weather-figure, figure.dd-figure';

/** How far (client px) the cursor may sit from a mark and still catch it. */
const SNAP = 14;

/**
 * Makes every data mark inside the container's figures hoverable. Safe to call
 * after every re-render (fresh nodes) and on lightbox CLONES: stale tooltip
 * nodes / emphasis classes copied by cloneNode are stripped first.
 */
export function wireFigureHover(container, opts = {}) {
  if (!container) return;
  container.querySelectorAll(opts.figures || FIGURES).forEach(fig => {
    // Clone hygiene: a lightbox clone carries the original's tip/emphasis.
    fig.querySelectorAll('.fh-tip').forEach(el => el.remove());
    fig.querySelectorAll('.fh-hover').forEach(el => el.classList.remove('fh-hover'));

    const svg = fig.querySelector('svg');
    if (!svg) return;
    let tip = null;      // lazily created shared tooltip, one per figure
    let marked = null;   // the currently emphasized mark
    let cands = null;    // candidate marks, resolved once per wiring

    const candidates = () => {
      if (cands) return cands;
      const sr = svg.getBoundingClientRect();
      cands = [...svg.querySelectorAll('rect, circle, path, polygon, polyline')]
        .filter(el => isDataMark(el, sr));
      return cands;
    };

    const clear = () => {
      if (marked) { marked.classList.remove('fh-hover'); marked = null; }
      if (tip) tip.hidden = true;
    };

    svg.addEventListener('pointermove', e => {
      const mark = nearestMark(candidates(), e.clientX, e.clientY);
      if (!mark) { clear(); return; }
      if (mark !== marked) {
        if (marked) marked.classList.remove('fh-hover');
        mark.classList.add('fh-hover');
        marked = mark;
        // Line series have no per-point value text — emphasis only.
        const label = mark.tagName.toLowerCase() === 'polyline'
          ? '' : nearestLabels(svg, mark);
        if (label) {
          if (!tip) {
            tip = document.createElement('div');
            tip.className = 'fh-tip';
            fig.appendChild(tip);
          }
          tip.textContent = label; // textContent: SVG-derived text, never markup
          tip.hidden = false;
        } else if (tip) {
          tip.hidden = true;
        }
      }
      if (tip && !tip.hidden) placeTip(tip, fig, e);
    });
    svg.addEventListener('pointerleave', clear);
  });
}

/**
 * Wires the figure title rows as jump affordances to their report section
 * (nearest preceding heading sibling). No target heading → no affordance.
 */
export function wireFigureJumps(container, opts = {}) {
  if (!container) return;
  container.querySelectorAll(opts.figures || FIGURES).forEach(fig => {
    const cap = fig.querySelector('figcaption');
    if (!cap || cap.classList.contains('fh-jump')) return;
    const heading = precedingHeading(fig);
    if (!heading) return;
    cap.classList.add('fh-jump');
    cap.title = t('figure.jump');
    cap.setAttribute('role', 'button');
    cap.tabIndex = 0;
    const jump = e => {
      // The weather figure's own click opens the lightbox — the title row
      // is the section jump, so it must not bubble into that.
      e.stopPropagation();
      heading.scrollIntoView({ behavior: 'smooth', block: 'start' });
      flash(heading);
    };
    cap.addEventListener('click', jump);
    cap.addEventListener('keydown', e => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); jump(e); }
    });
  });
}

/**
 * Strips the jump affordance from cloned captions (lightbox clones keep the
 * class/attributes but lose the listeners — a dead affordance would lie).
 */
export function clearFigureJumps(container) {
  if (!container) return;
  container.querySelectorAll('figcaption.fh-jump').forEach(cap => {
    cap.classList.remove('fh-jump');
    cap.removeAttribute('title');
    cap.removeAttribute('role');
    cap.removeAttribute('tabindex');
  });
}

/**
 * The heading the figure belongs to: figures are injected right AFTER their
 * section's prose (ordinal anchoring), so the nearest preceding heading
 * sibling IS the figure's section.
 */
function precedingHeading(fig) {
  for (let el = fig.previousElementSibling; el; el = el.previousElementSibling) {
    if (/^H[123]$/.test(el.tagName)) return el;
  }
  return null;
}

// ---- mark detection ----------------------------------------------------------

/**
 * A data mark by the house SVG grammar (DeepDiveCharts/WeatherCharts): dots
 * are <circle>, bars/tiles are filled <rect>/<path>, series are <polyline>.
 * Grid/axis hairlines are <line> (not selected) or fill-less paths; area
 * washes under a line ride opacity ≈ 0.1; a rect covering the whole canvas is
 * background, not data.
 */
function isDataMark(el, svgRect) {
  const tag = el.tagName.toLowerCase();
  if (tag === 'circle' || tag === 'polyline') return true;
  const fill = el.getAttribute('fill');
  if (!fill || fill === 'none') return false;
  const opacity = Number(el.getAttribute('opacity') || '1');
  if (!(opacity >= 0.5)) return false;
  const r = el.getBoundingClientRect();
  if (r.width >= svgRect.width * 0.9 && r.height >= svgRect.height * 0.9) return false;
  return true;
}

/**
 * The mark nearest the pointer, within SNAP px of its box. Point-shaped marks
 * always beat a line series (a polyline's box spans the whole plot, so it only
 * answers when the pointer is inside it and nothing closer speaks).
 */
function nearestMark(marks, x, y) {
  let best = null, bestD = SNAP;
  let line = null, lineHit = false;
  for (const el of marks) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue; // display:none / unpainted
    const d = boxDistance(x, y, r);
    if (el.tagName.toLowerCase() === 'polyline') {
      if (d === 0) { line = el; lineHit = true; }
      continue;
    }
    if (d <= bestD) { bestD = d; best = el; }
  }
  return best || (lineHit ? line : null);
}

/**
 * Up to two distinct <text> contents nearest the mark's box — the value label
 * the figures place directly at their marks, usually plus the category/date
 * label of the same slot. Nothing within reach → no tooltip (honesty rule:
 * the tooltip never shows text that isn't in the SVG).
 */
function nearestLabels(svg, mark) {
  const sr = svg.getBoundingClientRect();
  const radius = Math.max(24, sr.width * 0.05);
  const mr = mark.getBoundingClientRect();
  const found = [];
  for (const tx of svg.querySelectorAll('text')) {
    const s = (tx.textContent || '').trim();
    if (!s) continue;
    const r = tx.getBoundingClientRect();
    const dx = Math.max(r.left - mr.right, mr.left - r.right, 0);
    const dy = Math.max(r.top - mr.bottom, mr.top - r.bottom, 0);
    const d = Math.hypot(dx, dy);
    if (d <= radius) found.push({ s, d });
  }
  found.sort((a, b) => a.d - b.d);
  const out = [];
  for (const f of found) {
    if (!out.includes(f.s)) out.push(f.s);
    if (out.length === 2) break;
  }
  return out.join(' · ');
}

/** Pointer-to-box distance in client px (0 when the pointer is inside). */
function boxDistance(x, y, r) {
  const dx = Math.max(r.left - x, 0, x - r.right);
  const dy = Math.max(r.top - y, 0, y - r.bottom);
  return Math.hypot(dx, dy);
}

/** Near-cursor placement clamped to the figure box (worldmap place() grammar). */
function placeTip(tip, fig, e) {
  const r = fig.getBoundingClientRect();
  let x = e.clientX - r.left + 14;
  let y = e.clientY - r.top + 14;
  const tw = tip.offsetWidth, th = tip.offsetHeight;
  if (x + tw > r.width - 6) x = e.clientX - r.left - tw - 14;
  if (y + th > r.height - 6) y = e.clientY - r.top - th - 14;
  tip.style.left = `${Math.max(6, x)}px`;
  tip.style.top = `${Math.max(6, y)}px`;
}

/** One-shot highlight pulse on a jump target; removes itself (finite anim). */
function flash(el) {
  el.classList.remove('fh-flash');
  void el.offsetWidth; // restart the finite animation
  el.classList.add('fh-flash');
  el.addEventListener('animationend', () => el.classList.remove('fh-flash'),
      { once: true });
}
