// Fear & Greed detail widget — the elaborate version of the header badge's
// mini gauge, rendered as the third plannable widget (grid card / focus
// fullscreen). Fed by the same `fear-greed` socket payload:
// { score, rating, band, previousClose, previousWeek?, previousMonth?,
//   previousYear?, fetchedAt, history: [[epochMs, score], …],
//   components?: [{ key, score, rating, band }, …],
//   crypto?: { score, rating, band, previousClose?, fetchedAt,
//              history: [[epochMs, score], …] } }.
//
// Anatomy (top to bottom): hero gauge with the score inside + band pill +
// delta vs yesterday, the five band chips (the scale legend — identity by
// label, never color alone), the week/month/year look-backs, the seven
// sub-indicator bars behind the composite, the ~1y history line chart with a
// hover crosshair + tooltip, and the crypto counterpart (alternative.me) as
// its own compact block. The score is a diverging measure (fear ↔ greed
// around a neutral middle), so the line wears a y-mapped red→amber→green
// gradient — the same semantics as the gauge arc. All colors resolve through
// CSS vars (fear-greed.css), so light/dark are covered for free. Every extra
// block is optional: a missing payload field simply doesn't render.

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';

const BANDS = [
  { key: 'EXTREME_FEAR', from: 0, to: 25 },
  { key: 'FEAR', from: 25, to: 45 },
  { key: 'NEUTRAL', from: 45, to: 55 },
  { key: 'GREED', from: 55, to: 75 },
  { key: 'EXTREME_GREED', from: 75, to: 100 },
];

// The seven CNN sub-indicators → their label keys. Also the whitelist: an
// unknown component key from the payload is skipped, never rendered raw.
const COMPONENT_LABELS = {
  market_momentum_sp500: 'fg.comp.momentum',
  stock_price_strength: 'fg.comp.strength',
  stock_price_breadth: 'fg.comp.breadth',
  put_call_options: 'fg.comp.putcall',
  market_volatility_vix: 'fg.comp.vix',
  junk_bond_demand: 'fg.comp.junk',
  safe_haven_demand: 'fg.comp.safehaven',
};

const BAND_KEYS = new Set(BANDS.map(b => b.key));

function bandOf(score) {
  if (score < 25) return 'EXTREME_FEAR';
  if (score < 45) return 'FEAR';
  if (score <= 55) return 'NEUTRAL';
  if (score <= 75) return 'GREED';
  return 'EXTREME_GREED';
}

// A payload band string is only trusted when it names one of the five known
// bands (it lands in a data- attribute); anything else falls back to the score.
function safeBand(raw, score) {
  return BAND_KEYS.has(raw) ? raw : bandOf(score);
}

export function renderFearGreedDetail(host, d) {
  if (!host) return;
  const ok = d && typeof d.score === 'number' && isFinite(d.score);
  if (!ok) {
    // First paint before a reading lands: neutral skeleton, nothing flickers out
    // later because every real payload fully re-renders.
    if (!host.querySelector('.fg-hero')) {
      host.innerHTML = `
        <div class="fg-hero loading">${gaugeSvg(50, 'NEUTRAL', true)}</div>
        <p class="fg-waiting">${escapeHtml(t('fg.detail.waiting'))}</p>`;
    }
    return;
  }

  const score = Math.max(0, Math.min(100, d.score));
  const band = safeBand(d.band, score);
  const prev = typeof d.previousClose === 'number' && isFinite(d.previousClose)
    ? Math.max(0, Math.min(100, d.previousClose)) : null;
  const history = Array.isArray(d.history)
    ? d.history.filter(p => Array.isArray(p) && p.length === 2
        && isFinite(p[0]) && isFinite(p[1]))
    : [];

  host.innerHTML = `
    <div class="fg-hero">
      ${gaugeSvg(score, band, false)}
      <div class="fg-hero-meta">
        <span class="fg-band-pill" data-band="${band}">${escapeHtml(bandLabel(band))}</span>
        ${deltaHtml(score, prev)}
        ${updatedHtml(d.fetchedAt)}
      </div>
    </div>
    <div class="fg-bands" role="list">
      ${BANDS.map(b => `
        <span class="fg-band-chip${b.key === band ? ' current' : ''}" role="listitem" data-band="${b.key}">
          <span class="fg-band-swatch" aria-hidden="true"></span>
          <span class="fg-band-name">${escapeHtml(bandLabel(b.key))}</span>
          <span class="fg-band-range">${b.from}–${b.to}</span>
        </span>`).join('')}
    </div>
    ${prevsHtml(d)}
    ${componentsHtml(d.components)}
    ${history.length >= 2 ? chartHtml(history, band) : ''}
    ${cryptoHtml(d.crypto)}
    <p class="fg-attrib">${escapeHtml(t('fg.detail.source'))}
      <a href="https://edition.cnn.com/markets/fear-and-greed" target="_blank" rel="noopener">CNN Fear &amp; Greed Index</a>
      · ${escapeHtml(t('fg.detail.scope'))}</p>`;

  if (history.length >= 2) wireChartHover(host, history);
}

