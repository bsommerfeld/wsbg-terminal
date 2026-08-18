// Chart primitives for the debug dashboard.
//
// Form follows the question, not the wish for a chart:
//   fill level          -> meter()        (a bar against its ceiling)
//   declared vs measured-> pairMeter()    (never one number alone)
//   run-by-run yield    -> columns()      (failures dip below the baseline)
//   events over time    -> timeline()     (lanes, markers, real time axis)
//   split of a duration -> splitBars()    (wait vs work, 2px surface gap)
//   ranked composition  -> hbars()
// Plain numbers stay plain numbers — see dom.stat().
//
// Colours are the app's own status tokens (green/amber/red) plus one accent
// (blue) and neutral ink; nothing here invents a categorical palette. Every
// mark carries a <title> so the exact value is one hover away, and every chart
// on a panel has a table twin beside it, so no value is gated behind a hover.

import { el, svgEl, ms, clock, num } from './dom.js';

const TONE = { ok: 'var(--green)', warn: 'var(--amber)', bad: 'var(--red)', info: 'var(--blue)', mute: 'var(--mute-2)' };
const toneColor = t => TONE[t] || TONE.info;

/**
 * Fill level: value against a ceiling. `tone` is the fill's severity; the
 * track is the same bar's unfilled remainder, one step off the surface.
 */
export function meter(value, max, { tone = 'info', caption, right } = {}) {
  const wrap = el('div', 'dbg-meter');
  if (caption || right) {
    const cap = el('div', 'dbg-meter-cap');
    cap.appendChild(el('span', null, caption || ''));
    if (right) cap.appendChild(el('span', 'dbg-meter-right', right));
    wrap.appendChild(cap);
  }
  const track = el('div', 'dbg-meter-track');
  const fill = el('div', 'dbg-meter-fill');
  const ratio = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  fill.style.width = `${(ratio * 100).toFixed(2)}%`;
  fill.style.background = toneColor(tone);
  track.appendChild(fill);
  track.title = `${num(value)} / ${num(max)} (${(ratio * 100).toFixed(1)} %)`;
  wrap.appendChild(track);
  return wrap;
}

/**
 * The dashboard's most important rule made a component: a declared number and
 * the measured one, on ONE shared scale, side by side. `limit` draws the hard
 * ceiling that actually applies (e.g. the GGUF truncation point) as a marked
 * line across both bars — a number the app never asked for but gets anyway.
 */
export function pairMeter({ declaredLabel, declared, measuredLabel, measured, limit, limitLabel, note, noteTone }) {
  const wrap = el('div', 'dbg-pair');
  const scale = Math.max(declared || 0, measured || 0, limit || 0) || 1;
  for (const [label, value, tone] of [
    [declaredLabel, declared, 'mute'],
    [measuredLabel, measured, measured && limit && measured > limit ? 'bad' : 'info']]) {
    const row = el('div', 'dbg-pair-row');
    row.appendChild(el('span', 'dbg-pair-label', label));
    const track = el('div', 'dbg-meter-track');
    const fill = el('div', 'dbg-meter-fill');
    fill.style.width = `${((value || 0) / scale * 100).toFixed(2)}%`;
    fill.style.background = toneColor(tone);
    track.appendChild(fill);
    if (limit && limit < scale) {
      const mark = el('div', 'dbg-pair-limit');
      mark.style.left = `${(limit / scale * 100).toFixed(2)}%`;
      mark.title = `${limitLabel || 'limit'}: ${num(limit)}`;
      track.appendChild(mark);
    }
    row.appendChild(track);
    row.appendChild(el('span', 'dbg-pair-value', num(value)));
    wrap.appendChild(row);
  }
  if (limit) {
    const legend = el('div', 'dbg-pair-legend');
    legend.appendChild(el('span', 'dbg-pair-legend-mark'));
    legend.appendChild(el('span', null, `${limitLabel || 'limit'} · ${num(limit)}`));
    wrap.appendChild(legend);
  }
  if (note) {
    const n = el('p', 'dbg-note', note);
    if (noteTone) n.dataset.tone = noteTone;
    wrap.appendChild(n);
  }
  return wrap;
}

/**
 * Run-by-run yield. A failed run is `-1` by contract and is drawn as a red
 * tick BELOW the zero line — a dead source reads as a red comb, not as zeros.
 */
