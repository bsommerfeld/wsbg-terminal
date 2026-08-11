// Renders the AI-generated headline list.
//
// This module owns only the headline ROW CONTENT (toRow / buildMeta) and the
// public entry points. The list mechanics (keyed incremental sync + archive
// scroll-back paging) live in headline-list.js; the market snapshot chip lives
// in quote-strip.js.
//
// Read state (headline-read.js) is per headline and persisted: what the reader
// has not had on screen yet is outlined as a block, and while such a block lies
// beyond an edge of the list that edge burns as a portal (paintPortals).
//
// New entries (headlines not seen in the previous render) get the .new-row class
// so the gold flash animation plays once — the "frisch aufgetaucht" cue. Keyed
// per-headline (clusterId + createdAt), NOT per-cluster: a cluster publishes many
// headlines over its life, so keying on clusterId alone would flash only the
// first one and silently skip every follow-up.

import { highlightTickers, highlightSubjects } from '../format/ticker.js';
import { colorizeSignedNumbers } from '../format/numbers.js';
import { fmtClock, fmtStamp } from '../format/time.js';
import { escapeHtml } from '../format/escape.js';
import { t } from '../i18n/i18n.js';
import { openNewsSources } from '../chrome/news-sources.js';
import { quoteStripHtml } from './quote-strip.js';
import { createHeadlineList } from './headline-list.js';
import { createReadState } from './headline-read.js';
import { matches, onFilterChange, priceOf } from './headline-filter.js';

// Gold subject highlight — LIVE since subject CONSOLIDATION (2026-07-01): one event now
// composes exactly ONE headline under its primary subject, and the backend gilds the
// longest form of the subject's name the line actually wrote (HeadlineWriter.displayFormIn,
// so "Salesforce, Inc." gilds "Salesforce"), which is what makes the glow consistent.
const GOLD_SUBJECTS = true;

const HIGHLIGHT_CLASS = {
  IMPORTANT: 'highlight-important',
};

// Per-headline identity for new-row diffing. clusterId alone is not
// unique — a cluster yields many headlines — so we pair it with the
// createdAt timestamp, the same fingerprint HeadlinePublisher uses.
function rowKey(h) {
  return h.clusterId + '@' + h.createdAt;
}

// The timestamp half of the row key — the read store prunes by it without
// knowing how the key is put together.
function keyAge(key) {
  return Number(key.slice(key.lastIndexOf('@') + 1)) || 0;
}

// Two neighbouring lines further apart than this get a thin rule between them —
// the wire's night-shift lull made visible instead of collapsed away.
const GAP_SECONDS = 2 * 60 * 60;

const readState = createReadState({ keyAge });

const list = createHeadlineList({
  identity: rowKey,
  gapSeconds: GAP_SECONDS,
  readState,
  canRead: headlinesAreOnScreen,
  onUnread: paintPortals,
  renderRow: buildRow,
  renderEmpty,
  filterFn: matches,
  renderNoMatches: renderNoMatch,
  renderSearchHead: buildSearchHead,
  renderSearchEmpty: buildSearchEmpty,
});

// A spec change (from the filter popover) re-syncs the loaded rows in place —
// no socket round-trip, the wire arrays stay complete.
onFilterChange(() => list.rerender());

export function renderHeadlines(host, items) {
  startStampTicker();
  list.render(host, items);
}

// The age line under each clock has to stay true without a re-render — rows
// live across renders (keyed sync), and a wire that stays quiet for an hour
// would otherwise freeze every line at "vor 1 Min.". One interval for the whole
// list, and it only touches a node whose wording actually changed, so a long
// scroll-back page costs a string compare per row and no layout at all.
let stampTimer = null;
function startStampTicker() {
  if (stampTimer) return;
  stampTimer = setInterval(tickStamps, 1000);
}

function tickStamps() {
  const now = Date.now() / 1000;
  for (const el of document.querySelectorAll('#widget-reddit .row .time .stamp')) {
    const next = fmtStamp(Number(el.dataset.stamp), now);
    if (next && next !== el.textContent) el.textContent = next;
  }
}

