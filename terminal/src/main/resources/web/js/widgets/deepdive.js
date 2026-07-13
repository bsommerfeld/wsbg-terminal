// KI-DD widget — one comprehensive, FIXED due-diligence report per subject,
// generated on demand from every data leg + the room, archived permanently,
// exportable as PDF. Socket contract: inbound `{type:"deepdive", payload:
// {command:"generate"|"list"|"get"|"delete"|"export-pdf", …}}`; outbound topic
// `deepdive` `{busy, stage, subject, reports:[meta…]}` (list state + progress)
// and `deepdive-report` `{item}` (one full report on demand).
//
// Anatomy (the watchlist's two-view pattern): `.dd-home` carries the ticker
// composer, the progress card AT THE TOP while a run is live (stage label +
// pass bars), and the report CARDS (delete via armed two-tap trash); clicking
// a card opens `.dd-detail-view` — the report as its OWN page with a round
// back arrow, header and PDF export. A finished run auto-opens its report —
// unless the user is reading another one. View switches animate ONE-SHOT
// (finite keyframes restarted by the hidden-toggle); nothing loops (OSR paint
// rule). The `.widget-body` stays the one scroller — list scroll position is
// saved and restored across the views.

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { renderMarkdown } from '../format/markdown.js';

let sock = null;
let hostEl = null;
let homeEl = null;
let listEl = null;
let progressEl = null;
let detailEl = null;

let state = { busy: false, stage: null, stageDetail: null, subject: null, reports: [] };
/** The full report currently cached (fetched via `get`, or pushed on finish). */
let current = null;
/** Which report's detail view is open (null = home). */
let openId = null;
/** Saved list scroll position while a detail view is open. */
let listScrollTop = 0;
/** Transient PDF-export outcome note ({ok, path} | null), cleared on next render. */
let pdfNote = null;
/** The report id whose delete button is armed (two-tap confirm). */
let armedDelete = null;

export function initDeepDive(socket) {
  sock = socket;
  hostEl = document.getElementById('deepdive-detail');
  if (!hostEl) return;
  hostEl.innerHTML = `
    <div class="dd-home">
      <form class="dd-form">
        <input class="dd-input" type="text" list="dd-suggestions" maxlength="15"
               autocomplete="off" spellcheck="false"
               placeholder="${escapeHtml(t('dd.placeholder'))}"
               aria-label="${escapeHtml(t('dd.aria'))}"
               data-i18n-placeholder="dd.placeholder" data-i18n-aria-label="dd.aria">
        <datalist id="dd-suggestions"></datalist>
        <button class="dd-btn" type="submit" data-i18n="dd.generate">${escapeHtml(t('dd.generate'))}</button>
      </form>
      <div class="dd-progress-host"></div>
      <div class="dd-list"></div>
    </div>
    <div class="dd-detail-view" hidden></div>`;
  homeEl = hostEl.querySelector('.dd-home');
  listEl = hostEl.querySelector('.dd-list');
  progressEl = hostEl.querySelector('.dd-progress-host');
  detailEl = hostEl.querySelector('.dd-detail-view');

  const form = hostEl.querySelector('.dd-form');
  const input = hostEl.querySelector('.dd-input');
  form.addEventListener('submit', e => {
    e.preventDefault();
    // The DD takes a TICKER, never a free name — the backend validates the
    // shape and looks the name up itself (local lists, then resolver).
    const ticker = input.value.trim().toUpperCase();
    if (!ticker || state.busy) return;
    sock.send('deepdive', { command: 'generate', name: ticker });
    input.value = '';
  });
  // Same live SubjectRegistry pool the watchlist uses — but ticker-valued.
  input.addEventListener('focus', () => sock.send('watchlist', { command: 'subjects' }));

  homeEl.addEventListener('click', onHomeClick);
  detailEl.addEventListener('click', onDetailClick);
  render();
}