export function columns(values, { height = 44, label } = {}) {
  const w = 5, gap = 2;
  const n = values.length;
  const width = Math.max(1, n * (w + gap));
  const top = height - 12;             // 12px reserved below the baseline
  const peak = Math.max(1, ...values.map(v => (v > 0 ? v : 0)));
  const svg = svgEl('svg', { class: 'dbg-columns', width, height, viewBox: `0 0 ${width} ${height}`, role: 'img' });
  if (label) svg.appendChild(svgEl('title')).textContent = label;
  const base = svgEl('line', { x1: 0, y1: top, x2: width, y2: top, class: 'dbg-axis' });
  svg.appendChild(base);
  values.forEach((v, i) => {
    const x = i * (w + gap);
    let rect;
    if (v < 0) {
      rect = svgEl('rect', { x, y: top + 2, width: w, height: 8, rx: 2, fill: TONE.bad });
    } else if (v === 0) {
      rect = svgEl('rect', { x, y: top - 3, width: w, height: 3, rx: 1.5, fill: TONE.warn });
    } else {
      const h = Math.max(3, Math.round((v / peak) * (top - 4)));
      rect = svgEl('rect', { x, y: top - h, width: w, height: h, rx: 2, fill: TONE.ok });
    }
    rect.appendChild(svgEl('title')).textContent = v < 0 ? 'failed run' : `${v} items`;
    svg.appendChild(rect);
  });
  return svg;
}

/**
 * Events on a real time axis. `lanes` is [{name, events:[{atMs, durationMs,
 * tone, label}]}]; the x scale spans `spanMs` back from now, so distance
 * between markers IS the interval between passes and a stalled lane shows as
 * a gap. Markers carry their duration as width (min 3px, so a 40 ms pass is
 * still visible) and their outcome as colour.
 */
export function timeline(lanes, { spanMs = 15 * 60_000, now = Date.now(), height = 26, labelWidth = 132 } = {}) {
  const wrap = el('div', 'dbg-timeline');
  const start = now - spanMs;
  const axis = el('div', 'dbg-tl-axis');
  axis.style.marginLeft = `${labelWidth}px`;
  for (let i = 0; i <= 4; i++) {
    const tick = el('span', 'dbg-tl-tick', i === 4 ? 'now' : ms(spanMs * (1 - i / 4)) + ' ago');
    tick.style.left = `${(i / 4 * 100).toFixed(2)}%`;
    axis.appendChild(tick);
  }
  wrap.appendChild(axis);

  // Marker width is duration, scaled against the longest event currently in
  // view — an absolute ms-to-pixel scale would make every pass a hairline in
  // a 6 h window and a slab in a 5 m one.
  const inView = lanes.flatMap(l => (l.events || []).filter(e => e.atMs >= start && e.atMs <= now + 1000));
  const maxDur = Math.max(1, ...inView.map(e => e.durationMs || 0));

  for (const lane of lanes) {
    const row = el('div', 'dbg-tl-row');
    const name = el('span', 'dbg-tl-name', lane.name);
    name.style.width = `${labelWidth}px`;
    name.title = lane.name;
    row.appendChild(name);
    const track = el('div', 'dbg-tl-track');
    track.style.height = `${height}px`;
    for (let i = 1; i < 4; i++) {
      const grid = el('div', 'dbg-tl-grid');
      grid.style.left = `${(i / 4 * 100).toFixed(2)}%`;
      track.appendChild(grid);
    }
    for (const ev of lane.events || []) {
      if (ev.atMs < start || ev.atMs > now + 1000) continue;
      const left = (ev.atMs - start) / spanMs * 100;
      const mark = el('div', 'dbg-tl-mark');
      mark.style.left = `${left.toFixed(3)}%`;
      mark.style.width = `${(4 + 20 * Math.sqrt((ev.durationMs || 0) / maxDur)).toFixed(1)}px`;
      mark.style.background = toneColor(ev.tone || 'info');
      if (ev.weight != null) mark.style.height = `${Math.round(6 + 14 * Math.min(1, ev.weight))}px`;
      mark.title = ev.label || '';
      track.appendChild(mark);
    }
    if (lane.due) {  // a booked, still-pending appointment: hollow marker
      const left = (lane.due.atMs - start) / spanMs * 100;
      if (left <= 100) {
        const mark = el('div', 'dbg-tl-mark dbg-tl-due');
        mark.style.left = `${Math.max(0, left).toFixed(3)}%`;
        mark.title = lane.due.label || 'next due';
        track.appendChild(mark);
      }
    }
    row.appendChild(track);
    wrap.appendChild(row);
  }
  if (!lanes.length) wrap.appendChild(el('p', 'dbg-empty', 'no events in this window'));
  return wrap;
}

/**
 * One duration split into its parts (wait vs. work). Two segments, a 2px gap
 * in the surface colour between them — never a stroke.
 */
export function splitBars(rows, { legend } = {}) {
  const wrap = el('div', 'dbg-splits');
  if (legend) {
    const l = el('div', 'dbg-legend');
    for (const [text, tone] of legend) {
      const item = el('span', 'dbg-legend-item');
      const dot = el('span', 'dbg-legend-dot');
      dot.style.background = toneColor(tone);
      item.appendChild(dot);
      item.appendChild(el('span', null, text));
      l.appendChild(item);
    }
    wrap.appendChild(l);
  }
  const peak = Math.max(1, ...rows.map(r => r.parts.reduce((a, p) => a + p.value, 0)));
  for (const r of rows) {
    const row = el('div', 'dbg-split-row');
    row.appendChild(el('span', 'dbg-split-label', r.label));
    const bar = el('div', 'dbg-split-bar');
    for (const p of r.parts) {
      const seg = el('div', 'dbg-split-seg');
      seg.style.width = `${(p.value / peak * 100).toFixed(2)}%`;
      seg.style.background = toneColor(p.tone);
      seg.title = `${p.name}: ${ms(p.value)}`;
      bar.appendChild(seg);
    }
    row.appendChild(bar);
    row.appendChild(el('span', 'dbg-split-value', r.value));
    wrap.appendChild(row);
  }
  return wrap;
}

