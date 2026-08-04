// KI-DD live workshop view ("Blick in die Box") — the running report opened
// mid-generation. STRICTLY view-only: the user watches the desk work, never
// talks back. Socket contract: topic `deepdive-live` streams `{entry}` /
// `{charts}` increments (see DeepDiveBridge.liveJson: k=chat|body|note|
// pending|settled, ph=phase token, who=participant token, sec/par=locus,
// t=FULL text, diff=[{k,t,o,n}]); `{command:"live"}` answers the whole
// backlog on `deepdive-live-backlog` `{busy, subject, entries, charts}`.
//
// The stage: ONE column, TWO decks, split by a rule.
//
// Above the rule, the REPORT DECK — and nothing but the work on the report
// itself: the standing section texts set in the document column the finished
// page and the PDF use, figures slotted in the moment they exist, edits
// playing as human-like typing (deletions fade at the changed spot,
// insertions type in), and a blurred AI cover over any locus a judge doubts
// until its next standing state. No commentary up here.
//
// Below the rule, the REDAKTION — the full-width dashboard where everything
// the desk is actually chewing on is laid out, without exception. Three ways
// to look at the same run: the TICKET BOARD (one card per locus — a section
// where the work is section-bound, a phase where it is house-wide; that
// partition is closed, no entry falls through), the STREAM (the same voices
// strictly in the order they fell) and the SOURCE REGISTER (every source ever
// called for with the verdict it earned, rejects kept). Until 2026-08-04 this
// was a 320-420px stripe beside the report that only ever went full width in
// one accidental breakpoint band.
//
// Everything here is PREVIEW: subtly badged, and none of it survives into the
// finished report page — the finalize transition clears the workshop and
// hands over to the ordinary detail view (deepdive.js).
//
// Animation discipline (OSR paint rule): typing and fades are FINITE and
// driven by timeouts on real state changes; the only loop is the sanctioned
// skeleton shimmer on covers/ghosts, which exists only while the desk works.

import { t } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { renderMarkdown } from '../format/markdown.js';
import { wireFigureHover, wireFigureZoom } from '../map/figure-hover.js';
import { figureHtml, linkFigureRefs } from './dd-figures.js';
import { createSourceScene } from './dd-source-scene.js';

const SECTION_COUNT = 8;

/** House phases in running order — the ticket board's tail is sorted by it. */
const PHASES = ['collect', 'triage', 'figures', 'sections', 'these', 'reclaim',
  'consistency', 'typeset'];

/** The desk's three views, and the register's three filters. */
const DESK_VIEWS = ['tickets', 'stream', 'sources'];
const SRC_FILTERS = ['all', 'keep', 'out'];

/** The animated source room runs above the register — unless motion is off. */
const SCENE_OK = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;

let sock = null;
let liveEl = null;
/** deepdive.js's "leave the box" callback (back arrow). */
let onBack = null;

/* ---- run state, accumulated from every increment (also while the box is
   closed, so opening replays instantly; the backlog command covers gaps) ---- */

let entries = [];                  // every entry, seq-ordered (the chat's source)
let seenSeq = 0;                   // highest seq applied
let gapped = false;                // a seq gap was seen — the feed needs a replay
let sections = emptySections();    // per index: current standing text | null
let notes = emptyNotes();          // per index: [{par, who, text}]
let pending = emptyPending();      // per index: {par} | null
let charts = [];                   // [{section, title, note, svg}]
/** The triage board: every collected source with its live verdict state. */
let sources = [];                  // [{ref, title, state:'pending'|'ok'|'out'}]
let srcIndex = new Map();          // ref -> source object
let srcEls = new Map();            // ref -> its <li> while the list stands
let scene = null;                  // the source room while it is on stage
let phase = null;                  // latest phase token
let subject = null;
let mounted = false;
let finalizing = false;
/** The Redaktion folded shut down to its head. */
let deskFolded = false;
/** Which of the desk's three views stands — only the standing one is built. */
let deskView = 'sources';
/** A hand on the view switch: from then on no default moves the desk again. */
let deskViewPinned = false;
/** The register's row filter. */
let srcFilter = 'all';

function emptySections() { return Array(SECTION_COUNT).fill(null); }
function emptyNotes() { return Array.from({ length: SECTION_COUNT }, () => []); }
function emptyPending() { return Array(SECTION_COUNT).fill(null); }

export function initDeepDiveLive(socket, el, backCallback) {
  sock = socket;
  liveEl = el;
  onBack = backCallback;
  liveEl.addEventListener('click', onClick);
  // A live language switch re-renders the whole box from state (the
  // participant/phase labels and headings are translated at render time).
  window.addEventListener('wsbg:languagechange', () => { if (mounted) renderAll(); });
}

/** New run: forget the previous workshop entirely. */
export function resetLive() {
  entries = [];
  seenSeq = 0;
  gapped = false;
  sections = emptySections();
  notes = emptyNotes();
  pending = emptyPending();
  charts = [];
  sources = [];
  srcIndex = new Map();
  srcEls = new Map();
  dropScene();
  phase = null;
  subject = null;
  finalizing = false;
  deskFolded = false;
  deskView = 'sources';
  deskViewPinned = false;
  srcFilter = 'all';
  jobs.clear();
  pulses.clear();
  if (mounted) renderAll();
}

