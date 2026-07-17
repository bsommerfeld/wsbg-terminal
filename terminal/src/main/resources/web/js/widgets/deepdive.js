// KI-DD widget — one comprehensive, FIXED due-diligence report per subject,
// generated on demand from every data leg + the room, archived permanently,
// exportable as PDF. Socket contract: inbound `{type:"deepdive", payload:
// {command:"generate"|"list"|"get"|"delete"|"export-pdf", …}}`; outbound topic
// `deepdive` `{busy, stage, subject, reports:[meta…]}` (list state + progress)
// and `deepdive-report` `{item}` (one full report on demand).
//
// Anatomy: `.dd-home` is a full-height stage. The ORB (`.dd-orb`) floats at
// roughly ONE THIRD of the view (1:2 flex voids around the content) — idle
// it is the ticker pill (the go-arrow slides out only while text is typed);
// when a run starts the SAME container morphs fluidly into the progress card
// (stage timeline + ETA) and morphs back when the run ends. Below it: the
// reports as one-line rows, FIVE per page with a pager. A finished run does
// NOT auto-open — its row pulses an amber ring until it is opened, persisted
// across restarts (localStorage). Clicking a row opens `.dd-detail-view` —
// the report as its OWN page with a round back arrow, header and PDF export.
// View switches animate ONE-SHOT; the unread pulse is the single deliberate
// exception to the no-loop OSR paint rule (cheap border/shadow only). The
// `.widget-body` stays the one scroller — list scroll position is saved and
// restored across the views.

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { renderMarkdown } from '../format/markdown.js';
import { readingTimeLabel, readingTimeLabelFromWords } from '../format/readtime.js';
import { wireFigureHover, wireFigureJumps } from '../map/figure-hover.js';
import { figureHtml, linkFigureRefs } from './dd-figures.js';
import {
  initDeepDiveLive, resetLive, mountLive, unmountLive, finalizeLive,
  requestBacklog, setLiveSubject,
} from './deepdive-live.js';

let sock = null;
let hostEl = null;
let homeEl = null;
let listEl = null;
let progressEl = null;
let detailEl = null;
let liveEl = null;

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
/**
 * Whether the live run's cancel was already sent. MODULE state, not DOM state:
 * every progress push re-renders the card, so a `btn.disabled` set on the node
 * would be resurrected enabled by the next render.
 */
let cancelSent = false;
/** Current page of the report list (5 rows per page). */
let page = 0;

/* ---- unread persistence: report ids whose row pulses until first opened.
   localStorage rides the CEF profile, so the marker survives restarts. ---- */

const UNREAD_KEY = 'wsbg.dd.unread';

function loadUnread() {
  try {
    const raw = JSON.parse(localStorage.getItem(UNREAD_KEY) || '[]');
    return new Set(Array.isArray(raw) ? raw : []);
  } catch (_) { return new Set(); }
}

function saveUnread() {
  try { localStorage.setItem(UNREAD_KEY, JSON.stringify([...unread])); } catch (_) { /* best effort */ }
}

let unread = loadUnread();

/** Whether the live workshop view ("Blick in die Box") is open. */
let liveOpen = false;
/** Whether the live view is currently mounted into its element. */
let liveShown = false;
/** Whether the finished report's handover transition is playing. */
let handingOver = false;

