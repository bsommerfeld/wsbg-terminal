// AI watchlist widget — the user's hand-picked subjects, each with a standing
// AI dossier the backend revises as evidence arrives. Fed by the `watchlist`
// socket payload ({ entries: [{ id, name, tldr, report, updatedAt, mapped,
// ticker, snapshot, sinceFirst, news, wireLines, … }] }); add/remove and the
// add-suggestions request go back over the same `watchlist` socket type.
//
// Anatomy: an add form (input with live subject suggestions), then one row per
// entry — name + ticker chip, the one-line TLDR (or a waiting/analyzing status)
// and a compact quote on the right. Clicking a row expands it into the full
// dossier: price header with day/5d/1m trend and the "since first mention"
// anchor, a multi-day close chart (L&S history, intraday spark as fallback),
// the report text, the subject's news and its last wire lines. All transient
// paints only — nothing loops (software-OSR paint rule).

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';

let sock = null;
let listEl = null;
let entries = [];
const expanded = new Set();

export function initWatchlist(socket) {
  sock = socket;
  const host = document.getElementById('watchlist-detail');
  if (!host) return;
  host.innerHTML = `
    <form class="wl-add">
      <input class="wl-add-input" type="text" list="wl-suggestions" maxlength="80"
             autocomplete="off" spellcheck="false"
             placeholder="${escapeHtml(t('wl.add.placeholder'))}"
             aria-label="${escapeHtml(t('wl.add.aria'))}"
             data-i18n-placeholder="wl.add.placeholder" data-i18n-aria-label="wl.add.aria">
      <datalist id="wl-suggestions"></datalist>
      <button class="wl-add-btn" type="submit" data-i18n="wl.add.btn">${escapeHtml(t('wl.add.btn'))}</button>
    </form>
    <div class="wl-list"></div>`;
  listEl = host.querySelector('.wl-list');

  const form = host.querySelector('.wl-add');
  const input = host.querySelector('.wl-add-input');
  form.addEventListener('submit', e => {
    e.preventDefault();
    const name = input.value.trim();
    if (!name) return;
    sock.send('watchlist', { command: 'add', name });
    input.value = '';
  });
  // Fresh suggestions (live subject units) every time the user starts typing.
  input.addEventListener('focus', () => sock.send('watchlist', { command: 'subjects' }));

  listEl.addEventListener('click', onListClick);
  renderList();
}

/** `watchlist` payload → full list re-render (the add form stays untouched). */
export function renderWatchlist(payload) {
  entries = payload && Array.isArray(payload.entries) ? payload.entries : [];
  for (const id of [...expanded]) {
    if (!entries.some(e => e.id === id)) expanded.delete(id);
  }
  renderList();
}

/** `watchlist-subjects` payload → the add input's datalist. */
export function renderWatchlistSubjects(payload) {
  const dl = document.getElementById('wl-suggestions');
  if (!dl) return;
  const items = payload && Array.isArray(payload.items) ? payload.items : [];
  dl.innerHTML = items.map(s => `<option value="${escapeHtml(s.name || '')}"${
    s.ticker ? ` label="${escapeHtml(s.ticker)}"` : ''}></option>`).join('');
}

function onListClick(e) {
  const del = e.target.closest('.wl-del');
  if (del) {
    sock.send('watchlist', { command: 'remove', id: del.dataset.id });
    return;
  }
  const head = e.target.closest('.wl-row-head');
  if (head) {
    const id = head.closest('.wl-row').dataset.id;
    if (expanded.has(id)) expanded.delete(id);
    else expanded.add(id);
    renderList();
  }
}

function renderList() {
  if (!listEl) return;
  renderThumb();
  if (!entries.length) {
    listEl.innerHTML = `<p class="wl-empty">${escapeHtml(t('wl.empty'))}</p>`;
    return;
  }
  listEl.innerHTML = entries.map(rowHtml).join('');
}

/* ---- the dedicated grid-card tile (.grid-thumb, shown instead of the
   miniature view in the overview): the watched names with their day moves —
   content only, none of the form/row chrome that reads as noise at card
   scale. Sized at natural-pane dimensions; widget-grid.css zooms it. ---- */

const THUMB_ROWS = 5;

function renderThumb() {
  const thumb = document.getElementById('watchlist-thumb');
  if (!thumb) return;
  if (!entries.length) {
    thumb.innerHTML = `
      <div class="wl-thumb-empty">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12z"/><circle cx="12" cy="12" r="3"/></svg>
        <span>${escapeHtml(t('wl.thumb.empty'))}</span>
      </div>`;
    return;
  }
  const shown = entries.slice(0, THUMB_ROWS);
  const rest = entries.length - shown.length;
  thumb.innerHTML = `
    <div class="wl-thumb-list">
      ${shown.map(e => {
        const s = e.snapshot;
        const pct = s && isFinite(s.changePercent) ? s.changePercent : null;
        const right = pct != null
          ? `<span class="wl-thumb-pct ${cls(pct)}${s.priceStale ? ' stale' : ''}">${escapeHtml(fmtPct(pct))}</span>`
          : (e.ticker ? `<span class="wl-thumb-ticker">${escapeHtml(e.ticker)}</span>` : '');
        return `<div class="wl-thumb-row">
          <span class="wl-thumb-name">${escapeHtml(e.name)}</span>${right}
        </div>`;
      }).join('')}
      ${rest > 0 ? `<div class="wl-thumb-more">+${rest}</div>` : ''}
    </div>`;
}