/** `deepdive-live` increments: one entry, or the figure layer. */
export function onDeepDiveLive(payload) {
  if (!payload) return;
  if (Array.isArray(payload.charts)) {
    charts = payload.charts;
    if (mounted) renderFigures();
    return;
  }
  const e = payload.entry;
  if (!e || typeof e.seq !== 'number') return;
  if (e.seq <= seenSeq) return;
  // A gap means missed increments (reconnect) — remembered even while the
  // box is closed, so opening later knows the local feed is incomplete.
  if (seenSeq > 0 && e.seq > seenSeq + 1) {
    gapped = true;
    if (mounted) requestBacklog();
  }
  seenSeq = e.seq;
  entries.push(e);
  applyEntry(e, mounted);
}

/** `deepdive-live-backlog`: the authoritative replay — rebuild from scratch. */
export function onDeepDiveLiveBacklog(payload) {
  if (!payload || !Array.isArray(payload.entries)) return;
  entries = payload.entries.slice();
  charts = Array.isArray(payload.charts) ? payload.charts : [];
  subject = payload.subject || subject;
  sections = emptySections();
  notes = emptyNotes();
  pending = emptyPending();
  sources = [];
  srcIndex = new Map();
  srcEls = new Map();
  dropScene();
  phase = null;
  jobs.clear();
  seenSeq = 0;
  gapped = false;
  // Rebuild is pure state work — the single renderAll below paints it.
  const wasMounted = mounted;
  mounted = false;
  for (const e of entries) {
    if (typeof e.seq === 'number' && e.seq > seenSeq) seenSeq = e.seq;
    applyEntry(e, false);
  }
  mounted = wasMounted;
  if (mounted) renderAll();
}

function requestBacklog() {
  if (sock) sock.send('deepdive', { command: 'live' });
}

/**
 * Opening the box: the replay is only worth its megabytes when the local
 * feed is actually incomplete — a seen gap, or nothing received yet (page
 * loaded mid-run). Otherwise the accumulated state IS the backlog.
 */
export function requestBacklogIfNeeded() {
  if (gapped || seenSeq === 0) requestBacklog();
}

export function setLiveSubject(s) {
  subject = s || subject;
  if (mounted) {
    const el = liveEl.querySelector('.dd-live-name');
    if (el && subject) el.textContent = subject;
  }
}

/** Mount (or re-mount) the box into its view element. */
export function mountLive() {
  mounted = true;
  finalizing = false;
  // Opening mid-run: the desk looks where the run actually is, unless the
  // reader has already picked a view for this run.
  if (!deskViewPinned) deskView = defaultDeskView();
  renderAll();
}

export function unmountLive() {
  mounted = false;
  jobs.clear();
  dropScene();
  if (liveEl) liveEl.innerHTML = '';
}

/** The room stops the moment its canvas leaves the DOM (paint budget). */
function dropScene() {
  if (scene) { scene.destroy(); scene = null; }
}

/**
 * The handover: the report stands — the workshop clears (annotations, covers
 * and chat fade in one one-shot pass), then the caller swaps in the ordinary
 * finished-report view. Nothing of the preview survives.
 */
export function finalizeLive(done) {
  if (!mounted) { done(); return; }
  finalizing = true;
  // Freeze every running animation at its final text first.
  for (let i = 0; i < SECTION_COUNT; i++) flushSection(i);
  const view = liveEl.firstElementChild;
  if (view) view.classList.add('dd-finalizing');
  const banner = liveEl.querySelector('.dd-live-done');
  if (banner) banner.hidden = false;
  setTimeout(() => { finalizing = false; done(); }, 900);
}

/* ---- state application ---- */

function applyEntry(e, animate) {
  if (e.ph) {
    phase = e.ph;
    // The room only needs to know whether the desk still expects sources —
    // collecting and triage overlap, and it keeps fetching through both.
    if (scene) scene.phase(phase);
  }
  const sec = typeof e.sec === 'number' ? e.sec : -1;
  switch (e.k) {
    case 'body':
      if (sec >= 0) {
        pending[sec] = null;
        // A landed rework answers the standing objections on this section —
        // they are resolved and stop counting as open.
        notes[sec] = [];
        queueBody(sec, e.t || '', animate);
        if (mounted) refreshTicketState(sec);
      }
      break;
    case 'pending':
      if (sec >= 0) {
        pending[sec] = { par: typeof e.par === 'number' ? e.par : 0 };
        if (mounted) { applyCover(sec); refreshTicketState(sec); }
      }
      break;
    case 'settled':
      if (sec >= 0) {
        pending[sec] = null;
        if (mounted) { applyCover(sec); refreshTicketState(sec); }
      }
      break;
    case 'note':
      if (sec >= 0) notes[sec].push({ par: e.par || 0, who: e.who || '', text: e.t || '' });
      if (mounted) fileEntry(e);
      break;
    case 'chat':
      if (mounted) fileEntry(e);
      break;
    case 'src': {
      if (!e.ref || srcIndex.has(e.ref)) break;
      const s = { ref: e.ref, title: e.t || '', state: 'pending' };
      sources.push(s);
      srcIndex.set(e.ref, s);
      if (mounted) addSrcRow(s, animate);
      break;
    }
    case 'src-ok':
    case 'src-out': {
      const s = e.ref ? srcIndex.get(e.ref) : null;
      if (!s || s.state === 'out') break;
      const next = e.k === 'src-ok' ? 'ok' : 'out';
      if (s.state === next) break;
      s.state = next;
      if (mounted) updateSrcRow(s, animate);
      break;
    }
  }
  if (mounted) updateDeskHead();
}

/* ---- full render ---- */

