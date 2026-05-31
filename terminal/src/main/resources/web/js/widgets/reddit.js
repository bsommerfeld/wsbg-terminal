// Renders the AI-generated headline list.
//
// Driven entirely by the `headlines` payload pushed from
// HeadlinePublisher. New entries (clusterIds not seen in the previous
// render) get the .new-row class so the gold flash animation plays
// once — same visual cue the refresh button used to trigger
// repository-wide.

import { highlightTickers } from '../format/ticker.js';
import { colorizeSignedNumbers } from '../format/numbers.js';
import { fmtClock } from '../format/time.js';

const HIGHLIGHT_CLASS = {
  IMPORTANT: 'highlight-important',
};

// Per-widget seen-keys cache — populated on first render, diffed on
// subsequent renders to detect which rows are new.
const seenKeys = new WeakMap();

export function renderHeadlines(host, items) {
  if (!host) return;
  if (!items || items.length === 0) {
    host.innerHTML = `
      <div class="empty-cook" aria-label="Köche kochen noch">
        <img src="/icons/cook.webp" alt="">
      </div>`;
    seenKeys.set(host, new Set());
    return;
  }

  const prev = seenKeys.get(host) || new Set();
  const isFirstRender = prev.size === 0;

  // Suppress the "new" flash on first paint — every row would flash
  // simultaneously, which is more chaotic than communicative.
  host.innerHTML = items.map(h => toRow(h, !isFirstRender && !prev.has(h.clusterId))).join('');

  seenKeys.set(host, new Set(items.map(h => h.clusterId)));
}

function toRow(h, isNew) {
  // highlightTickers escapes its input internally and emits <span>s.
  const head = colorizeSignedNumbers(highlightTickers(h.headline, h.tickerSymbol));

  const classes = ['row'];
  const cls = HIGHLIGHT_CLASS[h.highlight];
  if (cls) classes.push(cls);
  if (isNew) classes.push('new-row');

  const time = fmtClock(h.createdAt);
  const meta = buildMeta(h);

  // Subtle provenance mark: when the line was enriched with Yahoo Finance
  // data, a faintly glowing Yahoo logo sits bottom-right, well clear of the
  // tags. Hover reveals the disclaimer. No mark on pure 1:1 Reddit lines.
  const yahoo = h.usedYahoo
    ? `<div class="yahoo-mark" title="Enthält zusätzliche Informationen von Yahoo Finance News"></div>`
    : '';

  return `<div class="${classes.join(' ')}">
    <div class="time">${time}</div>
    <div class="body">
      <div class="head">${head}</div>
      ${meta ? `<div class="meta">${meta}</div>` : ''}
    </div>
    ${yahoo}
  </div>`;
}

function buildMeta(h) {
  // Meta row, left → right: a live quote strip (sparkline + price + day%)
  // for the primary ticker, then one coloured sentiment chip, then
  // optional neutral sector chips. The quote leads because hard market
  // data is the strongest scan-by signal; sentiment is the ONLY chip
  // that gets colour (the room's mood); sectors stay neutral context.
  const parts = [];
  const quote = buildQuote(h.snapshot);
  if (quote) parts.push(quote);
  if (h.sentiment && h.sentiment !== 'NEUTRAL') {
    const cls = 'sent-' + h.sentiment.toLowerCase();
    parts.push(`<span class="tag sentiment ${cls}">${escapeText(h.sentiment.toLowerCase())}</span>`);
  }
  (h.sectors || []).forEach(s => parts.push(`<span class="tag sector">${escapeText(s)}</span>`));
  return parts.join('');
}