/** `deepdive` payload → list/progress state. */
export function renderDeepDive(payload) {
  if (!payload) return;
  state = {
    busy: !!payload.busy,
    stage: payload.stage || null,
    stageDetail: payload.stageDetail || null,
    subject: payload.subject || null,
    reports: Array.isArray(payload.reports) ? payload.reports : [],
  };
  // A finished run pushes the fresh report alongside the state — open it as
  // its own page, but never yank a report the user is currently reading.
  if (payload.item) {
    current = payload.item;
    if (!openId) openView(current.id, true);
  }
  // A report deleted or aged out closes its stale detail view.
  if (openId && !state.reports.some(r => r.id === openId)) closeView(false);
  if (current && !state.reports.some(r => r.id === current.id)) current = null;
  if (payload.pdf) pdfNote = payload.pdf;
  armedDelete = state.reports.some(r => r.id === armedDelete) ? armedDelete : null;
  render();
  pdfNote = null;
}

/**
 * `watchlist-subjects` payload → the DD's OWN datalist, ticker-valued (the DD
 * accepts only tickers; entries without one are no suggestion here).
 */
export function renderDeepDiveSuggestions(payload) {
  const dl = document.getElementById('dd-suggestions');
  if (!dl) return;
  const items = payload && Array.isArray(payload.items) ? payload.items : [];
  dl.innerHTML = items.filter(s => s.ticker)
    .map(s => `<option value="${escapeHtml(s.ticker)}"${
      s.name ? ` label="${escapeHtml(s.name)}"` : ''}></option>`).join('');
}

/** `deepdive-report` payload → open that report's own page. */
export function renderDeepDiveReport(payload) {
  if (payload && payload.item) {
    current = payload.item;
    openView(current.id, true);
    render();
  }
}

/* ---- view switching (watchlist pattern: save/restore the ONE scroller) ---- */

function scroller() {
  return hostEl ? hostEl.closest('.widget-body') : null;
}

function openView(id, rememberScroll) {
  if (rememberScroll && !openId) {
    const sc = scroller();
    listScrollTop = sc ? sc.scrollTop : 0;
  }
  openId = id;
  const sc = scroller();
  if (sc) sc.scrollTop = 0;
}

function closeView(restoreScroll) {
  openId = null;
  if (restoreScroll) {
    const sc = scroller();
    if (sc) sc.scrollTop = listScrollTop;
  }
}

/* ---- events ---- */

function onHomeClick(e) {
  const del = e.target.closest('.dd-del');
  if (del) {
    const id = del.dataset.id;
    if (armedDelete === id) {
      armedDelete = null;
      sock.send('deepdive', { command: 'delete', id });
    } else {
      armedDelete = id; // two-tap confirm — the button turns red and asks
      render();
    }
    return;
  }
  if (armedDelete) {
    armedDelete = null; // any other click disarms
    render();
  }
  const card = e.target.closest('.dd-card');
  if (card) sock.send('deepdive', { command: 'get', id: card.dataset.id });
}

function onDetailClick(e) {
  if (e.target.closest('.dd-back')) {
    closeView(true);
    render();
    return;
  }
  const pdf = e.target.closest('.dd-pdf');
  if (pdf && current) {
    sock.send('deepdive', { command: 'export-pdf', id: current.id });
  }
}

/* ---- rendering ---- */

function render() {
  if (!homeEl) return;
  renderThumb();
  const open = !!(openId && current && current.id === openId);
  homeEl.hidden = open;
  detailEl.hidden = !open;
  detailEl.innerHTML = open ? detailHtml(current) : '';
  if (open) return;
  renderHome();
}

/* ---- the dedicated grid-card tile (.grid-thumb, shown instead of the
   miniature view in the overview): the newest report as hero (name, ticker,
   date + price) with earlier reports as terse rows — or the live run's
   subject + pass bars while the AI writes, or a short empty state. Laid out
   at natural pane size; widget-grid.css zooms it with the card, so the type
   is LARGE. ---- */

const THUMB_ROWS = 3;