function rowHtml(e) {
  const open = expanded.has(e.id);
  const status = e.tldr
    ? `<span class="wl-tldr">${escapeHtml(e.tldr)}</span>`
    : `<span class="wl-tldr wl-status">${escapeHtml(t(e.mapped ? 'wl.analyzing' : 'wl.pending'))}</span>`;
  return `
  <article class="wl-row${open ? ' open' : ''}" data-id="${escapeHtml(e.id)}">
    <div class="wl-row-line">
      <button class="wl-row-head" type="button" aria-expanded="${open}"
              title="${escapeHtml(t('wl.expand'))}" aria-label="${escapeHtml(t('wl.expand'))}">
        <span class="wl-name">${escapeHtml(e.name)}${
          e.ticker ? `<span class="wl-ticker">${escapeHtml(e.ticker)}</span>` : ''}</span>
        ${status}
        ${miniQuoteHtml(e.snapshot)}
        <svg class="wl-chev" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg>
      </button>
      <button class="wl-del" type="button" data-id="${escapeHtml(e.id)}"
              title="${escapeHtml(t('wl.remove'))}" aria-label="${escapeHtml(t('wl.remove'))}">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
      </button>
    </div>
    ${open ? bodyHtml(e) : ''}
  </article>`;
}

/* ---- collapsed row: compact quote ---- */

function miniQuoteHtml(s) {
  if (!s || !isFinite(s.price)) return '';
  const pct = isFinite(s.changePercent) ? s.changePercent : null;
  return `<span class="wl-quote${s.priceStale ? ' stale' : ''}">
    <span class="wl-price">${escapeHtml(fmtPrice(s.price, s.currency))}</span>
    ${pct == null ? '' : `<span class="wl-pct ${cls(pct)}">${escapeHtml(fmtPct(pct))}</span>`}
  </span>`;
}

/* ---- expanded body ---- */

function bodyHtml(e) {
  const parts = [];
  if (e.snapshot && isFinite(e.snapshot.price)) parts.push(priceHeadHtml(e));
  parts.push(chartHtml(e.snapshot));
  if (e.report) {
    parts.push(`<div class="wl-report">${reportHtml(e.report)}</div>`);
  }
  parts.push(metaHtml(e));
  if (Array.isArray(e.news) && e.news.length) parts.push(newsHtml(e.news));
  if (Array.isArray(e.wireLines) && e.wireLines.length) parts.push(wireHtml(e.wireLines));
  return `<div class="wl-body">${parts.filter(Boolean).join('')}</div>`;
}

function priceHeadHtml(e) {
  const s = e.snapshot;
  const pct = isFinite(s.changePercent) ? s.changePercent : null;
  const tiles = [];
  if (isFinite(s.change5d)) tiles.push(trendTile('5D', s.change5d));
  if (isFinite(s.change1m)) tiles.push(trendTile('1M', s.change1m));
  let anchor = '';
  if (e.sinceFirst && isFinite(e.sinceFirst.percent)) {
    anchor = `<span class="wl-anchor">${escapeHtml(t('wl.sinceFirst'))} (${
      escapeHtml(fmtDate(e.sinceFirst.atEpoch))}): <b class="${cls(e.sinceFirst.percent)}">${
      escapeHtml(fmtPct(e.sinceFirst.percent))}</b></span>`;
  }
  return `<div class="wl-price-head${s.priceStale ? ' stale' : ''}">
    <span class="wl-price-big">${escapeHtml(fmtPrice(s.price, s.currency))}</span>
    ${pct == null ? '' : `<span class="wl-pct-big ${cls(pct)}">${escapeHtml(fmtPct(pct))}</span>`}
    ${tiles.join('')}
    ${s.source ? `<span class="wl-venue">${escapeHtml(s.source)}</span>` : ''}
    ${anchor}
  </div>`;
}

function trendTile(label, pct) {
  return `<span class="wl-trend"><span class="wl-trend-label">${label}</span>
    <span class="${cls(pct)}">${escapeHtml(fmtPct(pct))}</span></span>`;
}

/* Multi-day close chart from the L&S history; intraday spark as fallback. */
const CH = { w: 560, h: 120, pad: 6 };