// Live quote strip for the headline's primary ticker. Renders only when
// PublishHeadlineTool attached a Yahoo snapshot with a usable price:
//   [ micro-sparkline ]  214.25  +0.78%
// The sparkline, price-change and the whole strip's accent colour key
// off the day-move direction (green up / red down / muted flat). Deeper
// numbers (day range, 52-week range, volume) ride along in the native
// hover title so the row stays uncluttered but the data is one hover away.
function buildQuote(s) {
  if (!s || typeof s.price !== 'number' || !isFinite(s.price)) return '';
  const chg = typeof s.changePercent === 'number' ? s.changePercent : null;
  const dir = chg == null ? sparkDir(s.spark)
            : chg > 0.005 ? 'up' : chg < -0.005 ? 'down' : 'flat';

  const spark = buildSparkline(s.spark, dir);
  const price = `<span class="q-price">${escapeText(fmtPrice(s.price, s.currency))}</span>`;
  const chgTxt = chg == null ? ''
    : `<span class="q-chg ${dir}">${chg >= 0 ? '+' : ''}${chg.toFixed(2)}%</span>`;

  return `<span class="quote ${dir}" title="${escapeText(buildQuoteTitle(s))}">`
       + `${spark}${price}${chgTxt}</span>`;
}

// Builds an inline SVG micro-sparkline from the intraday close series.
// viewBox matches the CSS pixel box 1:1 so the end-point dot stays round.
// Three layers: a faint area fill, the line, and a dot on the last point.
function buildSparkline(spark, dir) {
  if (!Array.isArray(spark) || spark.length < 2) return '';
  const W = 56, H = 18, pad = 2;
  let min = Infinity, max = -Infinity;
  for (const v of spark) { if (v < min) min = v; if (v > max) max = v; }
  const range = (max - min) || 1;
  const n = spark.length;

  const pts = spark.map((v, i) => {
    const x = (i / (n - 1)) * W;
    const y = H - pad - ((v - min) / range) * (H - 2 * pad);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const line = pts.join(' ');
  const area = `0,${H} ${line} ${W},${H}`;
  const lastX = (W).toFixed(1);
  const lastY = pts[n - 1].split(',')[1];

  return `<svg class="spark ${dir}" viewBox="0 0 ${W} ${H}" aria-hidden="true">`
       + `<polygon class="area" points="${area}"/>`
       + `<polyline class="line" points="${line}"/>`
       + `<circle class="dot" cx="${lastX}" cy="${lastY}" r="1.7"/>`
       + `</svg>`;
}

function sparkDir(spark) {
  if (!Array.isArray(spark) || spark.length < 2) return 'flat';
  const d = spark[spark.length - 1] - spark[0];
  return d > 0 ? 'up' : d < 0 ? 'down' : 'flat';
}

// Price with a currency glyph for the majors; pennystocks (< 1) get more
// decimals so a 0.0042 → 0.0061 move is still legible.
function fmtPrice(p, ccy) {
  const sym = ccy === 'USD' ? '$' : ccy === 'EUR' ? '€' : ccy === 'GBP' ? '£' : '';
  const v = Math.abs(p) < 1 ? p.toFixed(4) : p.toFixed(2);
  return sym + v;
}

function buildQuoteTitle(s) {
  const L = [];
  if (s.symbol) L.push(s.symbol);
  if (isNum(s.dayLow) && isNum(s.dayHigh)) L.push(`Tag ${fmtNum(s.dayLow)}–${fmtNum(s.dayHigh)}`);
  if (isNum(s.fiftyTwoWeekLow) && isNum(s.fiftyTwoWeekHigh)) L.push(`52W ${fmtNum(s.fiftyTwoWeekLow)}–${fmtNum(s.fiftyTwoWeekHigh)}`);
  if (isNum(s.volume)) L.push(`Vol ${fmtVol(s.volume)}`);
  return L.join('  ·  ');
}

function isNum(v) { return typeof v === 'number' && isFinite(v); }
function fmtNum(v) { return Math.abs(v) < 1 ? v.toFixed(4) : v.toFixed(2); }
function fmtVol(v) {
  if (v >= 1e9) return (v / 1e9).toFixed(1) + 'B';
  if (v >= 1e6) return (v / 1e6).toFixed(1) + 'M';
  if (v >= 1e3) return (v / 1e3).toFixed(1) + 'K';
  return String(v);
}

function escapeText(s) {
  return s.replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}