function renderAll() {
  if (!liveEl) return;
  dropScene();
  liveEl.innerHTML = `
    <div class="dd-live">
      <div class="dd-live-main">
        <div class="dd-live-head">
          <button class="dd-back" type="button" title="${escapeHtml(t('dd.back'))}"
                  aria-label="${escapeHtml(t('dd.back'))}">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>
          </button>
          <span class="dd-live-titles">
            <span class="dd-live-title">
              <span class="dd-live-name">${escapeHtml(subject || '')}</span>
              <span class="dd-live-badge">${escapeHtml(t('dd.live.badge'))}</span>
            </span>
            <span class="dd-live-sub">${escapeHtml(t('dd.live.viewonly'))}</span>
          </span>
        </div>
        <div class="dd-live-done" hidden>${escapeHtml(t('dd.live.done'))}</div>
        <div class="dd-report dd-live-report"><div class="dd-live-doc"></div></div>
      </div>
      <section class="dd-desk${deskFolded ? ' is-folded' : ''}">
        <div class="dd-desk-head">
          <span class="dd-desk-title">${escapeHtml(t('dd.live.chat'))}</span>
          <span class="dd-desk-phase"></span>
          <span class="dd-desk-stats"></span>
          <div class="dd-desk-views">
            ${DESK_VIEWS.map(v => `<button type="button" class="dd-desk-view${v === deskView ? ' is-on' : ''}"
                    data-deskview="${v}">${escapeHtml(deskViewLabel(v))}</button>`).join('')}
          </div>
          <button class="dd-desk-fold" type="button"
                  aria-expanded="${deskFolded ? 'false' : 'true'}"
                  title="${escapeHtml(t('dd.live.chat.fold'))}"
                  aria-label="${escapeHtml(t('dd.live.chat.fold'))}">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
          </button>
        </div>
        <div class="dd-desk-body"></div>
      </section>
    </div>`;
  renderDoc();
  renderFigures();
  renderDesk();
  updateDeskHead();
}

function docEl() { return liveEl.querySelector('.dd-live-doc'); }

/** Has the report deck anything to show yet? */
function reportStarted() {
  return sections.some(s => s != null) || charts.length > 0;
}

function renderDoc() {
  const doc = docEl();
  if (!doc) return;
  if (!reportStarted()) {
    // The deck holds EDITING only, so before the first text there is nothing
    // up here but the ghost of the page to come — the legwork the desk is
    // doing meanwhile is laid out in the Redaktion below the rule.
    doc.innerHTML = `<div class="dd-live-empty">
      <span class="dd-live-empty-note">${escapeHtml(t('dd.live.empty'))}</span>
      <span class="dd-ghost" style="width:88%"></span>
      <span class="dd-ghost" style="width:97%"></span>
      <span class="dd-ghost" style="width:72%"></span>
    </div>`;
    return;
  }
  doc.innerHTML = '';
  for (let i = 0; i < SECTION_COUNT; i++) {
    if (sections[i] == null && !charts.some(f => f.section === i)) continue;
    doc.appendChild(sectionShell(i));
    if (sections[i] != null) setSectionFinal(i, sections[i]);
    applyCover(i);
  }
}

function sectionShell(i) {
  const el = document.createElement('section');
  el.className = 'dd-live-sec';
  el.dataset.sec = String(i);
  el.innerHTML = `<h2>${escapeHtml(t('dd.sec.' + i))}</h2>
    <div class="dd-live-body" data-sec="${i}"></div>
    <div class="dd-live-figs"></div>`;
  return el;
}

/** The section element, created in skeleton order on first need. */
function ensureSection(i) {
  const doc = docEl();
  if (!doc) return null;
  const empty = doc.querySelector('.dd-live-empty');
  if (empty) empty.remove();
  // The report takes the stage: the source room down in the register bows
  // out with one fade. Its register rows stay — they are the record.
  retireRoom();
  autoSwitchDesk();
  let el = doc.querySelector(`.dd-live-sec[data-sec="${i}"]`);
  if (el) return el;
  el = sectionShell(i);
  let before = null;
  for (const sib of doc.querySelectorAll('.dd-live-sec')) {
    if (Number(sib.dataset.sec) > i) { before = sib; break; }
  }
  doc.insertBefore(el, before);
  return el;
}

/* ---- the source register (Redaktion → "Quellen"): every source that was
      ever called for, with the verdict it earned. The animated source ROOM
      (dd-source-scene.js) runs above it while the desk is still gathering —
      each source lands as a paper card, the clerk carries it to the desk and
      files it or bins it — and bows out when the report takes the stage.
      The ROWS are permanent: a struck source used to slide out and be gone
      for good, which made this a ticker rather than a register. ---- */

function roomHtml() {
  // The room goes up before the first source does: commissioning the legwork
  // IS the collecting phase, and that is the longest wait of the whole run.
  return SCENE_OK && !reportStarted()
    ? '<div class="dd-triage"><div class="dd-scene"></div></div>' : '';
}

function registerHtml() {
  return `<div class="dd-register" data-filter="${srcFilter}">
    <div class="dd-reg-bar">
      <span class="dd-reg-count"></span>
      ${SRC_FILTERS.map(f => `<button type="button" data-srcfilter="${f}"
              class="dd-reg-filter${f === srcFilter ? ' is-on' : ''}"
              >${escapeHtml(t('dd.live.src.filter.' + f))}</button>`).join('')}
    </div>
    ${roomHtml()}
    <ul class="dd-reg-list"></ul>
    <div class="dd-reg-empty"${sources.length ? ' hidden' : ''}
      >${escapeHtml(t('dd.live.src.none'))}</div>
  </div>`;
}

/** Populates a freshly rendered register: the room, if it is up, and the rows. */
function fillRegister(host) {
  srcEls.clear();
  const box = host.querySelector('.dd-scene');
  if (box) {
    scene = createSourceScene(box);
    scene.seed(sources);
    if (phase) scene.phase(phase);
  }
  const list = host.querySelector('.dd-reg-list');
  for (const s of sources) list.appendChild(srcRowEl(s));
  updateSrcCounts();
}

