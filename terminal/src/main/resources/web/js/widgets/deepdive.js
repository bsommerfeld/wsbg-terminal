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
// A finished export is not written out, it is PLAYED: the button runs one
// ad-banner cycle (curtain in, check drawn on green fluid, curtain out to both
// sides, face morphs back) while the PDF opens itself in the OS viewer.
// View switches animate ONE-SHOT; the unread pulse is the single deliberate
// exception to the no-loop OSR paint rule (cheap border/shadow only). The
// `.widget-body` stays the one scroller — list scroll position is saved and
// restored across the views.

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { renderMarkdown } from '../format/markdown.js';
import { readingTimeLabel, readingTimeLabelFromWords } from '../format/readtime.js';
import { wireFigureHover, wireFigureJumps, wireFigureZoom } from '../map/figure-hover.js';
import { figureHtml, linkFigureRefs } from './dd-figures.js';
import {
  initDeepDiveLive, resetLive, mountLive, unmountLive, finalizeLive,
  requestBacklogIfNeeded, setLiveSubject,
} from './deepdive-live.js';

let sock = null;
let hostEl = null;
let homeEl = null;
let listEl = null;
let progressEl = null;
let detailEl = null;
let liveEl = null;

let state = { busy: false, stage: null, stageDetail: null, subject: null,
  priority: false, reports: [] };
/** The full report currently cached (fetched via `get`, or pushed on finish). */
let current = null;
/** Which report's detail view is open (null = home). */
let openId = null;
/** Saved list scroll position while a detail view is open. */
let listScrollTop = 0;
/**
 * The running success banner on the export button: `performance.now()` of its
 * start (0 = none). MODULE state, not DOM state — every state push rebuilds the
 * detail page, so the animation is re-attached with a negative delay (`--fx-shift`)
 * and picks up where it was instead of restarting from frame one.
 */
let pdfFlashAt = 0;
let pdfFlashTimer = null;
/** A failed export says so in words - briefly, then it clears itself. */
let pdfFailed = false;
let pdfFailTimer = null;
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
    // Priority: the wire's model lanes are parked, this run owns both workers.
    // Server-owned state (the run disarms it on every exit) — never latched here.
    priority: !!payload.priority,
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
  if (payload.pdf) applyPdfOutcome(payload.pdf);
  armedDelete = state.reports.some(r => r.id === armedDelete) ? armedDelete : null;
  render();
}

/** One banner cycle on the export button, in ms — keep in sync with deepdive.css. */
const PDF_FLASH_MS = 2400;
/** How long a failed export stays on screen before it clears itself. */
const PDF_FAIL_MS = 6000;

/**
 * The export's outcome. Success needs no words: the button plays its banner
 * (check + green fluid, sweeping out left and right) while the PDF opens itself
 * in the OS viewer. Only a failure is written out, and even that goes again.
 */