/* ---- the week / month / year look-backs CNN ships beside the close ---- */

function prevsHtml(d) {
  const items = [
    ['fg.prev.week', d.previousWeek],
    ['fg.prev.month', d.previousMonth],
    ['fg.prev.year', d.previousYear],
  ].filter(([, v]) => typeof v === 'number' && isFinite(v));
  if (!items.length) return '';
  return `<div class="fg-prevs">${items.map(([key, v]) => {
    const s = Math.max(0, Math.min(100, v));
    return `<span class="fg-prev" data-band="${bandOf(s)}">
      <span class="fg-prev-label">${escapeHtml(t(key))}</span>
      <span class="fg-prev-dot" aria-hidden="true"></span>
      <span class="fg-prev-val">${Math.round(s)}</span>
    </span>`;
  }).join('')}</div>`;
}

/* ---- the seven sub-indicators behind the composite ---- */

function componentsHtml(components) {
  const list = Array.isArray(components)
    ? components.filter(c => c && COMPONENT_LABELS[c.key]
        && typeof c.score === 'number' && isFinite(c.score))
    : [];
  if (!list.length) return '';
  return `
  <div class="fg-comps">
    <div class="fg-comps-title">${escapeHtml(t('fg.comps.title'))}</div>
    ${list.map(c => {
      const s = Math.max(0, Math.min(100, c.score));
      const band = safeBand(c.band, s);
      return `
      <div class="fg-comp" data-band="${band}">
        <span class="fg-comp-name">${escapeHtml(t(COMPONENT_LABELS[c.key]))}</span>
        <span class="fg-comp-track" aria-hidden="true">
          <span class="fg-comp-fill" style="width:${s.toFixed(0)}%"></span>
        </span>
        <span class="fg-comp-val">${Math.round(s)}</span>
        <span class="fg-comp-band">${escapeHtml(bandLabel(band))}</span>
      </div>`;
    }).join('')}
  </div>`;
}

/* ---- the crypto counterpart (alternative.me), one compact block ---- */

function cryptoHtml(c) {
  if (!c || typeof c.score !== 'number' || !isFinite(c.score)) return '';
  const score = Math.max(0, Math.min(100, c.score));
  const band = safeBand(c.band, score);
  const prev = typeof c.previousClose === 'number' && isFinite(c.previousClose)
    ? Math.max(0, Math.min(100, c.previousClose)) : null;
  const history = Array.isArray(c.history)
    ? c.history.filter(p => Array.isArray(p) && p.length === 2
        && isFinite(p[0]) && isFinite(p[1]))
    : [];
  return `
  <div class="fg-crypto" data-band="${band}">
    <div class="fg-crypto-title">${escapeHtml(t('fg.crypto.title'))}</div>
    <div class="fg-crypto-row">
      <span class="fg-crypto-score">${Math.round(score)}</span>
      <span class="fg-band-pill" data-band="${band}">${escapeHtml(bandLabel(band))}</span>
      ${deltaHtml(score, prev)}
      ${cryptoSparkHtml(history)}
    </div>
    <p class="fg-crypto-attrib">${escapeHtml(t('fg.detail.source'))}
      <a href="https://alternative.me/crypto/fear-and-greed-index/" target="_blank" rel="noopener">alternative.me</a>
      · ${escapeHtml(t('fg.crypto.scope'))}</p>
  </div>`;
}