export function initDeepDive(socket) {
  sock = socket;
  hostEl = document.getElementById('deepdive-detail');
  if (!hostEl) return;
  // The orb and its composer form are STATIC markup (never re-rendered):
  // the typed ticker survives state pushes, and the orb node persisting is
  // what lets CSS transitions carry the pill→card morph.
  hostEl.innerHTML = `
    <div class="dd-home">
      <div class="dd-void dd-void-top"></div>
      <div class="dd-orb-wrap">
      <div class="dd-orb">
        <form class="dd-compose">
          <input class="dd-input" type="text" maxlength="15"
                 autocomplete="off" spellcheck="false"
                 placeholder="${escapeHtml(t('dd.placeholder'))}"
                 aria-label="${escapeHtml(t('dd.aria'))}"
                 data-i18n-placeholder="dd.placeholder" data-i18n-aria-label="dd.aria">
          <button class="dd-go" type="submit" title="${escapeHtml(t('dd.generate'))}"
                  aria-label="${escapeHtml(t('dd.generate'))}" tabindex="-1">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14"/><path d="m13 6 6 6-6 6"/></svg>
          </button>
        </form>
        <div class="dd-progress-host"></div>
      </div>
      </div>
      <div class="dd-list"></div>
      <div class="dd-void dd-void-bottom"></div>
    </div>
    <div class="dd-detail-view" hidden></div>
    <div class="dd-live-view" hidden></div>`;
  homeEl = hostEl.querySelector('.dd-home');
  listEl = hostEl.querySelector('.dd-list');
  progressEl = hostEl.querySelector('.dd-progress-host');
  detailEl = hostEl.querySelector('.dd-detail-view');
  liveEl = hostEl.querySelector('.dd-live-view');
  initDeepDiveLive(sock, liveEl, () => { closeLive(true); render(); });

  const form = hostEl.querySelector('.dd-compose');
  const input = hostEl.querySelector('.dd-input');
  form.addEventListener('submit', e => {
    e.preventDefault();
    // The DD takes a TICKER, never a free name — the backend validates the
    // shape and looks the name up itself (local lists, then resolver).
    const ticker = input.value.trim().toUpperCase();
    if (!ticker || state.busy) return;
    sock.send('deepdive', { command: 'generate', name: ticker });
    input.value = '';
    form.classList.remove('has-text');
  });
  // The go-arrow slides out only while there is something to send.
  input.addEventListener('input', () => {
    form.classList.toggle('has-text', !!input.value.trim());
  });
  input.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      input.value = '';
      form.classList.remove('has-text');
      return;
    }
    // Enter fires the generation explicitly — the browser's implicit form
    // submission proved unreliable in the JCEF shell (live 2026-07-17).
    if (e.key === 'Enter') {
      e.preventDefault();
      if (form.requestSubmit) form.requestSubmit();
      else form.dispatchEvent(new Event('submit', { cancelable: true }));
    }
  });

  homeEl.addEventListener('click', onHomeClick);
  detailEl.addEventListener('click', onDetailClick);
  render();
}

/* The ETA chip ticks locally between pushes - the payload carries the
   bridge's estimate, receivedAt anchors the local countdown. */
let etaReceivedAt = 0;
setInterval(() => {
  if (!state || !state.busy || !(state.etaSeconds > 0) || !progressEl) return;
  const chip = progressEl.querySelector('.dd-eta');
  if (chip) chip.textContent = etaText();
}, 15000);

function etaText() {
  const drift = Math.round((Date.now() - etaReceivedAt) / 1000);
  const left = Math.max(0, (state.etaSeconds || 0) - drift);
  if (left < 90) return t('dd.eta.soon');
  return t('dd.eta').replace('{m}', String(Math.round(left / 60)));
}

