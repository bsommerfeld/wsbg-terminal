// EUR/USD detail widget — the elaborate version of the FJ header's rate badge,
// rendered as a plannable widget (grid card / focus fullscreen). Fed by the same
// `eurusd` socket payload:
// { rate, previousRate, direction, source, fetchedAt,
//   previousClose?, dayHigh?, dayLow?, week52High?, week52Low?,
//   ecbRate?, ecbDate?, spark?: [[epochMs, rate], …], history?: [[epochMs, rate], …] }
//
// Anatomy (top to bottom): hero — the flag pair around the big rate, the
// day-change pill (vs Yahoo's previous close) and the intraday sparkline;
// then the stat tiles (day range, 52-week range, ECB reference fix, the
// inverse 1 USD rate) and the ~1y ECB daily chart with a hover crosshair.
// The grid card shows only the hero core (rate + delta + spark), everything
// else is focus-view content (widget-grid.css trims, like the F&G tile).
//
// The rate is a level, not a diverging score, so the history line wears the
// single amber accent; polarity (up/down on the day) lives ONLY in the delta
// pill and the sparkline tint — the same green/red the quote strips speak.
// Every detail field is optional: a missing block simply doesn't render.

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { restartAnimation } from '../chrome/anim.js';
import { FLAG_EU, FLAG_US } from './eurusd.js';

const FLASH_MS = 2000;

