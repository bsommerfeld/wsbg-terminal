// KI-DD widget — one comprehensive, FIXED due-diligence report per subject,
// generated on demand from every data leg + the room, written in three AI
// passes (draft → adversarial Q&A → final), archived permanently, exportable
// as PDF. Socket contract: inbound `{type:"deepdive", payload:{command:
// "generate"|"list"|"get"|"export-pdf", …}}`; outbound topic `deepdive`
// `{busy, stage, subject, reports:[meta…]}` (list state + progress) and
// `deepdive-report` `{item}` (one full report on demand). Suggestions ride the
// watchlist's `wl-suggestions` datalist (same SubjectRegistry pool).
//
// Anatomy: generate form on top, then the progress narration while the passes
// run, then the selected report (markdown, PDF button), then the history of
// earlier reports. All transient paints only — nothing loops (OSR paint rule).

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { renderMarkdown } from '../format/markdown.js';

let sock = null;
let hostEl = null;
let bodyEl = null;
let state = { busy: false, stage: null, subject: null, reports: [] };
/** The full report currently shown (fetched via `get`, or pushed on finish). */
let current = null;
/** Transient PDF-export outcome note ({ok, path} | null), cleared on next render. */
let pdfNote = null;

export function initDeepDive(socket) {
  sock = socket;
  hostEl = document.getElementById('deepdive-detail');
  if (!hostEl) return;
  hostEl.innerHTML = `
    <form class="dd-form">
      <input class="dd-input" type="text" list="wl-suggestions" maxlength="80"
             autocomplete="off" spellcheck="false"
             placeholder="${escapeHtml(t('dd.placeholder'))}"
             aria-label="${escapeHtml(t('dd.aria'))}"
             data-i18n-placeholder="dd.placeholder" data-i18n-aria-label="dd.aria">
      <button class="dd-btn" type="submit" data-i18n="dd.generate">${escapeHtml(t('dd.generate'))}</button>
    </form>
    <div class="dd-body"></div>`;
  bodyEl = hostEl.querySelector('.dd-body');

  const form = hostEl.querySelector('.dd-form');
  const input = hostEl.querySelector('.dd-input');
  form.addEventListener('submit', e => {
    e.preventDefault();
    const name = input.value.trim();
    if (!name || state.busy) return;
    sock.send('deepdive', { command: 'generate', name });
    input.value = '';
  });
  // Same live suggestions the watchlist uses (one SubjectRegistry pool).
  input.addEventListener('focus', () => sock.send('watchlist', { command: 'subjects' }));

  bodyEl.addEventListener('click', onBodyClick);
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
  // A finished run pushes the fresh report as `item` alongside the state.
  if (payload.item) current = payload.item;
  if (current && !state.reports.some(r => r.id === current.id)) current = null;
  if (payload.pdf) pdfNote = payload.pdf;
  render();
  pdfNote = null;
}

/** `deepdive-report` payload → one full report to show. */
export function renderDeepDiveReport(payload) {
  if (payload && payload.item) {
    current = payload.item;
    render();
    const scroller = hostEl.closest('.widget-body');
    if (scroller) scroller.scrollTop = 0;
  }
}

function onBodyClick(e) {
  const hist = e.target.closest('.dd-history-btn');
  if (hist) {
    sock.send('deepdive', { command: 'get', id: hist.dataset.id });
    return;
  }
  const pdf = e.target.closest('.dd-pdf');
  if (pdf && current) {
    sock.send('deepdive', { command: 'export-pdf', id: current.id });
  }
}

function render() {
  if (!bodyEl) return;
  const btn = hostEl.querySelector('.dd-btn');
  if (btn) btn.disabled = state.busy;

  const parts = [];
  if (state.busy) parts.push(progressHtml());
  if (current) parts.push(reportHtml(current));
  if (!state.busy && !current && !state.reports.length) {
    parts.push(`<p class="dd-empty">${escapeHtml(t('dd.empty'))}</p>`);
  }
  if (state.reports.length) parts.push(historyHtml());
  bodyEl.innerHTML = parts.join('');
}

/* ---- progress: which pass is running ---- */

const STAGES = ['collect', 'draft', 'integrate', 'qa', 'final'];

function progressHtml() {
  const idx = Math.max(0, STAGES.indexOf(state.stage));
  const steps = STAGES.map((s, i) =>
    `<span class="dd-step${i <= idx ? ' done' : ''}"></span>`).join('');
  const label = t('dd.stage.' + (state.stage || 'collect'))
    + (state.stageDetail ? ` (${state.stageDetail})` : '');
  return `<div class="dd-progress">
    <span class="dd-progress-subject">${escapeHtml(state.subject || '')}</span>
    <span class="dd-progress-stage">${escapeHtml(label)}</span>
    <div class="dd-progress-steps">${steps}</div>
  </div>`;
}

/* ---- the report ---- */

function reportHtml(r) {
  const meta = [
    r.createdAt ? fmtDateTime(r.createdAt) : null,
    r.ticker || null,
    r.price != null ? fmtPrice(r.price, r.currency) : null,
  ].filter(Boolean).join(' · ');
  return `
  <div class="dd-report-head">
    <span class="dd-report-title">${escapeHtml(r.canonicalName || r.subject || '')}</span>
    <span class="dd-report-meta">${escapeHtml(meta)}</span>
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
  let current = { body: [] };
  chunks.push(current);
  for (const line of md.split('\n')) {
    if (line.startsWith('## ')) {
      current = { body: [line] };
      chunks.push(current);
    } else {
      current.body.push(line);
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

/* ---- history ---- */

function historyHtml() {
  const items = state.reports.map(r => `
    <li><button class="dd-history-btn${current && current.id === r.id ? ' active' : ''}"
                type="button" data-id="${escapeHtml(r.id)}">
      <span class="dd-history-name">${escapeHtml(r.canonicalName || r.subject || '')}</span>
      <span class="dd-history-meta">${escapeHtml(fmtDateTime(r.createdAt))}</span>
    </button></li>`).join('');
  return `
  <div class="dd-sec-head">
    <span class="dd-sec-kicker">${escapeHtml(t('dd.history'))}</span>
    <span class="dd-sec-rule"></span>
  </div>
  <ul class="dd-history">${items}</ul>`;
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