function renderThumb() {
  const thumb = document.getElementById('deepdive-thumb');
  if (!thumb) return;

  if (state.busy) {
    const idx = Math.max(0, STAGES.indexOf(state.stage));
    const steps = STAGES.map((s, i) =>
      `<span class="dd-thumb-step${i < idx ? ' done' : i === idx ? ' live' : ''}"></span>`).join('');
    thumb.innerHTML = `<div class="dd-thumb-busy">
      <span class="dd-thumb-kicker">${escapeHtml(t('dd.thumb.writing'))}</span>
      <span class="dd-thumb-name">${escapeHtml(state.subject || '')}</span>
      <div class="dd-thumb-steps">${steps}</div>
    </div>`;
    return;
  }
  if (!state.reports.length) {
    thumb.innerHTML = `<div class="dd-thumb-empty">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><circle cx="11.5" cy="14.5" r="2.5"/><path d="m13.3 16.3 2.2 2.2"/></svg>
      <span>${escapeHtml(t('dd.thumb.empty'))}</span>
    </div>`;
    return;
  }
  const [head, ...rest] = state.reports;
  const meta = [fmtDateTime(head.createdAt),
    head.price != null ? fmtPrice(head.price, head.currency) : null].filter(Boolean).join(' · ');
  const rows = rest.slice(0, THUMB_ROWS - 1).map(r => `
    <div class="dd-thumb-row">
      <span class="dd-thumb-row-name">${escapeHtml(r.canonicalName || r.subject || '')}</span>
      <span class="dd-thumb-row-date">${escapeHtml(fmtDate(r.createdAt))}</span>
    </div>`).join('');
  const more = rest.length - (THUMB_ROWS - 1);
  thumb.innerHTML = `<div class="dd-thumb-report">
    <span class="dd-thumb-kicker">${escapeHtml(t('dd.thumb.latest'))}</span>
    <span class="dd-thumb-name">${escapeHtml(head.canonicalName || head.subject || '')}${
      head.ticker ? `<span class="dd-thumb-ticker">${escapeHtml(head.ticker)}</span>` : ''}</span>
    <span class="dd-thumb-meta">${escapeHtml(meta)}</span>
    ${rows ? `<div class="dd-thumb-rows">${rows}${
      more > 0 ? `<div class="dd-thumb-more">+${more}</div>` : ''}</div>` : ''}
  </div>`;
}

function renderHome() {
  const btn = hostEl.querySelector('.dd-btn');
  if (btn) btn.disabled = state.busy;
  progressEl.innerHTML = state.busy ? progressHtml() : '';

  if (!state.reports.length) {
    listEl.innerHTML = state.busy ? ''
      : `<p class="dd-empty">${escapeHtml(t('dd.empty'))}</p>`;
    return;
  }
  const cards = state.reports.map(cardHtml).join('');
  listEl.innerHTML = `
    <div class="dd-sec-head">
      <span class="dd-sec-kicker">${escapeHtml(t('dd.reports'))}</span>
      <span class="dd-sec-rule"></span>
      <span class="dd-sec-count">${state.reports.length}</span>
    </div>
    <ul class="dd-cards">${cards}</ul>`;
}

function cardHtml(r) {
  const armed = armedDelete === r.id;
  const meta = [fmtDateTime(r.createdAt),
    r.price != null ? fmtPrice(r.price, r.currency) : null].filter(Boolean).join(' · ');
  return `
  <li class="dd-card" data-id="${escapeHtml(r.id)}" role="button" tabindex="0">
    <span class="dd-card-main">
      <span class="dd-card-name">${escapeHtml(r.canonicalName || r.subject || '')}${
        r.ticker ? `<span class="dd-ticker">${escapeHtml(r.ticker)}</span>` : ''}</span>
      <span class="dd-card-meta">${escapeHtml(meta)}</span>
    </span>
    <button class="dd-del${armed ? ' armed' : ''}" type="button" data-id="${escapeHtml(r.id)}"
            title="${escapeHtml(t(armed ? 'dd.delete.confirm' : 'dd.delete'))}"
            aria-label="${escapeHtml(t(armed ? 'dd.delete.confirm' : 'dd.delete'))}">
      ${armed ? `<span class="dd-del-confirm">${escapeHtml(t('dd.delete.confirm'))}</span>`
        : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>'}
    </button>
    <svg class="dd-card-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>
  </li>`;
}

/* ---- progress: which pass is running ---- */

const STAGES = ['collect', 'draft', 'integrate', 'qa', 'final'];