// ~45d mini trend, single line in the current band's color (a compact rider,
// not a second full chart — the big diverging chart stays the composite's).
function cryptoSparkHtml(history) {
  if (history.length < 2) return '';
  const W = 180, H = 40, pad = 3;
  let min = Infinity, max = -Infinity;
  for (const [, v] of history) { if (v < min) min = v; if (v > max) max = v; }
  const range = (max - min) || 1;
  const n = history.length;
  const pts = history.map(([, v], i) => {
    const x = (i / (n - 1)) * W;
    const y = H - pad - ((v - min) / range) * (H - 2 * pad);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const lastY = pts[n - 1].split(',')[1];
  return `
    <svg class="fg-crypto-spark" viewBox="0 0 ${W} ${H}" aria-hidden="true">
      <polyline class="line" points="${pts.join(' ')}"/>
      <circle class="dot" cx="${W}" cy="${lastY}" r="2.4"/>
    </svg>`;
}

function bandLabel(band) {
  return t('fg.' + band);
}

function deltaHtml(score, prev) {
  if (prev == null) return '';
  const diff = Math.round(score) - Math.round(prev);
  const cls = diff > 0 ? 'up' : diff < 0 ? 'down' : 'flat';
  const arrow = diff > 0 ? '▲' : diff < 0 ? '▼' : '•';
  const num = diff > 0 ? `+${diff}` : String(diff);
  return `<span class="fg-delta ${cls}">${arrow} ${diff === 0
    ? escapeHtml(t('fg.detail.unchanged'))
    : `${num} ${escapeHtml(t('fg.detail.sinceYesterday'))}`}</span>`;
}

function updatedHtml(fetchedAt) {
  const ts = fetchedAt ? new Date(fetchedAt) : null;
  if (!ts || isNaN(ts)) return '';
  const time = new Intl.DateTimeFormat(currentLang(), { hour: '2-digit', minute: '2-digit' }).format(ts);
  return `<span class="fg-updated">${escapeHtml(t('fg.detail.updated'))} ${time}</span>`;
}

/* ---- the hero gauge ---- */

// Semicircle geometry: score 0 → 180° (left), 100 → 0° (right).
const G = { cx: 130, cy: 130, r: 104, w: 15 };

function polar(angleDeg, r) {
  const a = (Math.PI * angleDeg) / 180;
  return [G.cx + r * Math.cos(a), G.cy - r * Math.sin(a)];
}

function arcPath(fromScore, toScore, r) {
  // 1.2° angular padding on each side = the 2px surface gap between segments.
  const a0 = 180 - fromScore * 1.8 - 1.2;
  const a1 = 180 - toScore * 1.8 + 1.2;
  const [x0, y0] = polar(a0, r);
  const [x1, y1] = polar(a1, r);
  return `M ${x0.toFixed(1)} ${y0.toFixed(1)} A ${r} ${r} 0 0 1 ${x1.toFixed(1)} ${y1.toFixed(1)}`;
}

function gaugeSvg(score, band, loading) {
  const needleDeg = 180 - score * 1.8;
  const [nx, ny] = polar(needleDeg, G.r - G.w - 12);
  const [lx, ly] = polar(180, G.r + 12);
  const [rx, ry] = polar(0, G.r + 12);
  // The score sits BELOW the hub, clear of the needle's whole sweep.
  return `
  <svg class="fg-gauge-big" viewBox="0 0 260 196" role="img"
       aria-label="Fear &amp; Greed: ${Math.round(score)}">
    ${BANDS.map(b => `<path class="fg-arc" data-band="${b.key}"
        d="${arcPath(b.from, b.to, G.r)}" stroke-width="${G.w}" fill="none"/>`).join('')}
    <line class="fg-needle-big" x1="${G.cx}" y1="${G.cy}"
          x2="${nx.toFixed(1)}" y2="${ny.toFixed(1)}"/>
    <circle class="fg-hub-big" cx="${G.cx}" cy="${G.cy}" r="5"/>
    <text class="fg-axis-end" x="${lx.toFixed(1)}" y="${(ly + 14).toFixed(1)}" text-anchor="middle">0</text>
    <text class="fg-axis-end" x="${rx.toFixed(1)}" y="${(ry + 14).toFixed(1)}" text-anchor="middle">100</text>
    <text class="fg-big-val${loading ? ' loading' : ''}" x="${G.cx}" y="${G.cy + 56}"
          text-anchor="middle">${Math.round(score)}</text>
  </svg>`;
}

/* ---- the 1y history chart ---- */

const C = { w: 640, h: 210, top: 14, right: 46, bottom: 26, left: 34 };

function scales(history) {
  const t0 = history[0][0];
  const t1 = history[history.length - 1][0];
  const x = ms => C.left + ((ms - t0) / Math.max(1, t1 - t0)) * (C.w - C.left - C.right);
  const y = v => C.top + (1 - v / 100) * (C.h - C.top - C.bottom);
  return { x, y, t0, t1 };
}

function chartHtml(history, band) {
  const { x, y, t0, t1 } = scales(history);
  const pts = history.map(p => `${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`);
  const last = history[history.length - 1];
  const lastX = x(last[0]);
  const lastY = y(last[1]);
  const area = `M ${pts[0]} L ${pts.join(' L ')} L ${lastX.toFixed(1)},${y(0).toFixed(1)} L ${x(t0).toFixed(1)},${y(0).toFixed(1)} Z`;

  // ~5 month ticks across the year, formatted in the active language.
  const fmt = new Intl.DateTimeFormat(currentLang(), { month: 'short' });
  const ticks = [];
  for (let i = 1; i <= 5; i++) {
    const ms = t0 + ((t1 - t0) * i) / 6;
    ticks.push(`<text class="fg-x-tick" x="${x(ms).toFixed(1)}" y="${C.h - 8}"
        text-anchor="middle">${escapeHtml(fmt.format(new Date(ms)))}</text>`);
  }

  return `
  <div class="fg-chart-block">
    <div class="fg-chart-title">${escapeHtml(t('fg.detail.chartTitle'))}</div>
    <div class="fg-chart-wrap">
      <svg class="fg-chart" viewBox="0 0 ${C.w} ${C.h}" preserveAspectRatio="xMidYMid meet">
        <defs>
          <linearGradient id="fg-line-grad" x1="0" y1="${y(100)}" x2="0" y2="${y(0)}"
                          gradientUnits="userSpaceOnUse">
            <stop offset="0" class="fg-stop-greed"/>
            <stop offset="0.5" class="fg-stop-neutral"/>
            <stop offset="1" class="fg-stop-fear"/>
          </linearGradient>
          <linearGradient id="fg-area-grad" x1="0" y1="${y(100)}" x2="0" y2="${y(0)}"
                          gradientUnits="userSpaceOnUse">
            <stop offset="0" class="fg-stop-greed" stop-opacity="0.10"/>
            <stop offset="0.5" class="fg-stop-neutral" stop-opacity="0.06"/>
            <stop offset="1" class="fg-stop-fear" stop-opacity="0.10"/>
          </linearGradient>
        </defs>
        ${[25, 50, 75].map(v => `
          <line class="fg-gridline" x1="${C.left}" y1="${y(v).toFixed(1)}"
                x2="${C.w - C.right}" y2="${y(v).toFixed(1)}"/>
          <text class="fg-y-tick" x="${C.left - 7}" y="${(y(v) + 3.5).toFixed(1)}"
                text-anchor="end">${v}</text>`).join('')}
        ${ticks.join('')}
        <path class="fg-area" d="${area}" fill="url(#fg-area-grad)"/>
        <path class="fg-line" d="M ${pts.join(' L ')}" stroke="url(#fg-line-grad)" fill="none"/>
        <g class="fg-cross" hidden>
          <line class="fg-cross-line" y1="${C.top}" y2="${C.h - C.bottom}"/>
          <circle class="fg-cross-dot" r="4.5"/>
        </g>
        <circle class="fg-last-dot" data-band="${band}" cx="${lastX.toFixed(1)}"
                cy="${lastY.toFixed(1)}" r="4.5"/>
        <text class="fg-last-label" data-band="${band}" x="${(lastX + 9).toFixed(1)}"
              y="${(lastY + 4).toFixed(1)}">${Math.round(last[1])}</text>
      </svg>
      <div class="fg-tip" hidden></div>
    </div>
  </div>`;
}

// Paint the neutral skeleton as soon as the DOM is ready (mirrors the header
// badge's self-init) so the widget never opens as a blank pane before the
// first reading lands; the first real payload fully replaces it.
function paintDetailSkeleton() {
  const host = document.getElementById('fg-detail');
  if (host && !host.firstElementChild) renderFearGreedDetail(host, null);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', paintDetailSkeleton);
} else {
  paintDetailSkeleton();
}

// Crosshair + tooltip: nearest sample under the pointer; date, score, band.
function wireChartHover(host, history) {
  const svg = host.querySelector('.fg-chart');
  const wrap = host.querySelector('.fg-chart-wrap');
  const tip = host.querySelector('.fg-tip');
  const cross = svg.querySelector('.fg-cross');
  const crossLine = svg.querySelector('.fg-cross-line');
  const crossDot = svg.querySelector('.fg-cross-dot');
  const { x, y } = scales(history);
  const fmt = new Intl.DateTimeFormat(currentLang(), { day: 'numeric', month: 'short', year: 'numeric' });

  svg.addEventListener('pointermove', e => {
    const box = svg.getBoundingClientRect();
    // viewBox is uniformly scaled (meet): map client x → chart x.
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
    tip.innerHTML = `<span class="fg-tip-date">${escapeHtml(fmt.format(new Date(ms)))}</span>
      <span class="fg-tip-val">${Math.round(v)}</span>
      <span class="fg-tip-band" data-band="${bandOf(v)}">${escapeHtml(bandLabel(bandOf(v)))}</span>`;
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