/** The room's loop stops the moment the report needs the paint budget. */
function retireRoom() {
  const box = liveEl ? liveEl.querySelector('.dd-triage') : null;
  dropScene();
  if (!box || box.classList.contains('is-done')) return;
  box.classList.add('is-done');
  setTimeout(() => box.remove(), 420);
}

function srcRowEl(s) {
  const li = document.createElement('li');
  li.className = 'dd-src'
    + (s.state === 'ok' ? ' is-ok' : s.state === 'out' ? ' is-out' : '');
  li.innerHTML = `<span class="dd-src-dot">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 13 4 4L19 7"/></svg>
    </span>
    <span class="dd-src-title">${escapeHtml(s.title)}</span>`;
  srcEls.set(s.ref, li);
  return li;
}

/* A collect wave lands as one burst of entries — a small incremental delay
   per row lets the list visibly RUN IN instead of popping wholesale. */
let srcBurstAt = 0;
let srcBurstN = 0;

function addSrcRow(s, animate) {
  if (scene) scene.add(s, animate);
  const list = liveEl ? liveEl.querySelector('.dd-reg-list') : null;
  if (list) {
    const li = srcRowEl(s);
    if (animate) {
      const now = Date.now();
      if (now - srcBurstAt > 600) srcBurstN = 0;
      srcBurstAt = now;
      li.classList.add('is-new');
      li.style.animationDelay = Math.min(srcBurstN++ * 35, 900) + 'ms';
    }
    list.appendChild(li);
    const none = liveEl.querySelector('.dd-reg-empty');
    if (none) none.hidden = true;
  }
  updateSrcCounts();
}

/** A verdict lands: the row takes it in place and stays where it is. */
function updateSrcRow(s) {
  if (scene) scene.verdict(s);
  const li = srcEls.get(s.ref);
  if (li) {
    li.classList.remove('is-new');
    li.classList.toggle('is-ok', s.state === 'ok');
    li.classList.toggle('is-out', s.state === 'out');
  }
  updateSrcCounts();
}

function updateSrcCounts() {
  const chip = liveEl ? liveEl.querySelector('.dd-reg-count') : null;
  if (!chip) return;
  chip.textContent = srcCountLabel();
}

function srcCountLabel() {
  const ok = sources.filter(s => s.state === 'ok').length;
  const out = sources.filter(s => s.state === 'out').length;
  // Nothing judged yet: say what the desk is doing. A row of zeroes would
  // read as "nothing found" exactly while the fetching runs.
  if (!ok && !out) {
    return sources.length
      ? t('dd.live.src.arrived').replace('{n}', String(sources.length))
      : t('dd.live.src.calling');
  }
  return t('dd.live.src.counts')
    .replace('{n}', String(sources.length))
    .replace('{ok}', String(ok))
    .replace('{out}', String(out));
}

/* ---- the report mirror: paragraphs, typing, covers, margin notes ---- */

function paragraphsOf(text) {
  const s = (text || '').trim();
  return s ? s.split(/\n\s*\n/) : [];
}

function paraHtml(text) {
  return linkFigureRefs(renderMarkdown(text));
}

function paraBlock(i, text) {
  const el = document.createElement('div');
  el.className = 'dd-para';
  el.dataset.par = String(i);
  el.innerHTML = paraHtml(text);
  return el;
}

function bodyEl(sec) {
  return liveEl ? liveEl.querySelector(`.dd-live-body[data-sec="${sec}"]`) : null;
}

function paraEls(body) {
  return [...body.children].filter(el => el.classList.contains('dd-para'));
}

/** The section stands at its final text — no animation (mount, flush). */
function setSectionFinal(sec, text) {
  const secEl = ensureSection(sec);
  if (!secEl) return;
  const body = secEl.querySelector('.dd-live-body');
  body.innerHTML = '';
  paragraphsOf(text).forEach((p, i) => body.appendChild(paraBlock(i + 1, p)));
}

/* -- update queue: one animation at a time per section; stacking updates
      fast-forward (only the last one plays) -- */

const jobs = new Map(); // sec -> {queue: [{from,to,animate}], running}

function queueBody(sec, newText, animate) {
  const from = sections[sec];
  sections[sec] = newText;
  if (!mounted) return;
  let j = jobs.get(sec);
  if (!j) { j = { queue: [], running: false }; jobs.set(sec, j); }
  j.queue.push({ from, to: newText, animate: animate && !finalizing });
  if (!j.running) runJobs(sec, j);
}

async function runJobs(sec, j) {
  j.running = true;
  try {
    while (j.queue.length) {
      const job = j.queue.shift();
      // Stacked updates: everything but the newest lands instantly.
      if (j.queue.length || !job.animate) {
        setSectionFinal(sec, job.to);
      } else {
        await animateBody(sec, job.from || '', job.to);
      }
    }
  } finally {
    j.running = false;
    applyCover(sec);
  }
}