/**
 * Ranked composition: label, bar, value. One hue for one series. `format`
 * renders the value (counts are numbers, footprints are bytes).
 */
export function hbars(items, { tone = 'info', max, format = num } = {}) {
  const wrap = el('div', 'dbg-hbars');
  const peak = max || Math.max(1, ...items.map(i => i.value));
  for (const it of items) {
    const row = el('div', 'dbg-hbar-row');
    const label = el('span', 'dbg-hbar-label', it.label);
    label.title = it.title || it.label;
    row.appendChild(label);
    const track = el('div', 'dbg-hbar-track');
    const fill = el('div', 'dbg-hbar-fill');
    fill.style.width = `${(it.value / peak * 100).toFixed(2)}%`;
    fill.style.background = toneColor(it.tone || tone);
    track.appendChild(fill);
    row.appendChild(track);
    row.appendChild(el('span', 'dbg-hbar-value', format(it.value)));
    wrap.appendChild(row);
  }
  if (!items.length) wrap.appendChild(el('p', 'dbg-empty', 'nothing yet'));
  return wrap;
}

/**
 * A step line of one measured level over time (basin size, tokens, …).
 * The y-range follows the DATA, not the ceiling: a pool that breathes between
 * 640 and 690 would be a flat line against a 1200 ceiling and the movement —
 * the only thing this plot is for — would be invisible. The ceiling is named
 * in the caption instead.
 */
export function stepLine(points, { spanMs = 15 * 60_000, now = Date.now(), tone = 'info', ceiling } = {}) {
  const width = 720, height = 90, padTop = 8, padBottom = 18;
  const svg = svgEl('svg', { class: 'dbg-stepline', viewBox: `0 0 ${width} ${height}`, preserveAspectRatio: 'none', role: 'img' });
  const start = now - spanMs;
  const inWindow = points.filter(p => p.atMs >= start);
  const values = inWindow.map(p => p.value);
  const hi = Math.max(1, ...values), lo = Math.min(...values, hi);
  const pad = Math.max(1, (hi - lo) * 0.15);
  const top = hi + pad, bottom = Math.max(0, lo - pad);
  const x = t => (t - start) / spanMs * width;
  const y = v => padTop + (1 - (v - bottom) / (top - bottom || 1)) * (height - padTop - padBottom);
  svg.appendChild(svgEl('line', { x1: 0, y1: height - padBottom, x2: width, y2: height - padBottom, class: 'dbg-axis' }));
  if (inWindow.length) {
    let d = `M ${x(inWindow[0].atMs).toFixed(1)} ${y(inWindow[0].value).toFixed(1)}`;
    for (const p of inWindow.slice(1)) d += ` L ${x(p.atMs).toFixed(1)} ${y(p.value).toFixed(1)}`;
    svg.appendChild(svgEl('path', { d, fill: 'none', stroke: toneColor(tone), 'stroke-width': 2, 'stroke-linejoin': 'round', 'vector-effect': 'non-scaling-stroke' }));
    const last = inWindow[inWindow.length - 1];
    const dot = svgEl('circle', { cx: x(last.atMs), cy: y(last.value), r: 4, fill: toneColor(tone), stroke: 'var(--bg)', 'stroke-width': 2 });
    dot.appendChild(svgEl('title')).textContent = `${num(last.value)} at ${clock(last.atMs)}`;
    svg.appendChild(dot);
  }
  const box = el('div', 'dbg-plot');
  box.appendChild(svg);
  const cap = el('div', 'dbg-plot-cap');
  cap.appendChild(el('span', null, `${ms(spanMs)} ago`));
  cap.appendChild(el('span', null, inWindow.length
    ? `range ${num(lo)} – ${num(hi)}${ceiling ? ` of ${num(ceiling)}` : ''}`
    : 'nothing in this window'));
  cap.appendChild(el('span', null, 'now'));
  box.appendChild(cap);
  return box;
}

/** Countdown/elapsed bar for a booked appointment or a cooling host. */
export function countdown(leftMs, spanMs, { tone = 'warn' } = {}) {
  const wrap = el('div', 'dbg-countdown');
  const track = el('div', 'dbg-meter-track');
  const fill = el('div', 'dbg-meter-fill');
  fill.style.width = `${Math.max(0, Math.min(1, leftMs / (spanMs || 1))) * 100}%`;
  fill.style.background = toneColor(tone);
  track.appendChild(fill);
  wrap.appendChild(track);
  wrap.appendChild(el('span', 'dbg-countdown-label', ms(Math.max(0, leftMs))));
  return wrap;
}
