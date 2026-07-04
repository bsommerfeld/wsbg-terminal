// Live quote strip — a self-contained "market snapshot chip".
//
// Renders the primary instrument's snapshot inline at the head of a headline's
// meta row:  [ micro-sparkline ]  214.25  +0.78%
// The sparkline, price-change and the whole strip's accent colour key off the
// day-move direction (green up / red down / muted flat). Deeper numbers (day
// range, 52-week range, volume) ride along in the native hover title so the row
// stays uncluttered but the data is one hover away.
//
// Zero coupling to headlines — it reads only the `snapshot` payload shape, so
// the same chip can back the watchlist/archive UIs on the roadmap.

import { escapeHtml } from '../format/escape.js';
import { fmtPrice, fmtNum, fmtVol, isNum } from '../format/numbers.js';
import { t } from '../i18n/i18n.js';

// Renders the strip for a snapshot, or '' when there's no usable price.
export function quoteStripHtml(s) {
  if (!s || typeof s.price !== 'number' || !isFinite(s.price)) return '';
  const chg = typeof s.changePercent === 'number' ? s.changePercent : null;
  const dir = chg == null ? sparkDir(s.spark)
            : chg > 0.005 ? 'up' : chg < -0.005 ? 'down' : 'flat';

  const stale = isStaleQuote(s);
  const spark = buildSparkline(s.spark, dir);
  const price = `<span class="q-price">${escapeHtml(fmtPrice(s.price, s.currency))}</span>`;
  const chgTxt = chg == null ? ''
    : `<span class="q-chg ${dir}">${chg >= 0 ? '+' : ''}${chg.toFixed(2)}%</span>`;

  // Off-hours (every venue closed) just dims the strip — no label; the hover title
  // explains it. With the live chain (L&S evening, NASDAQ after-hours) this is rare.
  return `<span class="quote ${dir}${stale ? ' stale' : ''}" title="${escapeHtml(buildQuoteTitle(s))}">`
       + `${spark}${price}${chgTxt}</span>`;
}

// Dim a quote ONLY when the server says so. priceStale is GAP-aware (computed against the
// CET clock): a US/index quote on its last close is NOT dimmed while the German market is
// open — only the dead-of-night gap dims. We must NOT re-derive this from marketTime here,
// or every overseas/index price would read "closed" all day. (Older payloads without the
// flag fall back to the 30-min timestamp check.)
function isStaleQuote(s) {
  if (!s) return false;
  if (typeof s.priceStale === 'boolean') return s.priceStale;
  if (typeof s.marketTime !== 'number' || s.marketTime <= 0) return false;
  return (Date.now() / 1000 - s.marketTime) > 1800;
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

function buildQuoteTitle(s) {
  const L = [];
  if (s.symbol) L.push(s.symbol);
  if (isNum(s.dayLow) && isNum(s.dayHigh)) L.push(`${t('quote.day')} ${fmtNum(s.dayLow)}–${fmtNum(s.dayHigh)}`);
  if (isNum(s.fiftyTwoWeekLow) && isNum(s.fiftyTwoWeekHigh)) L.push(`52W ${fmtNum(s.fiftyTwoWeekLow)}–${fmtNum(s.fiftyTwoWeekHigh)}`);
  if (isNum(s.volume)) L.push(`Vol ${fmtVol(s.volume)}`);
  if (s.source) L.push(`${t('quote.source')} ${s.source}`);   // which venue priced it
  if (isStaleQuote(s)) L.push(t('quote.stale'));
  return L.join('  ·  ');
}