/** Everything queued or mid-flight snaps to the standing text. */
function flushSection(sec) {
  const j = jobs.get(sec);
  if (j) j.queue.length = 0;
  if (sections[sec] != null && bodyEl(sec)) setSectionFinal(sec, sections[sec]);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

/**
 * The human-typing illusion: paragraph-level LCS finds the changed loci —
 * kept paragraphs never repaint, removed ones fade out in place, new ones
 * type in, and a reworked one plays word-level (old words fade at the exact
 * spot, new words type in). Oversized diffs land instantly (runaway guard).
 */
async function animateBody(sec, fromText, toText) {
  const secEl = ensureSection(sec);
  if (!secEl) return;
  const body = secEl.querySelector('.dd-live-body');
  const a = paragraphsOf(fromText);
  const b = paragraphsOf(toText);
  if (a.length * b.length > 62_500) { setSectionFinal(sec, toText); return; }
  const ops = coalesce(lcsOps(a, b));

  // Existing blocks by old paragraph order (notes ride separately).
  const blocks = paraEls(body);
  if (blocks.length !== a.length) { setSectionFinal(sec, toText); return; }

  // Pass 1: removals fade out together at their spots.
  let ai = 0;
  const dying = [];
  for (const op of ops) {
    if (op.op === 'del') { for (let k = 0; k < op.a.length; k++) dying.push(blocks[ai + k]); }
    if (op.op !== 'add') ai += op.a.length;
  }
  if (dying.length) {
    dying.forEach(el => el.classList.add('dd-out'));
    await sleep(380);
    dying.forEach(el => el.remove());
  }

  // Pass 2: walk again — insert typed paragraphs / play word-level rework.
  ai = 0;
  let cursor = 0; // index into the SURVIVING block sequence
  const alive = paraEls(body);
  const at = idx => alive[idx] || null;
  for (const op of ops) {
    if (op.op === 'eq') {
      cursor += op.a.length;
      ai += op.a.length;
    } else if (op.op === 'del') {
      ai += op.a.length; // already gone
    } else if (op.op === 'add') {
      for (const par of op.b) {
        const el = document.createElement('div');
        el.className = 'dd-para dd-typing';
        body.insertBefore(el, at(cursor));
        alive.splice(cursor, 0, el);
        await typeText(el, par);
        el.classList.remove('dd-typing');
        el.innerHTML = paraHtml(par);
        cursor++;
      }
    } else { // chg: one reworked locus
      const el = at(cursor);
      if (el) {
        await animateRework(el, op.a.join('\n\n'), op.b.join('\n\n'));
        el.innerHTML = paraHtml(op.b.join('\n\n'));
      }
      cursor += 1;
      ai += op.a.length;
      // A chg that folded several paragraphs into fewer keeps block count
      // honest by re-rendering at the end (below).
    }
  }
  // Deterministic end state — the animation is presentation, never truth.
  setSectionFinal(sec, toText);
}

/** Types plain text into an empty block, word-chunked with a caret. */
async function typeText(el, text) {
  const words = text.split(/\s+/);
  const perTick = Math.max(1, Math.ceil(words.length / 80));
  let shown = 0;
  while (shown < words.length) {
    shown = Math.min(words.length, shown + perTick);
    el.innerHTML = escapeHtml(words.slice(0, shown).join(' '))
      + '<span class="dd-caret"></span>';
    await sleep(26);
  }
}

/** Word-level rework at the changed spot: old runs fade, new runs type. */
async function animateRework(el, fromText, toText) {
  const a = fromText.split(/\s+/);
  const b = toText.split(/\s+/);
  if (a.length * b.length > 250_000) return; // instant swap outside
  const ops = lcsOps(a, b);
  // Skeleton: kept words as text, old runs as fading spans, new runs empty.
  const parts = [];
  const adds = [];
  for (const op of ops) {
    if (op.op === 'eq') parts.push(escapeHtml(op.b.join(' ')));
    else if (op.op === 'del') parts.push(`<span class="dd-w-del">${escapeHtml(op.a.join(' '))}</span>`);
    else { const id = adds.length; adds.push(op.b); parts.push(`<span class="dd-w-add" data-add="${id}"></span>`); }
  }
  el.innerHTML = parts.join(' ');
  const dels = el.querySelectorAll('.dd-w-del');
  if (dels.length) {
    dels.forEach(s => s.classList.add('out'));
    await sleep(380);
    dels.forEach(s => s.remove());
  }
  for (const span of el.querySelectorAll('.dd-w-add')) {
    const words = adds[Number(span.dataset.add)] || [];
    const perTick = Math.max(1, Math.ceil(words.length / 50));
    let shown = 0;
    while (shown < words.length) {
      shown = Math.min(words.length, shown + perTick);
      span.innerHTML = escapeHtml(words.slice(0, shown).join(' '))
        + '<span class="dd-caret"></span>';
      await sleep(26);
    }
    span.innerHTML = escapeHtml(words.join(' '));
  }
}

/* -- LCS diff over token lists (paragraphs or words) -- */

function lcsOps(a, b) {
  const n = a.length, m = b.length;
  // One flat table keeps allocation cheap; callers cap n*m.
  const w = m + 1;
  const dp = new Int32Array((n + 1) * w);
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i * w + j] = a[i] === b[j]
        ? dp[(i + 1) * w + j + 1] + 1
        : Math.max(dp[(i + 1) * w + j], dp[i * w + j + 1]);
    }
  }
  const ops = [];
  const push = (op, tok) => {
    const last = ops[ops.length - 1];
    if (last && last.op === op) {
      if (op !== 'add') last.a.push(tok);
      if (op !== 'del') last.b.push(tok);
    } else {
      ops.push({
        op,
        a: op === 'add' ? [] : [tok],
        b: op === 'del' ? [] : [tok],
      });
    }
  };
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) { push('eq', b[j]); i++; j++; }
    else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) { push('del', a[i]); i++; }
    else { push('add', b[j]); j++; }
  }
  while (i < n) { push('del', a[i]); i++; }
  while (j < m) { push('add', b[j]); j++; }
  return ops;
}