const num = v => typeof v === 'number' && isFinite(v);
const fmtRate = (v, digits = 4) =>
  new Intl.NumberFormat(currentLang(), { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(v);
const fmtPct = v =>
  `${v > 0 ? '+' : ''}${new Intl.NumberFormat(currentLang(), { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v)} %`;

export function renderEurUsdDetail(host, q) {
  if (!host) return;
  if (!q || !num(q.rate)) {
    // First paint before a quote lands: shimmering skeleton, fully replaced by
    // the first real payload.
    if (!host.querySelector('.fx-rate')) {
      host.innerHTML = `
        <div class="fx-rate-row">${FLAG_EU}<span class="fx-rate loading" aria-hidden="true">0,0000</span>${FLAG_US}</div>
        <p class="fx-waiting">${escapeHtml(t('fx.detail.waiting'))}</p>`;
    }
    return;
  }

  const spark = pairList(q.spark);
  const history = pairList(q.history);
  const prevClose = num(q.previousClose) ? q.previousClose : null;
  const dayPct = prevClose ? ((q.rate - prevClose) / prevClose) * 100 : null;
  const dir = dayPct == null ? 'flat' : dayPct > 0.005 ? 'up' : dayPct < -0.005 ? 'down' : 'flat';

  host.innerHTML = `
    <div class="fx-rate-row">${FLAG_EU}<span class="fx-rate">${escapeHtml(fmtRate(q.rate))}</span>${FLAG_US}</div>
    ${deltaHtml(dayPct, dir)}
    ${sparkHtml(spark, dir)}
    <div class="fx-hero-meta">
      ${updatedHtml(q.fetchedAt)}
      ${q.source === 'FRANKFURTER' ? `<span class="fx-src-note">${escapeHtml(t('fx.detail.ecbFallback'))}</span>` : ''}
    </div>
    ${statsHtml(q)}
    ${history.length >= 2 ? chartHtml(history) : ''}
    <p class="fx-attrib">${attribHtml()}</p>`;

  // Tick-to-tick pulse on the big figure — same green/red dwell as the badge.
  if (q.direction === 'UP' || q.direction === 'DOWN') {
    const rateEl = host.querySelector('.fx-rate');
    if (rateEl) restartAnimation(rateEl, q.direction === 'UP' ? 'flash-up' : 'flash-down', FLASH_MS);
  }

  if (history.length >= 2) wireChartHover(host, history);
}

function pairList(a) {
  return Array.isArray(a)
    ? a.filter(p => Array.isArray(p) && p.length === 2 && isFinite(p[0]) && isFinite(p[1]))
    : [];
}

function deltaHtml(dayPct, dir) {
  if (dayPct == null) return '';
  const arrow = dir === 'up' ? '▲' : dir === 'down' ? '▼' : '•';
  return `<div class="fx-delta ${dir}">${arrow} ${escapeHtml(fmtPct(dayPct))} ${escapeHtml(t('fx.detail.today'))}</div>`;
}

function updatedHtml(fetchedAt) {
  const ts = fetchedAt ? new Date(fetchedAt) : null;
  if (!ts || isNaN(ts)) return '';
  const time = new Intl.DateTimeFormat(currentLang(), { hour: '2-digit', minute: '2-digit' }).format(ts);
  return `<span class="fx-updated">${escapeHtml(t('fx.detail.updated'))} ${time}</span>`;
}

/* ---- intraday sparkline (hero) ---- */

// Bigger sibling of the quote strip's micro-spark: area + line + end dot,
// tinted by the day direction. viewBox matches the CSS box 1:1.
function sparkHtml(spark, dir) {
  if (spark.length < 2) return '';
  const W = 220, H = 48, pad = 3;
  let min = Infinity, max = -Infinity;
  for (const [, v] of spark) { if (v < min) min = v; if (v > max) max = v; }
  const range = (max - min) || 1;
  const n = spark.length;
  const pts = spark.map(([, v], i) => {
    const x = (i / (n - 1)) * W;
    const y = H - pad - ((v - min) / range) * (H - 2 * pad);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const lastY = pts[n - 1].split(',')[1];
  return `
    <svg class="fx-spark ${dir}" viewBox="0 0 ${W} ${H}" aria-hidden="true">
      <polygon class="area" points="0,${H} ${pts.join(' ')} ${W},${H}"/>
      <polyline class="line" points="${pts.join(' ')}"/>
      <circle class="dot" cx="${W}" cy="${lastY}" r="2.4"/>
    </svg>`;
}

/* ---- stat tiles ---- */

function statsHtml(q) {
  const tiles = [];
  if (num(q.dayLow) && num(q.dayHigh)) {
    tiles.push(tile(t('fx.detail.dayRange'), `${fmtRate(q.dayLow)} – ${fmtRate(q.dayHigh)}`));
  }
  if (num(q.week52Low) && num(q.week52High)) {
    tiles.push(tile(t('fx.detail.w52Range'), `${fmtRate(q.week52Low)} – ${fmtRate(q.week52High)}`));
  }
  if (num(q.ecbRate)) {
    const date = q.ecbDate ? fmtDate(q.ecbDate) : '';
    tiles.push(tile(t('fx.detail.ecbRef'), fmtRate(q.ecbRate), date));
  }
  tiles.push(tile(t('fx.detail.inverse'), fmtRate(1 / q.rate)));
  return `<div class="fx-stats">${tiles.join('')}</div>`;
}

function tile(label, value, hint = '') {
  return `<div class="fx-stat">
    <span class="fx-stat-label">${escapeHtml(label)}</span>
    <span class="fx-stat-value">${escapeHtml(value)}</span>
    ${hint ? `<span class="fx-stat-hint">${escapeHtml(hint)}</span>` : ''}
  </div>`;
}

function fmtDate(iso) {
  const d = new Date(iso);
  if (isNaN(d)) return '';
  return new Intl.DateTimeFormat(currentLang(), { day: 'numeric', month: 'short' }).format(d);
}

function attribHtml() {
  return `${escapeHtml(t('fx.detail.attrib.live'))} ·
    <a href="https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/eurofxref-graph-usd.en.html"
       target="_blank" rel="noopener">${escapeHtml(t('fx.detail.attrib.ecb'))}</a>`;
}

/* ---- the 1y ECB history chart ---- */

const C = { w: 640, h: 210, top: 14, right: 56, bottom: 26, left: 46 };

function scales(history) {
  const t0 = history[0][0];
  const t1 = history[history.length - 1][0];
  let min = Infinity, max = -Infinity;
  for (const [, v] of history) { if (v < min) min = v; if (v > max) max = v; }
  // A level series never sits on a 0..100 scale — pad the observed band a bit
  // so the line breathes and the extremes don't kiss the frame.
  const padV = (max - min || 0.01) * 0.08;
  const lo = min - padV, hi = max + padV;
  const x = ms => C.left + ((ms - t0) / Math.max(1, t1 - t0)) * (C.w - C.left - C.right);
  const y = v => C.top + (1 - (v - lo) / (hi - lo)) * (C.h - C.top - C.bottom);
  return { x, y, t0, t1, lo, hi };
}

function chartHtml(history) {
  const { x, y, t0, t1, lo, hi } = scales(history);
  const pts = history.map(p => `${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`);
  const last = history[history.length - 1];
  const lastX = x(last[0]);
  const lastY = y(last[1]);
  const area = `M ${pts[0]} L ${pts.join(' L ')} L ${lastX.toFixed(1)},${(C.h - C.bottom).toFixed(1)} L ${C.left},${(C.h - C.bottom).toFixed(1)} Z`;

  // Three horizontal guides across the padded band, ~5 month ticks along x.
  const yTicks = [0.25, 0.5, 0.75].map(f => lo + (hi - lo) * f);
  const fmt = new Intl.DateTimeFormat(currentLang(), { month: 'short' });
  const ticks = [];
  for (let i = 1; i <= 5; i++) {
    const ms = t0 + ((t1 - t0) * i) / 6;
    ticks.push(`<text class="fx-x-tick" x="${x(ms).toFixed(1)}" y="${C.h - 8}"
        text-anchor="middle">${escapeHtml(fmt.format(new Date(ms)))}</text>`);
  }

  return `
  <div class="fx-chart-block">
    <div class="fx-chart-title">${escapeHtml(t('fx.detail.chartTitle'))}</div>
    <div class="fx-chart-wrap">
      <svg class="fx-chart" viewBox="0 0 ${C.w} ${C.h}" preserveAspectRatio="xMidYMid meet">
        ${yTicks.map(v => `
          <line class="fx-gridline" x1="${C.left}" y1="${y(v).toFixed(1)}"
                x2="${C.w - C.right}" y2="${y(v).toFixed(1)}"/>
          <text class="fx-y-tick" x="${C.left - 7}" y="${(y(v) + 3.5).toFixed(1)}"
                text-anchor="end">${escapeHtml(fmtRate(v, 3))}</text>`).join('')}
        ${ticks.join('')}
        <path class="fx-area" d="${area}"/>
        <path class="fx-line" d="M ${pts.join(' L ')}" fill="none"/>
        <g class="fx-cross" hidden>
          <line class="fx-cross-line" y1="${C.top}" y2="${C.h - C.bottom}"/>
          <circle class="fx-cross-dot" r="4.5"/>
        </g>
        <circle class="fx-last-dot" cx="${lastX.toFixed(1)}" cy="${lastY.toFixed(1)}" r="4.5"/>
        <text class="fx-last-label" x="${(lastX + 9).toFixed(1)}"
              y="${(lastY + 4).toFixed(1)}">${escapeHtml(fmtRate(last[1], 3))}</text>
      </svg>
      <div class="fx-tip" hidden></div>
    </div>
  </div>`;
}

// Crosshair + tooltip: nearest sample under the pointer; date + rate.
function wireChartHover(host, history) {
  const svg = host.querySelector('.fx-chart');
  const wrap = host.querySelector('.fx-chart-wrap');
  const tip = host.querySelector('.fx-tip');
  if (!svg || !wrap || !tip) return;
  const cross = svg.querySelector('.fx-cross');
  const crossLine = svg.querySelector('.fx-cross-line');
  const crossDot = svg.querySelector('.fx-cross-dot');
  const { x, y } = scales(history);
  const fmt = new Intl.DateTimeFormat(currentLang(), { day: 'numeric', month: 'short', year: 'numeric' });

  svg.addEventListener('pointermove', e => {
    const box = svg.getBoundingClientRect();
    const scale = box.width / C.w;
    const cx = (e.clientX - box.left) / scale;
    let best = 0;
    let bestDist = Infinity;
    for (let i = 0; i < history.length; i++) {
      const d = Math.abs(x(history[i][0]) - cx);
      if (d < bestDist) { bestDist = d; best = i; }
    }
    const [ms, v] = history[best];
    const px = x(ms);
    const py = y(v);
    cross.hidden = false;
    crossLine.setAttribute('x1', px.toFixed(1));
    crossLine.setAttribute('x2', px.toFixed(1));
    crossDot.setAttribute('cx', px.toFixed(1));
    crossDot.setAttribute('cy', py.toFixed(1));

    tip.hidden = false;
    tip.innerHTML = `<span class="fx-tip-date">${escapeHtml(fmt.format(new Date(ms)))}</span>
      <span class="fx-tip-val">${escapeHtml(fmtRate(v))}</span>`;
    const wrapBox = wrap.getBoundingClientRect();
    const tipX = px * scale + (box.left - wrapBox.left);
    const flip = tipX > wrapBox.width * 0.72;
    tip.style.left = `${tipX + (flip ? -12 : 12)}px`;
    tip.style.top = `${py * scale + (box.top - wrapBox.top)}px`;
    tip.classList.toggle('flip', flip);
  });
  svg.addEventListener('pointerleave', () => {
    cross.hidden = true;
    tip.hidden = true;
  });
}

// Paint the skeleton as soon as the DOM is ready (mirrors fg-detail's self-init)
// so the widget never opens as a blank pane before the first quote lands.
function paintDetailSkeleton() {
  const host = document.getElementById('eurusd-detail');
  if (host && !host.firstElementChild) renderEurUsdDetail(host, null);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', paintDetailSkeleton);
} else {
  paintDetailSkeleton();
}