/** `deepdive` payload → list/progress state. */
export function renderDeepDive(payload) {
  if (!payload) return;
  const wasBusy = state.busy;
  state = {
    busy: !!payload.busy,
    stage: payload.stage || null,
    stageDetail: payload.stageDetail || null,
    subject: payload.subject || null,
    progress: typeof payload.progress === 'number' ? payload.progress : -1,
    etaSeconds: typeof payload.etaSeconds === 'number' ? payload.etaSeconds : 0,
    reports: Array.isArray(payload.reports) ? payload.reports : [],
  };
  etaReceivedAt = Date.now();
  // The one-shot cancel resets on any busy edge: a NEW run gets a fresh
  // button, and a finished/cancelled run drops the pending state.
  if (wasBusy !== state.busy) cancelSent = false;
  // A NEW run resets the workshop feed; a run that ended WITHOUT a report
  // (cancelled/failed) closes an open box back to home.
  if (!wasBusy && state.busy) resetLive();
  if (state.busy) setLiveSubject(state.subject);
  if (wasBusy && !state.busy && !payload.item && liveOpen) closeLive(true);
  // A finished run pushes the fresh report alongside the state. It does NOT
  // auto-open: the orb morphs back to the pill and the new row pulses in the
  // list until the user opens it — that marker survives restarts. The ONE
  // exception is an open workshop box: the preview plays its handover
  // transition and becomes the finished report page (annotations and chat
  // do not survive — the print is AI-quiet).
  if (payload.item) {
    current = payload.item;
    unread.add(current.id);
    page = 0; // the fresh report always lands on the visible first page
    if (liveOpen && !handingOver) {
      handingOver = true;
      const freshId = current.id;
      finalizeLive(() => {
        handingOver = false;
        liveOpen = false;
        openView(freshId, false);
        render();
      });
    }
  }
  // Prune unread markers of reports that no longer exist (deleted/aged out).
  const before = unread.size;
  unread = new Set([...unread].filter(id => state.reports.some(r => r.id === id)));
  if (payload.item || unread.size !== before) saveUnread();
  // A report deleted or aged out closes its stale detail view.
  if (openId && !state.reports.some(r => r.id === openId)) closeView(false);
  if (current && !state.reports.some(r => r.id === current.id)) current = null;
  if (payload.pdf) pdfNote = payload.pdf;
  armedDelete = state.reports.some(r => r.id === armedDelete) ? armedDelete : null;
  render();
  pdfNote = null;
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
  // Opening is what ends the unread pulse.
  if (unread.delete(id)) saveUnread();
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

/* ---- the workshop box (live view) over the running generation ---- */

function openLive() {
  if (!state.busy || liveOpen) return;
  const sc = scroller();
  listScrollTop = sc ? sc.scrollTop : 0;
  liveOpen = true;
  setLiveSubject(state.subject);
  // The accumulated feed renders instantly; the backlog answer is the
  // authoritative replay (covers a box opened after a reconnect).
  requestBacklog();
  render();
  if (sc) sc.scrollTop = 0;
}

function closeLive(restoreScroll) {
  if (!liveOpen && !handingOver) return;
  liveOpen = false;
  handingOver = false;
  render();
  if (restoreScroll) {
    const sc = scroller();
    if (sc) sc.scrollTop = listScrollTop;
  }
}

/* ---- events ---- */

function onHomeClick(e) {
  if (e.target.closest('.dd-peek')) {
    openLive();
    return;
  }
  const pageBtn = e.target.closest('.dd-page-prev, .dd-page-next');
  if (pageBtn) {
    page += pageBtn.classList.contains('dd-page-next') ? 1 : -1;
    render();
    return;
  }
  if (e.target.closest('.dd-cancel')) {
    // One shot; state lives in the module (cancelSent), NOT on the node —
    // progress pushes re-render. The card collapses IMMEDIATELY (the render
    // treats a cancel-sent run as over); the backend's hard abort confirms
    // with the idle push moments later.
    if (cancelSent) return;
    cancelSent = true;
    sock.send('deepdive', { command: 'cancel' });
    if (liveOpen || handingOver) closeLive(true);
    render();
    return;
  }
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
  // Figure pointer in the prose ("Abbildung A3") — scroll to its figure card.
  const ref = e.target.closest('.dd-figref');
  if (ref) {
    e.preventDefault();
    const fig = detailEl.querySelector(`[data-figid="${ref.dataset.fig}"]`);
    if (fig) fig.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

/* ---- rendering ---- */

function render() {
  if (!homeEl) return;
  renderThumb();
  const live = liveOpen || handingOver;
  const open = !live && !!(openId && current && current.id === openId);
  homeEl.hidden = live || open;
  detailEl.hidden = !open;
  liveEl.hidden = !live;
  detailEl.innerHTML = open ? detailHtml(current) : '';
  if (live) {
    // The workshop box mounts ONCE per opening — the frequent state pushes
    // must never repaint it mid-typing (its own feed drives every update).
    if (!liveShown) {
      liveShown = true;
      mountLive();
    }
    return;
  }
  if (liveShown) {
    liveShown = false;
    unmountLive();
  }
  if (open) {
    // Shared figure layer (figure-hover.js): marks answer the cursor with
    // their own labels, the title row jumps to the figure's ## section.
    wireFigureHover(detailEl);
    wireFigureJumps(detailEl);
    return;
  }
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

/** Rows per page of the report list. */
const PAGE_SIZE = 5;

function renderHome() {
  // The orb morphs between its two lives: idle pill ⇄ live progress card.
  // Both are children of the persistent .dd-orb node, so the geometry
  // (width, radius, padding) transitions instead of jumping.
  const wrap = homeEl.querySelector('.dd-orb-wrap');
  const orb = homeEl.querySelector('.dd-orb');
  const form = homeEl.querySelector('.dd-compose');
  // A cancel-sent run is already over for the user — the card collapses on
  // the click, the backend's idle push only confirms.
  const running = state.busy && !cancelSent;
  if (wrap) wrap.classList.toggle('is-card', running); // width + orbiting halo
  if (orb) orb.classList.toggle('is-card', running);
  if (form) form.classList.toggle('is-gone', running);
  // Only WRITE the progress content while busy — on the way back to the pill
  // the stale content stays in the DOM (invisible: the host collapses via
  // 0fr + opacity) so the height animates closed instead of snapping.
  if (running) progressEl.innerHTML = progressHtml();

  if (!state.reports.length) {
    listEl.innerHTML = running ? ''
      : `<div class="dd-empty">
           <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><circle cx="11.5" cy="14.5" r="2.5"/><path d="m13.3 16.3 2.2 2.2"/></svg>
           <p>${escapeHtml(t('dd.empty'))}</p>
         </div>`;
    return;
  }

  const pages = Math.max(1, Math.ceil(state.reports.length / PAGE_SIZE));
  page = Math.min(Math.max(0, page), pages - 1);
  const rows = state.reports.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE);
  listEl.innerHTML = `
    <div class="dd-sec-head">
      <span class="dd-sec-kicker">${escapeHtml(t('dd.reports'))}</span>
      <span class="dd-sec-rule"></span>
      <span class="dd-sec-count">${state.reports.length}</span>
    </div>
    <ul class="dd-cards">${rows.map(rowHtml).join('')}</ul>
    ${pages > 1 ? `
    <div class="dd-pager">
      <button class="dd-page-prev" type="button" ${page === 0 ? 'disabled ' : ''}
              aria-label="${escapeHtml(t('dd.page.prev'))}">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>
      </button>
      <span class="dd-pager-num">${page + 1} / ${pages}</span>
      <button class="dd-page-next" type="button" ${page >= pages - 1 ? 'disabled ' : ''}
              aria-label="${escapeHtml(t('dd.page.next'))}">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>
      </button>
    </div>` : ''}`;
}

/* One report, one calm line — name + ticker left, date + reading time
   right. A row still unopened since its run finished pulses the amber ring. */
function rowHtml(r) {
  const meta = [fmtDate(r.createdAt),
    readingTimeLabelFromWords(r.reportWords)].filter(Boolean).join(' · ');
  return `
  <li class="dd-card${unread.has(r.id) ? ' unread' : ''}" data-id="${escapeHtml(r.id)}"
      role="button" tabindex="0">
    <span class="dd-card-name">${escapeHtml(r.canonicalName || r.subject || '')}${
      r.ticker ? `<span class="dd-ticker">${escapeHtml(r.ticker)}</span>` : ''}</span>
    <span class="dd-card-date">${escapeHtml(meta)}</span>
    ${delBtnHtml(r.id)}
  </li>`;
}

function delBtnHtml(id) {
  const armed = armedDelete === id;
  return `<button class="dd-del${armed ? ' armed' : ''}" type="button" data-id="${escapeHtml(id)}"
          title="${escapeHtml(t(armed ? 'dd.delete.confirm' : 'dd.delete'))}"
          aria-label="${escapeHtml(t(armed ? 'dd.delete.confirm' : 'dd.delete'))}">
    ${armed ? `<span class="dd-del-confirm">${escapeHtml(t('dd.delete.confirm'))}</span>`
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>'}
  </button>`;
}

/* ---- progress: the run as a stage timeline ----
   Header (subject + % + cancel) over a thin fill track, then the five
   passes as a vertical checklist: done = filled amber check, live = ring
   with the stage narration + ETA underneath, upcoming = hollow dot. All
   state-driven — nothing loops (OSR paint rule). */

const STAGES = ['collect', 'triage', 'sections', 'these', 'finish'];

function progressHtml() {
  const idx = Math.max(0, STAGES.indexOf(state.stage));
  const hasEta = state.progress >= 0 && state.etaSeconds > 0;
  const stages = STAGES.map((s, i) => {
    const cls = i < idx ? 'done' : i === idx ? 'live' : 'next';
    const dot = i < idx
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 13 4 4L19 7"/></svg>'
      : '';
    const live = i === idx;
    const detail = live
      ? `<span class="dd-stage-detail">${escapeHtml(
          t('dd.stage.' + s) + (state.stageDetail ? ` (${state.stageDetail})` : ''))}</span>`
      : '';
    return `<li class="dd-stage ${cls}">
      <span class="dd-stage-dot">${dot}</span>
      <span class="dd-stage-body">
        <span class="dd-stage-name">${escapeHtml(t('dd.step.' + s))}${
          live && hasEta ? `<span class="dd-eta">${escapeHtml(etaText())}</span>` : ''}</span>
        ${detail}
      </span>
    </li>`;
  }).join('');
  return `<div class="dd-progress">
    <div class="dd-progress-head">
      <span class="dd-progress-subject">${escapeHtml(state.subject || '')}</span>
      ${hasEta ? `<span class="dd-progress-pct">${Math.max(0, Math.min(100, Math.round(state.progress)))} %</span>` : ''}
      <button class="dd-cancel${cancelSent ? ' is-cancelling' : ''}" type="button"
              ${cancelSent ? 'disabled ' : ''}title="${escapeHtml(t('dd.cancel'))}"
              aria-label="${escapeHtml(t('dd.cancel'))}">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
      </button>
    </div>
    ${hasEta ? `
    <div class="dd-progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100"
         aria-valuenow="${state.progress}">
      <div class="dd-progress-fill" style="width:${Math.max(2, state.progress)}%"></div>
    </div>` : ''}
    <ol class="dd-stages">${stages}</ol>
    <button class="dd-peek" type="button">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
      <span>${escapeHtml(t('dd.live.peek'))}</span>
    </button>
  </div>`;
}

/* ---- the report's own page ---- */

function detailHtml(r) {
  const meta = [fmtDateTime(r.createdAt),
    r.price != null ? fmtPrice(r.price, r.currency) : null,
    r.reportWords != null ? readingTimeLabelFromWords(r.reportWords)
                          : readingTimeLabel(r.report)].filter(Boolean).join(' · ');
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
  // Positional figure IDs (A1, A2, ...) — the same register the prose cites.
  const figId = new Map(charts.map((f, i) => [f, 'A' + (i + 1)]));
  const parts = [];
  // chunks[i+1] is section i (0-based ordinal of ## occurrences).
  for (let c = 0; c < chunks.length; c++) {
    const text = chunks[c].body.join('\n').trim();
    if (text) parts.push(linkFigureRefs(renderMarkdown(text)));
    const sectionIdx = c - 1;
    if (sectionIdx >= 0) {
      for (const fig of charts.filter(f => f.section === sectionIdx)) {
        parts.push(figureHtml(fig, figId.get(fig)));
      }
    }
  }
  // Figures whose section never appeared (defensive) go to the end.
  const maxSection = chunks.length - 2;
  for (const fig of charts.filter(f => f.section > maxSection)) {
    parts.push(figureHtml(fig, figId.get(fig)));
  }
  return parts.join('');
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
