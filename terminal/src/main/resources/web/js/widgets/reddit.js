// Renders the AI-generated headline list.
//
// This module owns only the headline ROW CONTENT (toRow / buildMeta) and the
// public entry points. The list mechanics (keyed incremental sync + archive
// scroll-back paging) live in headline-list.js; the market snapshot chip lives
// in quote-strip.js.
//
// New entries (headlines not seen in the previous render) get the .new-row class
// so the gold flash animation plays once — the "frisch aufgetaucht" cue. Keyed
// per-headline (clusterId + createdAt), NOT per-cluster: a cluster publishes many
// headlines over its life, so keying on clusterId alone would flash only the
// first one and silently skip every follow-up.

import { highlightTickers, highlightSubjects } from '../format/ticker.js';
import { colorizeSignedNumbers } from '../format/numbers.js';
import { fmtClock } from '../format/time.js';
import { escapeHtml } from '../format/escape.js';
import { t } from '../i18n/i18n.js';
import { openNewsSources } from '../chrome/news-sources.js';
import { quoteStripHtml } from './quote-strip.js';
import { createHeadlineList } from './headline-list.js';
import { matches, onFilterChange } from './headline-filter.js';

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

const list = createHeadlineList({
  identity: rowKey,
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
  list.render(host, items);
}

/** Wires the scroll-to-bottom → load-older-archive behaviour (call once). */
export function initHeadlineScroll(host, socket) {
  list.initScroll(host, socket);
}

/** Appends an older archive page (from the `archive-results` page command). */
export function appendArchivePage(items) {
  list.appendArchivePage(items);
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
  const meta = buildMeta(h);
  // Bottom-right "open the source thread in the browser" button. A plain external
  // anchor — external-links.js intercepts the click and routes it to the OS browser.
  const threadBtn = h.threadUrl
    ? `<a class="thread-open" href="${escapeHtml(h.threadUrl)}" title="${escapeHtml(t('reddit.thread.open.title'))}"
          aria-label="${escapeHtml(t('reddit.thread.open.aria'))}">↗</a>`
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