/** Adjacent del+add (either order) is ONE reworked locus. */
function coalesce(ops) {
  const out = [];
  for (const op of ops) {
    const last = out[out.length - 1];
    if (last && ((last.op === 'del' && op.op === 'add') || (last.op === 'add' && op.op === 'del'))) {
      out[out.length - 1] = {
        op: 'chg',
        a: last.op === 'del' ? last.a : op.a,
        b: last.op === 'add' ? last.b : op.b,
      };
      continue;
    }
    out.push(op);
  }
  return out;
}

/* -- the judges' objections do NOT ride on the report deck. Up here is
      editing only: a margin rail beside the paragraph was a second copy of a
      note the desk already carries, and the margin it reserved came straight
      out of the report's width. Every objection lives in the Redaktion, on
      its locus' ticket; `notes[]` still tracks which ones stand, so a landed
      rework still clears them. -- */

/* -- the AI cover: the doubted locus blurred under the shimmer until its
      next standing state -- */

function applyCover(sec) {
  const body = bodyEl(sec);
  if (!body) return;
  body.classList.remove('dd-cover');
  body.querySelectorAll('.dd-para.dd-cover').forEach(el => el.classList.remove('dd-cover'));
  const p = pending[sec];
  if (!p) return;
  const blocks = paraEls(body);
  const target = p.par >= 1 && p.par <= blocks.length ? blocks[p.par - 1] : null;
  if (target) target.classList.add('dd-cover');
  else if (blocks.length) blocks[blocks.length - 1].classList.add('dd-cover');
  else body.classList.add('dd-cover');
}

/* ---- figures: slotted into their section the moment the layer exists ---- */

function renderFigures() {
  if (!liveEl || !charts.length) return;
  for (let i = 0; i < SECTION_COUNT; i++) {
    const figs = charts.map((f, idx) => ({ f, id: 'A' + (idx + 1) }))
      .filter(x => x.f.section === i);
    if (!figs.length) continue;
    const secEl = ensureSection(i);
    if (!secEl) continue;
    const host = secEl.querySelector('.dd-live-figs');
    if (host.childElementCount) continue; // figures land once per run
    host.innerHTML = figs.map(x => figureHtml(x.f, x.id)).join('');
    host.querySelectorAll('.dd-figure').forEach(el => el.classList.add('dd-fig-in'));
  }
  const report = liveEl.querySelector('.dd-live-report');
  if (report) { wireFigureHover(report); wireFigureZoom(report); }
}

/* ---- the Redaktion: one deck, three views ----
   The board is the default because it answers the question the stream never
   could: what is standing where. The stream keeps the clock, the register
   keeps the material. All three are built from the SAME entry list, and only
   the standing one is ever in the DOM. ---- */

function deskEl() { return liveEl ? liveEl.querySelector('.dd-desk') : null; }
function deskBodyEl() { return liveEl ? liveEl.querySelector('.dd-desk-body') : null; }

function deskViewLabel(v) {
  return v === 'sources' ? t('dd.live.sources') : t('dd.live.desk.' + v);
}

/** Every voice on the feed — the desk's whole material, nothing trimmed. */
function voicesOf() { return entries.filter(e => e.k === 'chat' || e.k === 'note'); }

/**
 * The locus an entry belongs to: its SECTION where the work is section-bound,
 * its PHASE where the house works on the report as a whole. That partition is
 * closed — every voice has exactly one locus, so nothing falls off the board.
 */
function locusOf(e) {
  return typeof e.sec === 'number' && e.sec >= 0 ? 's' + e.sec : 'p' + (e.ph || 'house');
}

/** A ticket is OPEN only while nothing at all has happened on its locus:
    under rework or merely talked about it is in progress, and it stands the
    moment a text does. */
function sectionState(i) {
  if (pending[i]) return 'work';
  if (sections[i] != null) return 'stands';
  return voicesOf().some(e => e.sec === i) ? 'work' : 'open';
}

/**
 * The board's tickets in a STABLE order — the report's skeleton first, the
 * house's own phases behind it. Nothing reorders on activity: a board that is
 * scanned again and again is worth more predictable than clever, and the
 * pulse plus the head carry the attention instead.
 */
function ticketsOf() {
  const byKey = new Map();
  const secs = [];
  for (let i = 0; i < SECTION_COUNT; i++) {
    // The full skeleton stands from the first second: an untouched section is
    // a quiet head-only card, so the board shows the PLAN and fills in.
    const tk = {
      key: 's' + i, no: '§' + (i + 1), title: t('dd.sec.' + i),
      msgs: [], notes: 0, changes: 0, state: sectionState(i),
    };
    byKey.set(tk.key, tk);
    secs.push(tk);
  }
  const house = [];
  for (const e of voicesOf()) {
    const key = locusOf(e);
    let tk = byKey.get(key);
    if (!tk) {
      tk = {
        key, no: '', ph: e.ph, title: phaseLabel(e.ph) || t('dd.live.ticket.house'),
        msgs: [], notes: 0, changes: 0, state: 'done',
      };
      byKey.set(key, tk);
      house.push(tk);
    }
    tk.msgs.push(e);
    if (e.k === 'note') tk.notes++;
    if (e.diff && e.diff.length) tk.changes++;
  }
  house.sort((a, b) => PHASES.indexOf(a.ph) - PHASES.indexOf(b.ph));
  for (const tk of house) if (tk.ph === phase) tk.state = 'work';
  return secs.concat(house);
}

/** One locus recounted — the cheap path when a single voice lands. */
function ticketAt(key) {
  const tk = { msgs: [], notes: 0, changes: 0 };
  for (const e of voicesOf()) {
    if (locusOf(e) !== key) continue;
    tk.msgs.push(e);
    if (e.k === 'note') tk.notes++;
    if (e.diff && e.diff.length) tk.changes++;
  }
  return tk;
}