/** Wires the scroll-to-bottom → load-older-archive behaviour (call once). */
export function initHeadlineScroll(host, socket) {
  list.initScroll(host, socket);
}

// The two unread portals: a shimmer at the edge of the list, lit while an
// unread block sits beyond it. Both can burn at once (a fresh block above,
// yesterday's tail still open below) — each edge answers only for its own
// direction, and goes dark the moment its block is reached. The markup is
// static (index.html); this flips the light and MEASURES where the light goes.
function paintPortals({ above, below }) {
  const widget = document.getElementById('widget-reddit');
  if (!widget) return;
  fitPortals(widget);
  const top = widget.querySelector('.unread-portal-top');
  const bottom = widget.querySelector('.unread-portal-bottom');
  if (top) top.classList.toggle('lit', !!above);
  if (bottom) bottom.classList.toggle('lit', !!below);
}

/**
 * Pins the portals to the LIST, not to the widget. Nothing about that geometry
 * can be hardcoded: fullscreen drops the widget header entirely (so a portal
 * offset by a header height floats in mid-air), centres the rows in a fixed
 * reading column and scales the whole body with a zoom. The only honest source
 * is the painted box of the list and of a real headline — so that is what gets
 * measured, and the answer is written to the widget as custom properties.
 */
function fitPortals(widget) {
  const body = widget.querySelector('[data-rows]');
  if (!body) return;
  const box = widget.getBoundingClientRect();
  const list = body.getBoundingClientRect();
  // Measured off a real row: from the left edge of its clock to the right edge
  // of its text. That span already carries the reading column, the centring and
  // the zoom of whatever view is up - and it is the line the reader's eye
  // follows, clock included.
  const row = body.querySelector('.row');
  const clock = row && row.querySelector('.time');
  const text = row && row.querySelector('.body');
  const span = clock && text
    ? { left: clock.getBoundingClientRect().left, right: text.getBoundingClientRect().right }
    : { left: list.left, right: list.right };
  if (span.right - span.left <= 0) return;
  widget.style.setProperty('--portal-left', `${Math.max(0, span.left - box.left)}px`);
  widget.style.setProperty('--portal-right', `${Math.max(0, box.right - span.right)}px`);
  widget.style.setProperty('--portal-top', `${Math.max(0, list.top - box.top)}px`);
  widget.style.setProperty('--portal-bottom', `${Math.max(0, box.bottom - list.bottom)}px`);
}

/**
 * The portals' geometry changes without any headline changing: a window drag,
 * the switch into fullscreen, the grid overview. Re-measure on every box
 * change of the widget and of the list inside it.
 */
export function initUnreadPortals() {
  const widget = document.getElementById('widget-reddit');
  const body = widget && widget.querySelector('[data-rows]');
  if (!widget || !body || typeof ResizeObserver === 'undefined') return;
  const ro = new ResizeObserver(() => fitPortals(widget));
  ro.observe(widget);
  ro.observe(body);
  fitPortals(widget);
}

/**
 * Whether the headline list is actually in front of the reader. Dwelling on a
 * row only counts as READING it when it does — in the grid overview every
 * widget is a thumbnail whose rows sit technically "in view", and fullscreen on
 * another widget hides this one entirely. Neither is reading.
 */
function headlinesAreOnScreen() {
  const widget = document.getElementById('widget-reddit');
  const main = document.querySelector('.main');
  if (!widget || !main) return false;
  const view = main.dataset.view;
  if (view === 'grid') return false;
  if (view === 'focus' && !widget.classList.contains('focused')) return false;
  // Settings and any modal overlay cover the list — the eye is elsewhere.
  if (main.classList.contains('settings-open')) return false;
  return !document.querySelector('.overlay:not([hidden])');
}

/** Appends an older archive page (from the `archive-results` page command). */
export function appendArchivePage(items) {
  list.appendArchivePage(items);
}

/**
 * Every price currently on the loaded wire (live + scroll-back), unfiltered.
 * The price-range dial paints these as a density rug under its ruler, so the
 * user drags the band against where the wire's prices actually sit instead of
 * against an empty axis.
 */
export function loadedPrices() {
  const out = [];
  for (const h of list.allItems()) {
    const p = priceOf(h);
    if (p !== null) out.push(p);
  }
  return out;
}