function applyPdfOutcome(pdf) {
  clearTimeout(pdfFlashTimer);
  clearTimeout(pdfFailTimer);
  if (pdf.ok) {
    pdfFailed = false;
    pdfFlashAt = performance.now();
    pdfFlashTimer = setTimeout(() => { pdfFlashAt = 0; render(); }, PDF_FLASH_MS);
  } else {
    pdfFlashAt = 0;
    pdfFailed = true;
    pdfFailTimer = setTimeout(() => { pdfFailed = false; render(); }, PDF_FAIL_MS);
  }
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
  // The accumulated feed renders instantly; the authoritative replay is
  // fetched ONLY when that feed is incomplete (gap seen / page loaded
  // mid-run) — a blind replay froze the switch on long runs.
  requestBacklogIfNeeded();
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
  if (e.target.closest('.dd-priority')) {
    // Toggle only — the backend answers with a state push, so the button never
    // shows a mode the gate isn't actually in (a run that just ended rejects it).
    sock.send('deepdive', { command: 'priority', on: !state.priority });
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
  if (card && card.dataset.id) sock.send('deepdive', { command: 'get', id: card.dataset.id });
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
    wireFigureZoom(detailEl);
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

  // The list is an ANCHORED slot: head + exactly PAGE_SIZE row heights + pager,
  // whatever the archive holds. Missing rows are rendered as invisible ghosts
  // and a single page voids the pager instead of dropping it — otherwise every
  // new report would change the list's height and shove the orb up the stage.
  const pages = Math.max(1, Math.ceil(state.reports.length / PAGE_SIZE));
  page = Math.min(Math.max(0, page), pages - 1);
  const rows = state.reports.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE);
  const ghosts = Array.from({ length: PAGE_SIZE - rows.length }, ghostRowHtml);
  const empty = !state.reports.length && !running;
  listEl.innerHTML = `
    <div class="dd-sec-head">
      <span class="dd-sec-kicker">${escapeHtml(t('dd.reports'))}</span>
      <span class="dd-sec-rule"></span>
      <span class="dd-sec-count">${state.reports.length}</span>
    </div>
    <div class="dd-rows">
      <ul class="dd-cards">${rows.map(rowHtml).join('')}${ghosts.join('')}</ul>
      ${empty ? `
      <div class="dd-empty">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><circle cx="11.5" cy="14.5" r="2.5"/><path d="m13.3 16.3 2.2 2.2"/></svg>
        <p>${escapeHtml(t('dd.empty'))}</p>
      </div>` : ''}
    </div>
    <div class="dd-pager${pages > 1 ? '' : ' is-void'}"${pages > 1 ? '' : ' aria-hidden="true"'}>
      <button class="dd-page-prev" type="button" ${page === 0 ? 'disabled ' : ''}
              aria-label="${escapeHtml(t('dd.page.prev'))}">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>
      </button>
      <span class="dd-pager-num">${page + 1} / ${pages}</span>
      <button class="dd-page-next" type="button" ${page >= pages - 1 ? 'disabled ' : ''}
              aria-label="${escapeHtml(t('dd.page.next'))}">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>
      </button>
    </div>`;
}

/* An empty row slot: the exact geometry of a real row (name line, meta line,
   delete button box), painted invisible — it holds the list's height still. */