const STAT_ICONS = {
  voices: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
  notes: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 22V4h15l-3 4 3 4H4"/></svg>',
  changes: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v10"/><path d="M7 9h10"/><path d="M7 20h10"/></svg>',
};

/** Voices, objections, changes — only the counts that are not zero. */
function statChips(tk) {
  return [['voices', tk.msgs.length], ['notes', tk.notes], ['changes', tk.changes]]
    .filter(([, n]) => n > 0)
    .map(([k, n]) => `<span class="dd-tstat${k === 'notes' ? ' is-note' : ''}"
        title="${escapeHtml(t('dd.live.ticket.' + k))}"
        >${STAT_ICONS[k]}<span>${n}</span></span>`)
    .join('');
}

function ticketHtml(tk) {
  const body = tk.msgs.length
    ? `<div class="dd-ticket-stats">${statChips(tk)}</div>
       <div class="dd-ticket-body">${tk.msgs.map(chatMsgHtml).join('')}</div>`
    : '';
  return `<article class="dd-ticket is-${tk.state}" data-locus="${tk.key}">
    <header class="dd-ticket-head">
      ${tk.no ? `<span class="dd-ticket-no">${escapeHtml(tk.no)}</span>` : ''}
      <span class="dd-ticket-title">${escapeHtml(tk.title)}</span>
      <span class="dd-ticket-state">${escapeHtml(t('dd.live.ticket.' + tk.state))}</span>
    </header>
    ${body}
  </article>`;
}

/** Builds the standing view from scratch. The room's canvas dies with any
    rebuild, so its loop is stopped FIRST — nothing paints off-screen. */
function renderDesk() {
  const host = deskBodyEl();
  if (!host) return;
  dropScene();
  if (deskFolded) { host.innerHTML = ''; return; }
  if (deskView === 'sources') {
    host.innerHTML = registerHtml();
    fillRegister(host);
    return;
  }
  const msgs = voicesOf();
  if (deskView === 'stream') {
    host.innerHTML = msgs.length
      ? `<div class="dd-stream">${msgs.map(chatMsgHtml).join('')}</div>`
      : deskEmptyHtml();
    return;
  }
  host.innerHTML = `<div class="dd-board">${ticketsOf().map(ticketHtml).join('')}</div>`;
}

function deskEmptyHtml() {
  return `<div class="dd-desk-empty">${escapeHtml(t('dd.live.desk.empty'))}</div>`;
}

/** A voice lands: it goes to its place in the standing view, and the head's
    running totals move whatever is on screen. */
function fileEntry(e) {
  updateDeskHead();
  const host = deskBodyEl();
  if (!host || deskFolded) return;
  if (deskView === 'stream') {
    const lane = host.querySelector('.dd-stream');
    if (!lane) { renderDesk(); return; }
    lane.insertAdjacentHTML('beforeend', chatMsgHtml(e));
    return;
  }
  if (deskView !== 'tickets') return;
  const key = locusOf(e);
  const card = host.querySelector(`.dd-ticket[data-locus="${key}"]`);
  // A house ticket that has never been seen: only then is a full rebuild
  // needed, and that is once per phase over a whole run.
  if (!card) { renderDesk(); pulseTicket(key); return; }
  let body = card.querySelector('.dd-ticket-body');
  if (!body) {
    // First voice on a locus: the quiet head-only card grows its body in
    // place, so no other card loses its scroll or its opened attachment.
    card.insertAdjacentHTML('beforeend',
      '<div class="dd-ticket-stats"></div><div class="dd-ticket-body"></div>');
    body = card.querySelector('.dd-ticket-body');
    if (key[0] === 's') refreshTicketState(Number(key.slice(1)));
    else { card.classList.remove('is-open', 'is-done'); card.classList.add('is-work'); }
  }
  const near = body.scrollHeight - body.scrollTop - body.clientHeight < 100;
  body.insertAdjacentHTML('beforeend', chatMsgHtml(e));
  if (near) body.scrollTop = body.scrollHeight;
  const stats = card.querySelector('.dd-ticket-stats');
  if (stats) stats.innerHTML = statChips(ticketAt(key));
  pulseTicket(key);
}

/** Per-locus timers for the landing flash. */
const pulses = new Map();

/** ONE finite flash on the ticket a voice just landed on — driven by a real
    state change, never an idling loop (OSR paint rule). */
function pulseTicket(key) {
  const card = liveEl ? liveEl.querySelector(`.dd-ticket[data-locus="${key}"]`) : null;
  if (!card) return;
  card.classList.remove('is-live');
  void card.offsetWidth; // restart the one-shot
  card.classList.add('is-live');
  clearTimeout(pulses.get(key));
  pulses.set(key, setTimeout(() => card.classList.remove('is-live'), 1500));
}

/** A section changed its standing: its ticket says so without a rebuild. */
function refreshTicketState(sec) {
  if (deskView !== 'tickets' || !liveEl) return;
  const card = liveEl.querySelector(`.dd-ticket[data-locus="s${sec}"]`);
  if (!card) return;
  const state = sectionState(sec);
  card.classList.remove('is-open', 'is-work', 'is-stands', 'is-done');
  card.classList.add('is-' + state);
  const chip = card.querySelector('.dd-ticket-state');
  if (chip) chip.textContent = t('dd.live.ticket.' + state);
}

/** Phase and running totals — the head is sticky, so this is the one line
    that never leaves the frame however far the board is scrolled. */