/** Shows an archive-search result set in place of the wire (headline-search.js). */
export function showSearchResults(query, total, items) {
  list.showSearch(query, total, items);
}

// The dismissible banner above the search results: what was searched, how many
// hits, and the way back to the live wire.
function buildSearchHead(query, total, shown, onClear) {
  const el = document.createElement('div');
  el.className = 'search-banner';
  const capped = shown < total
    ? ` <span class="search-banner-cap">(${shown} ${escapeHtml(t('search.shown'))})</span>` : '';
  el.innerHTML = `
    <span class="search-banner-text">
      <span class="search-banner-q">${escapeHtml(query)}</span>
      <span class="search-banner-count">· ${total} ${escapeHtml(t('search.hits'))}${capped}</span>
    </span>
    <button type="button" class="search-banner-close" title="${escapeHtml(t('search.clear'))}"
            aria-label="${escapeHtml(t('search.clear'))}">×</button>`;
  el.querySelector('.search-banner-close').addEventListener('click', onClear);
  return el;
}

function buildSearchEmpty() {
  const el = document.createElement('div');
  el.className = 'search-empty';
  el.textContent = t('search.none');
  return el;
}

function renderEmpty(host) {
  host.innerHTML = `
    <div class="empty-cook" aria-label="${escapeHtml(t('reddit.empty'))}">
      <img src="/icons/cook.webp" alt="">
    </div>`;
}

// Shown when the wire HAS data but the active filter matches none of it. The
// same cook as the empty state, but staged as a broken picture hanging askew
// from a single nail: FROZEN (a canvas still frame — no CSS pauses an animated
// webp in Chromium), tilted, cracked, and struck through in red. A wordless
// "no headlines for this filter", visually distinct from the cold, still-
// animating "still cooking" state.
function renderNoMatch(host) {
  host.innerHTML = `
    <div class="empty-cook filter-blocked" aria-label="${escapeHtml(t('filter.empty'))}">
      <div class="cook-frame">
        <canvas class="cook-still" width="96" height="96"></canvas>
      </div>
    </div>`;
  const canvas = host.querySelector('canvas');
  const img = new Image();
  img.onload = () => {
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const W = canvas.width, H = canvas.height;
    // object-fit: cover — scale to fill the box, centred, so a non-square source
    // isn't distorted (matches the .empty-cook img rendering).
    const scale = Math.max(W / img.width, H / img.height);
    const dw = img.width * scale, dh = img.height * scale;
    try {
      ctx.drawImage(img, (W - dw) / 2, (H - dh) / 2, dw, dh);
      drawCracks(ctx, W, H);
    } catch { /* ignore */ }
  };
  img.src = '/icons/cook.webp';
}

// Shattered-glass cracks radiating from an off-centre impact, drawn straight
// onto the still. Deterministic (no randomness): a handful of jagged rays plus
// a broken ring of connectors. A dark stroke with a thin light highlight offset
// reads as depth.
function drawCracks(ctx, w, h) {
  const ix = w * 0.44, iy = h * 0.4;                 // impact point
  const ends = [[6, 4], [70, 3], [93, 34], [88, 82], [40, 94], [3, 66], [2, 30]];

  // One jagged polyline from the impact toward an edge point.
  const ray = end => {
    const [ex, ey] = end;
    const dx = ex - ix, dy = ey - iy, len = Math.hypot(dx, dy);
    const nx = -dy / len, ny = dx / len;             // unit normal, for the jag
    ctx.moveTo(ix, iy);
    const segs = 3;
    for (let i = 1; i < segs; i++) {
      const t = i / segs;
      const off = (i % 2 ? 1 : -1) * 3.2;            // alternating perpendicular kink
      ctx.lineTo(ix + dx * t + nx * off, iy + dy * t + ny * off);
    }
    ctx.lineTo(ex, ey);
  };

  const strokeAll = (color, width) => {
    ctx.beginPath();
    ends.forEach(ray);
    // Broken ring: connect every other ray's ~mid-radius point.
    for (let i = 0; i < ends.length; i += 2) {
      const a = ends[i], b = ends[(i + 2) % ends.length];
      const ax = ix + (a[0] - ix) * 0.55, ay = iy + (a[1] - iy) * 0.55;
      const bx = ix + (b[0] - ix) * 0.55, by = iy + (b[1] - iy) * 0.55;
      ctx.moveTo(ax, ay);
      ctx.lineTo((ax + bx) / 2 + 2, (ay + by) / 2 - 2);
      ctx.lineTo(bx, by);
    }
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.lineJoin = 'round';
    ctx.stroke();
  };

  strokeAll('rgba(8,8,10,0.5)', 1.4);                // crack body
  strokeAll('rgba(255,255,255,0.28)', 0.5);          // glint
}

