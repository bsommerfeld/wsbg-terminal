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

const list = createHeadlineList({ identity: rowKey, renderRow: buildRow, renderEmpty });

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

function renderEmpty(host) {
  host.innerHTML = `
    <div class="empty-cook" aria-label="${escapeHtml(t('reddit.empty'))}">
      <img src="/icons/cook.webp" alt="">
    </div>`;
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