function updateDeskHead() {
  if (!liveEl) return;
  const chip = liveEl.querySelector('.dd-desk-phase');
  if (chip) chip.textContent = phaseLabel(phase);
  const stats = liveEl.querySelector('.dd-desk-stats');
  if (!stats) return;
  const msgs = voicesOf();
  let notesN = 0;
  let changes = 0;
  for (const e of msgs) {
    if (e.k === 'note') notesN++;
    if (e.diff && e.diff.length) changes++;
  }
  stats.textContent = t('dd.live.desk.stats')
    .replace('{v}', String(msgs.length))
    .replace('{n}', String(notesN))
    .replace('{c}', String(changes))
    .replace('{s}', String(sources.length));
}

/** Where the desk looks while nobody has told it otherwise: at the material
    as long as the material is all there is, at the board once text exists. */
function defaultDeskView() { return reportStarted() ? 'tickets' : 'sources'; }

function markDeskView() {
  if (!liveEl) return;
  for (const b of liveEl.querySelectorAll('.dd-desk-view')) {
    b.classList.toggle('is-on', b.dataset.deskview === deskView);
  }
}

function setDeskView(v) {
  if (!DESK_VIEWS.includes(v) || v === deskView) return;
  deskView = v;
  deskViewPinned = true; // a hand on the switch outranks any default
  markDeskView();
  renderDesk();
}

/** The first text lands: the run has moved from gathering to writing, so the
    desk follows it — unless the reader has picked a view themselves. */
function autoSwitchDesk() {
  if (deskViewPinned || deskView === 'tickets' || !reportStarted()) return;
  deskView = 'tickets';
  markDeskView();
  renderDesk();
}

function setSrcFilter(f) {
  if (!SRC_FILTERS.includes(f) || f === srcFilter) return;
  srcFilter = f;
  const reg = liveEl ? liveEl.querySelector('.dd-register') : null;
  if (!reg) return;
  reg.dataset.filter = f;
  for (const b of reg.querySelectorAll('.dd-reg-filter')) {
    b.classList.toggle('is-on', b.dataset.srcfilter === f);
  }
}

function toggleDesk() {
  const desk = deskEl();
  if (!desk) return;
  deskFolded = !deskFolded;
  desk.classList.toggle('is-folded', deskFolded);
  const btn = desk.querySelector('.dd-desk-fold');
  if (btn) btn.setAttribute('aria-expanded', deskFolded ? 'false' : 'true');
  renderDesk();
}

function chatMsgHtml(e) {
  const secTag = typeof e.sec === 'number' && e.sec >= 0
    ? `<span class="dd-msg-sec">${escapeHtml(t('dd.sec.' + e.sec))}</span>` : '';
  // A message WITH a diff attachment shows only the attachment — its text
  // is the very passage the diff carries (redundant and space-hungry).
  const hasDiff = e.diff && e.diff.length;
  return `<div class="dd-msg${e.k === 'note' ? ' is-note' : ''}">
    <div class="dd-msg-head">
      <span class="dd-msg-who">${escapeHtml(participantLabel(e.who))}</span>
      ${secTag}
      <span class="dd-msg-phase">${escapeHtml(phaseLabel(e.ph))}</span>
    </div>
    ${hasDiff ? attachHtml(e.diff)
      : `<div class="dd-msg-text">${escapeHtml(e.t || '')}</div>`}
  </div>`;
}

/** The diff attachment: folded by default, looks like a file attachment. */
function attachHtml(diff) {
  const adds = diff.filter(l => l.k === 'add').length;
  const dels = diff.filter(l => l.k === 'del').length;
  const lines = diff.map(l => {
    if (l.k === 'gap') return '<div class="dd-dl dd-dl-gap">⋯</div>';
    const g = l.k === 'add' ? (l.n || '') : l.k === 'del' ? (l.o || '') : (l.n || l.o || '');
    return `<div class="dd-dl dd-dl-${escapeHtml(l.k)}">
      <span class="dd-dl-g">${escapeHtml(String(g))}</span>
      <span class="dd-dl-t">${escapeHtml(l.t || '')}</span>
    </div>`;
  }).join('');
  return `<div class="dd-attach">
    <button class="dd-attach-head" type="button">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
      <span>${escapeHtml(t('dd.live.attach'))}</span>
      <span class="dd-attach-stat">${adds ? `+${adds}` : ''} ${dels ? `−${dels}` : ''}</span>
      <svg class="dd-attach-chev" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
    </button>
    <div class="dd-attach-body" hidden>${lines}</div>
  </div>`;
}

function participantLabel(who) {
  if (!who) return '';
  const key = 'dd.who.' + who;
  const label = t(key);
  return label === key ? who : label;
}

function phaseLabel(ph) {
  if (!ph) return '';
  const key = 'dd.ph.' + ph;
  const label = t(key);
  return label === key ? ph : label;
}

/* ---- events (view-only: back, desk view/fold, register filter,
       attachment fold) ---- */

function onClick(e) {
  if (e.target.closest('.dd-back')) {
    if (onBack) onBack();
    return;
  }
  const view = e.target.closest('.dd-desk-view');
  if (view) { setDeskView(view.dataset.deskview); return; }
  const filter = e.target.closest('.dd-reg-filter');
  if (filter) { setSrcFilter(filter.dataset.srcfilter); return; }
  if (e.target.closest('.dd-desk-head')) {
    // The whole head is the fold handle — the switches above take their
    // clicks first, so only the bare head folds the deck.
    toggleDesk();
    return;
  }
  const head = e.target.closest('.dd-attach-head');
  if (head) {
    const bodyEl = head.parentElement.querySelector('.dd-attach-body');
    if (bodyEl) {
      bodyEl.hidden = !bodyEl.hidden;
      head.parentElement.classList.toggle('is-open', !bodyEl.hidden);
    }
  }
}