function buildRow(h, isNew) {
  const tpl = document.createElement('template');
  tpl.innerHTML = toRow(h, isNew);
  const el = tpl.content.firstElementChild;
  // The News tag opens the source-article overlay when the record carries the
  // concrete refs (older archive lines only have the boolean → plain span).
  const newsTag = el.querySelector('button.news-tag');
  if (newsTag) newsTag.addEventListener('click', () => openNewsSources(h));
  if (isNew) {
    // Rows now live across renders, so drop the flash class once it played —
    // a row born offscreen (content-visibility skips it) would otherwise
    // replay the gold flash whenever it first scrolls into view.
    el.addEventListener('animationend', () => el.classList.remove('new-row'), { once: true });
  }
  return el;
}

function toRow(h, isNew) {
  // Both escape internally + emit <span>s. The subject gild carries the row when the
  // backend resolved a name form in the line (see GOLD_SUBJECTS); a row without one
  // (no ticker, or the line never wrote the name) keeps the plain ticker cue.
  const head = colorizeSignedNumbers(
    GOLD_SUBJECTS && Array.isArray(h.subjects) && h.subjects.length
      ? highlightSubjects(h.headline, h.subjects)
      : highlightTickers(h.headline, h.tickerSymbol));

  const classes = ['row'];
  const cls = HIGHLIGHT_CLASS[h.highlight];
  if (cls) classes.push(cls);
  if (isNew) classes.push('new-row');

  const time = fmtClock(h.createdAt);
  // Under the clock: how long ago the line landed, and from a week on its date.
  // The timestamp rides along on the element so the ticker can keep it current
  // without re-rendering the row.
  const stamp = fmtStamp(h.createdAt);
  const stampHtml = stamp
    ? `<span class="stamp" data-stamp="${h.createdAt}">${escapeHtml(stamp)}</span>` : '';
  const meta = buildMeta(h);
  // Bottom-right "open the source thread in the browser" button. A plain external
  // anchor — external-links.js intercepts the click and routes it to the OS browser.
  const threadBtn = h.threadUrl
    ? `<a class="thread-open" href="${escapeHtml(h.threadUrl)}" title="${escapeHtml(t('reddit.thread.open.title'))}"
          aria-label="${escapeHtml(t('reddit.thread.open.aria'))}">↗</a>`
    : '';

  return `<div class="${classes.join(' ')}">
    <div class="time">${time}${stampHtml}</div>
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
  const quote = quoteStripHtml(h.snapshot);
  // Subtle provenance hint — not a highlight. CSS pushes it to the right.
  // With concrete source refs on the record the tag becomes a button that
  // opens the news-sources overlay; old archive lines only carry the boolean
  // and keep the plain hover-hint span.
  const hasRefs = Array.isArray(h.newsRefs) && h.newsRefs.length > 0;
  const news = hasRefs
    ? `<button type="button" class="news-tag has-sources" title="${escapeHtml(t('reddit.news.sources.open'))}"
              aria-label="${escapeHtml(t('reddit.news.sources.open'))}">${escapeHtml(t('reddit.news.tag'))}</button>`
    : h.newsEnriched
      ? `<span class="news-tag" title="${escapeHtml(t('reddit.news.title'))}">${escapeHtml(t('reddit.news.tag'))}</span>`
      : '';
  if (!quote && !news) return '';
  const quoteHtml = quote ? `<span class="meta-group quote-group">${quote}</span>` : '';
  return quoteHtml + news;
}
