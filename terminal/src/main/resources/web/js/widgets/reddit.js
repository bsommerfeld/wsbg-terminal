// Renders the AI-generated headline list.
//
// Driven entirely by the `headlines` payload pushed from
// HeadlinePublisher. New entries (headlines not seen in the previous
// render) get the .new-row class so the gold flash animation plays
// once — the "frisch aufgetaucht" cue. Keyed per-headline
// (clusterId + createdAt), NOT per-cluster: a cluster publishes many
// headlines over its life, so keying on clusterId alone would flash
// only the first one and silently skip every follow-up.

import { highlightTickers } from '../format/ticker.js';
import { colorizeSignedNumbers } from '../format/numbers.js';
import { fmtClock } from '../format/time.js';

const HIGHLIGHT_CLASS = {
  IMPORTANT: 'highlight-important',
};

// Per-widget seen-keys cache — populated on first render, diffed on
// subsequent renders to detect which rows are new.
const seenKeys = new WeakMap();

// The list is two stacked layers, newest at the top:
//   liveItems    — the live 24h wire (HeadlinePublisher pushes, replaces wholesale)
//   archiveItems — older headlines pulled from the permanent archive as the user
//                  scrolls DOWN past the wire (prepended below, never flash).
// Both are kept here so a live push re-renders without dropping the scroll-back rows.
let liveItems = [];
let archiveItems = [];
let hostRef = null;
let socketRef = null;
let loadingMore = false;
let exhausted = false;     // archive returned a short/empty page → nothing older left
const PAGE = 50;

export function renderHeadlines(host, items) {
  if (!host) return;
  hostRef = host;
  liveItems = items || [];
  if (liveItems.length === 0 && archiveItems.length === 0) {
    host.innerHTML = `
      <div class="empty-cook" aria-label="Köche kochen noch">
        <img src="/icons/cook.webp" alt="">
      </div>`;
    seenKeys.set(host, new Set());
    return;
  }
  renderCombined();
}

/** Wires the scroll-to-bottom → load-older-archive behaviour (call once). */
export function initHeadlineScroll(host, socket) {
  if (!host) return;
  hostRef = host;
  socketRef = socket;
  host.addEventListener('scroll', () => {
    if (loadingMore || exhausted) return;
    // within ~1.5 rows of the bottom → fetch the next older page
    if (host.scrollTop + host.clientHeight >= host.scrollHeight - 120) loadMore();
  });
}

/** Appends an older archive page (from the `archive-results` page command). */
export function appendArchivePage(items) {
  loadingMore = false;
  if (!items || items.length === 0) { exhausted = true; return; }
  const known = new Set([...liveItems, ...archiveItems].map(rowKey));
  const fresh = items.filter(h => !known.has(rowKey(h)));
  if (fresh.length === 0) { exhausted = true; return; }
  if (items.length < PAGE) exhausted = true; // last page
  archiveItems = archiveItems.concat(fresh);
  renderCombined();
}

function loadMore() {
  if (!socketRef) return;
  const all = [...liveItems, ...archiveItems];
  if (all.length === 0) return;
  const oldest = all.reduce((m, h) => Math.min(m, h.createdAt), Infinity);
  loadingMore = true;
  socketRef.send('archive', { command: 'page', before: oldest, limit: PAGE, requestId: 'scrollback' });
}

// Renders live (top) + archive (below) as one list, de-duped by row key.
// Only genuinely-new LIVE rows flash; archive rows never do. Scroll position is
// preserved so a live push or an appended page doesn't yank the viewport.
function renderCombined() {
  const host = hostRef;
  if (!host) return;
  const byKey = new Map();
  for (const h of [...liveItems, ...archiveItems]) byKey.set(rowKey(h), h);
  const combined = [...byKey.values()];

  const prev = seenKeys.get(host) || new Set();
  const isFirstRender = prev.size === 0;
  const liveKeys = new Set(liveItems.map(rowKey));
  const scroll = host.scrollTop;
  host.innerHTML = combined
      .map(h => toRow(h, !isFirstRender && liveKeys.has(rowKey(h)) && !prev.has(rowKey(h))))
      .join('');
  host.scrollTop = scroll;
  seenKeys.set(host, liveKeys);
}

// Per-headline identity for new-row diffing. clusterId alone is not
// unique — a cluster yields many headlines — so we pair it with the
// createdAt timestamp, the same fingerprint HeadlinePublisher uses.
function rowKey(h) {
  return h.clusterId + '@' + h.createdAt;
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
  // Bottom-right "open the source thread in the browser" button. A plain external
  // anchor — external-links.js intercepts the click and routes it to the OS browser.
  const threadBtn = h.threadUrl
    ? `<a class="thread-open" href="${escapeText(h.threadUrl)}" title="Thread öffnen"
          aria-label="Thread im Browser öffnen">↗</a>`
    : '';

  return `<div class="${classes.join(' ')}">
    <div class="time">${time}</div>
    <div class="body">
      <div class="head">${head}</div>
      ${meta ? `<div class="meta">${meta}</div>` : ''}
    </div>
    ${threadBtn}
  </div>`;
}

function buildMeta(h) {
  // Meta row: the live quote strip (sparkline + price + day-move) for the
  // instrument the line is about, plus a quiet "News" provenance tag pinned to
  // the bottom-right when the editorial compose leaned on external news.
  // Sentiment/sector tags and the Yahoo+ticker provenance were removed — the
  // price is now sourced from several venues, so a "Yahoo" mark misleads, and
  // market sentiment is covered by the Fear&Greed gauge.
  const quote = buildQuote(h.snapshot);
  // Subtle provenance hint — not a highlight. CSS pushes it to the right.
  const news = h.newsEnriched
    ? `<span class="news-tag" title="Mit externen Nachrichten angereichert">News</span>`
    : '';
  if (!quote && !news) return '';
  const quoteHtml = quote ? `<span class="meta-group quote-group">${quote}</span>` : '';
  return quoteHtml + news;
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

  const stale = isStaleQuote(s);
  const spark = buildSparkline(s.spark, dir);
  const price = `<span class="q-price">${escapeText(fmtPrice(s.price, s.currency))}</span>`;
  const chgTxt = chg == null ? ''
    : `<span class="q-chg ${dir}">${chg >= 0 ? '+' : ''}${chg.toFixed(2)}%</span>`;

  // Off-hours (every venue closed) just dims the strip — no label; the hover title
  // explains it. With the live chain (L&S evening, NASDAQ after-hours) this is rare.
  return `<span class="quote ${dir}${stale ? ' stale' : ''}" title="${escapeText(buildQuoteTitle(s))}">`
       + `${spark}${price}${chgTxt}</span>`;
}

// A quote whose venue last traded more than 30 min ago is a last close, not live.
function isStaleQuote(s) {
  if (!s || typeof s.marketTime !== 'number' || s.marketTime <= 0) return false;
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
  if (s.source) L.push(`Kurs: ${s.source}`);            // which venue priced it
  if (isStaleQuote(s)) L.push('außerhalb der Handelszeit — letzter Kurs');
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