function ghostRowHtml() {
  return `
  <li class="dd-card dd-card-ghost" aria-hidden="true">
    <span class="dd-card-name">&nbsp;</span>
    <span class="dd-card-date">&nbsp;</span>
    <span class="dd-del"></span>
  </li>`;
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

/* The stage narration. A detail that arrives as an `i18n:<key>` token is
   translated HERE, live with the language switch — the back end never ships a
   display literal for those. Anything else is passed through as sent. */
function stageDetailText() {
  const d = state.stageDetail;
  if (!d) return '';
  return d.startsWith('i18n:') ? t(d.slice(5)) : d;
}

function progressHtml() {
  const idx = Math.max(0, STAGES.indexOf(state.stage));
  const hasEta = state.progress >= 0 && state.etaSeconds > 0;
  const stages = STAGES.map((s, i) => {
    const cls = i < idx ? 'done' : i === idx ? 'live' : 'next';
    const dot = i < idx
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 13 4 4L19 7"/></svg>'
      : '';
    const live = i === idx;
    const note = stageDetailText();
    const detail = live
      ? `<span class="dd-stage-detail">${escapeHtml(
          t('dd.stage.' + s) + (note ? ` (${note})` : ''))}</span>`
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
      <button class="dd-priority${state.priority ? ' is-on' : ''}" type="button"
              aria-pressed="${state.priority ? 'true' : 'false'}"
              title="${escapeHtml(t(state.priority ? 'dd.priority.off' : 'dd.priority.on'))}"
              aria-label="${escapeHtml(t(state.priority ? 'dd.priority.off' : 'dd.priority.on'))}">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M13 2 4 14h7l-1 8 9-12h-7z"/></svg>
      </button>
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
    ${state.priority ? `<p class="dd-priority-note">${escapeHtml(t('dd.priority.note'))}</p>` : ''}
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
  // A banner started before this repaint resumes at its own offset (see pdfFlashAt).
  const elapsed = pdfFlashAt ? Math.round(performance.now() - pdfFlashAt) : 0;
  const flashing = pdfFlashAt > 0 && elapsed < PDF_FLASH_MS;
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
    <button class="dd-pdf${flashing ? ' is-flash' : ''}" type="button"
            ${flashing ? `style="--fx-shift:${-elapsed}ms"` : ''}
            title="${escapeHtml(t('dd.pdf'))}" aria-label="${escapeHtml(t('dd.pdf'))}">
      <span class="dd-pdf-face">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5"/><path d="M12 15V3"/></svg>
        <span data-i18n="dd.pdf">${escapeHtml(t('dd.pdf'))}</span>
      </span>
      ${flashing ? pdfFlashHtml() : ''}
    </button>
  </div>
  ${pdfFailed ? `<span class="dd-pdf-note" role="status">${
    escapeHtml(t('dd.pdf.failed'))}</span>` : ''}
  <div class="dd-report">${reportWithFigures(r)}</div>`;
}

/**
 * The success banner that plays INSIDE the export button, ad-banner style: the
 * curtain sweeps shut over the button, a check draws itself on green fluid, and
 * both halves sweep back out — left to the left, right to the right — while the
 * button's own face morphs back in the middle. Both halves carry the SAME
 * artwork at full button width, each clipping its own side, so the two frames
 * compose one picture that leaves the frame in opposite directions.
 */
function pdfFlashHtml() {
  const art = `<span class="dd-pdf-fx-art">
        <i class="dd-pdf-blob b1"></i><i class="dd-pdf-blob b2"></i><i class="dd-pdf-blob b3"></i>
        <svg class="dd-pdf-fx-check" viewBox="0 0 44 24" aria-hidden="true">
          <path d="M16 12.6l4 4.2 8.6-9.4"/>
        </svg>
      </span>`;
  return `<span class="dd-pdf-fx" aria-hidden="true">
        <span class="dd-pdf-fx-half l">${art}</span>
        <span class="dd-pdf-fx-half r">${art}</span>
      </span>`;
}

/* White-paper layout: the report's ## sections rendered one by one, each
   followed by its figures (server-rendered SVG, section-anchored by ordinal).
   The SVG comes from our own Java builder — trusted markup; captions escaped. */
function reportWithFigures(r) {
  const charts = Array.isArray(r.charts) ? r.charts : [];
  const { body: md, sources } = splitSources(r.report || '');
  if (!charts.length) return renderMarkdown(md) + sourcesHtml(sources);

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
  return parts.join('') + sourcesHtml(sources);
}

/* ---- the source register: apparatus, not prose ----
   The deterministic "## Quellen" register (house-appended, ALWAYS the report's
   last section) is the one part nobody reads top to bottom — it is looked UP.
   So it rides folded: the crosshead stays, the footnote list waits behind it. */

const SOURCES_HEAD = /^##\s+(Quellen|Sources)\s*$/;

/** Splits the trailing source register off the report markdown. */
function splitSources(md) {
  const lines = md.split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    if (!lines[i].startsWith('## ')) continue;
    // Only the LAST section can be the register — anything else is prose.
    if (!SOURCES_HEAD.test(lines[i].trim())) break;
    return {
      body: lines.slice(0, i).join('\n').trimEnd(),
      sources: {
        title: lines[i].slice(3).trim(),
        list: lines.slice(i + 1).join('\n').trim(),
      },
    };
  }
  return { body: md, sources: null };
}

/** The register folded into a `<details>` — collapsed until the reader asks. */
function sourcesHtml(sources) {
  if (!sources || !sources.list) return '';
  const count = sources.list.split('\n').filter(l => l.trim().startsWith('- ')).length;
  return `<details class="dd-sources">
    <summary title="${escapeHtml(t('dd.sources.toggle'))}"
             aria-label="${escapeHtml(t('dd.sources.toggle'))}">
      <span class="dd-sources-title">${escapeHtml(sources.title)}</span>
      ${count ? `<span class="dd-sources-count">${count}</span>` : ''}
      <svg class="dd-sources-chev" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
    </summary>
    <div class="dd-sources-body">${renderMarkdown(sources.list)}</div>
  </details>`;
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