function chartHtml(s) {
  if (!s) return '';
  const series = Array.isArray(s.dailyCloses) && s.dailyCloses.length >= 2
    ? s.dailyCloses
    : (Array.isArray(s.spark) && s.spark.length >= 2 ? s.spark : null);
  if (!series) return '';
  const vals = series.filter(v => isFinite(v));
  if (vals.length < 2) return '';
  const min = Math.min(...vals);
  const max = Math.max(...vals);
  const span = max - min || 1;
  const x = i => CH.pad + (i / (vals.length - 1)) * (CH.w - 2 * CH.pad);
  const y = v => CH.pad + (1 - (v - min) / span) * (CH.h - 2 * CH.pad);
  const pts = vals.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`);
  const up = vals[vals.length - 1] >= vals[0];
  const area = `M ${pts[0]} L ${pts.join(' L ')} L ${x(vals.length - 1).toFixed(1)},${CH.h} L ${CH.pad},${CH.h} Z`;
  return `<div class="wl-chart-wrap">
    <svg class="wl-chart ${up ? 'up' : 'down'}" viewBox="0 0 ${CH.w} ${CH.h}" preserveAspectRatio="none">
      <path class="wl-chart-area" d="${area}"/>
      <polyline class="wl-chart-line" points="${pts.join(' ')}"/>
      <circle class="wl-chart-dot" cx="${x(vals.length - 1).toFixed(1)}" cy="${y(vals[vals.length - 1]).toFixed(1)}" r="3"/>
    </svg>
  </div>`;
}

/* The dossier text: "- " lines become list items, everything else paragraphs. */
function reportHtml(text) {
  const out = [];
  let ul = null;
  for (const raw of String(text).split('\n')) {
    const line = raw.trim();
    if (!line) { ul = null; continue; }
    if (line.startsWith('- ')) {
      if (!ul) { ul = []; out.push(ul); }
      ul.push(line.slice(2));
    } else {
      ul = null;
      out.push(line);
    }
  }
  return out.map(b => Array.isArray(b)
    ? `<ul>${b.map(li => `<li>${escapeHtml(li)}</li>`).join('')}</ul>`
    : `<p>${escapeHtml(b)}</p>`).join('');
}

function metaHtml(e) {
  const bits = [];
  if (e.updatedAt) bits.push(`${escapeHtml(t('wl.updated'))} ${escapeHtml(fmtDateTime(e.updatedAt))}`);
  if (e.evidenceCount) bits.push(`${e.evidenceCount} ${escapeHtml(t('wl.mentions'))}`);
  if (!bits.length) return '';
  return `<p class="wl-meta">${bits.join(' · ')}</p>`;
}

function newsHtml(news) {
  return `<div class="wl-block">
    <div class="wl-block-title">${escapeHtml(t('wl.news.title'))}</div>
    <ul class="wl-news">${news.map(n => `
      <li><a href="${escapeHtml(n.url || '#')}" target="_blank" rel="noopener">${escapeHtml(n.title || '')}</a>
        <span class="wl-news-meta">${escapeHtml([n.publisher, n.publishedAt ? fmtDate(n.publishedAt) : null]
          .filter(Boolean).join(' · '))}</span></li>`).join('')}
    </ul>
  </div>`;
}

function wireHtml(lines) {
  return `<div class="wl-block">
    <div class="wl-block-title">${escapeHtml(t('wl.wire.title'))}</div>
    <ul class="wl-wire">${[...lines].reverse().map(h => `
      <li><span class="wl-wire-time">${escapeHtml(fmtDateTime(h.atEpoch))}</span> ${escapeHtml(h.text || '')}</li>`).join('')}
    </ul>
  </div>`;
}

/* ---- formatting ---- */

function locale() {
  return currentLang() === 'de' ? 'de-DE' : 'en-US';
}

function cls(pct) {
  return pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat';
}

function fmtPrice(price, currency) {
  const digits = Math.abs(price) < 1 ? 4 : 2;
  const num = price.toLocaleString(locale(), {
    minimumFractionDigits: digits, maximumFractionDigits: digits,
  });
  if (currency === 'PTS') return `${num} Pkt`;
  if (currency === 'EUR') return `${num} €`;
  if (currency === 'USD') return `${num} $`;
  return currency ? `${num} ${currency}` : num;
}

/* Signed percent, wire convention: leading +/− directly on the number. */
function fmtPct(pct) {
  const num = Math.abs(pct).toLocaleString(locale(), {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
  });
  return `${pct > 0 ? '+' : pct < 0 ? '−' : '±'}${num} %`;
}

function fmtDate(epochSeconds) {
  return new Intl.DateTimeFormat(locale(), {
    day: '2-digit', month: '2-digit', year: 'numeric',
  }).format(new Date(epochSeconds * 1000));
}

function fmtDateTime(epochSeconds) {
  return new Intl.DateTimeFormat(locale(), {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(epochSeconds * 1000));
}