function progressHtml() {
  const idx = Math.max(0, STAGES.indexOf(state.stage));
  const steps = STAGES.map((s, i) =>
    `<span class="dd-step${i < idx ? ' done' : i === idx ? ' live' : ''}"></span>`).join('');
  const label = t('dd.stage.' + (state.stage || 'collect'))
    + (state.stageDetail ? ` (${state.stageDetail})` : '');
  return `<div class="dd-progress">
    <span class="dd-progress-subject">${escapeHtml(state.subject || '')}</span>
    <span class="dd-progress-stage">${escapeHtml(label)}</span>
    <div class="dd-progress-steps">${steps}</div>
  </div>`;
}

/* ---- the report's own page ---- */

function detailHtml(r) {
  const meta = [fmtDateTime(r.createdAt),
    r.price != null ? fmtPrice(r.price, r.currency) : null].filter(Boolean).join(' · ');
  return `
  <div class="dd-detail-head">
    <button class="dd-back" type="button" title="${escapeHtml(t('dd.back'))}"
            aria-label="${escapeHtml(t('dd.back'))}">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>
    </button>
    <span class="dd-detail-titles">
      <span class="dd-detail-title">${escapeHtml(r.canonicalName || r.subject || '')}${
        r.ticker ? `<span class="dd-ticker">${escapeHtml(r.ticker)}</span>` : ''}</span>
      <span class="dd-detail-meta">${escapeHtml(meta)}</span>
    </span>
    <button class="dd-pdf" type="button" title="${escapeHtml(t('dd.pdf'))}"
            aria-label="${escapeHtml(t('dd.pdf'))}">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5"/><path d="M12 15V3"/></svg>
      <span data-i18n="dd.pdf">${escapeHtml(t('dd.pdf'))}</span>
    </button>
  </div>
  ${pdfNote ? `<span class="dd-pdf-note">${escapeHtml(
    pdfNote.ok ? `${t('dd.pdf.done')} ${pdfNote.path || ''}` : t('dd.pdf.failed'))}</span>` : ''}
  <div class="dd-report">${reportWithFigures(r)}</div>`;
}

/* White-paper layout: the report's ## sections rendered one by one, each
   followed by its figures (server-rendered SVG, section-anchored by ordinal).
   The SVG comes from our own Java builder — trusted markup; captions escaped. */
function reportWithFigures(r) {
  const charts = Array.isArray(r.charts) ? r.charts : [];
  const md = r.report || '';
  if (!charts.length) return renderMarkdown(md);

  // Split the markdown into its ## sections (chunk 0 = any preamble).
  const chunks = [];
  let chunk = { body: [] };
  chunks.push(chunk);
  for (const line of md.split('\n')) {
    if (line.startsWith('## ')) {
      chunk = { body: [line] };
      chunks.push(chunk);
    } else {
      chunk.body.push(line);
    }
  }
  const parts = [];
  // chunks[i+1] is section i (0-based ordinal of ## occurrences).
  for (let c = 0; c < chunks.length; c++) {
    const text = chunks[c].body.join('\n').trim();
    if (text) parts.push(renderMarkdown(text));
    const sectionIdx = c - 1;
    if (sectionIdx >= 0) {
      for (const fig of charts.filter(f => f.section === sectionIdx)) {
        parts.push(figureHtml(fig));
      }
    }
  }
  // Figures whose section never appeared (defensive) go to the end.
  const maxSection = chunks.length - 2;
  for (const fig of charts.filter(f => f.section > maxSection)) {
    parts.push(figureHtml(fig));
  }
  return parts.join('');
}

function figureHtml(fig) {
  return `<figure class="dd-figure">
    <figcaption>
      <span class="dd-figure-title">${escapeHtml(fig.title || '')}</span>
      <span class="dd-figure-rule"></span>
      ${fig.note ? `<span class="dd-figure-note">${escapeHtml(fig.note)}</span>` : ''}
    </figcaption>
    ${fig.svg || ''}
  </figure>`;
}

/* ---- formatting ---- */

function locale() {
  return currentLang() === 'de' ? 'de-DE' : 'en-US';
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

function fmtDateTime(epochSeconds) {
  return new Intl.DateTimeFormat(locale(), {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(epochSeconds * 1000));
}

function fmtDate(epochSeconds) {
  return new Intl.DateTimeFormat(locale(), {
    day: '2-digit', month: '2-digit', year: 'numeric',
  }).format(new Date(epochSeconds * 1000));
}
