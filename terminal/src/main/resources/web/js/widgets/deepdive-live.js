// KI-DD live workshop view ("Blick in die Box") — the running report opened
// mid-generation. STRICTLY view-only: the user watches the desk work, never
// talks back. Socket contract: topic `deepdive-live` streams `{entry}` /
// `{charts}` increments (see DeepDiveBridge.liveJson: k=chat|body|note|
// pending|settled, ph=phase token, who=participant token, sec/par=locus,
// t=FULL text, diff=[{k,t,o,n}]); `{command:"live"}` answers the whole
// backlog on `deepdive-live-backlog` `{busy, subject, entries, charts}`.
//
// The stage: a live REPORT MIRROR (the standing section texts, rendered like
// the finished page, figures slotted in the moment they exist) beside a
// collapsible desk CHAT (every participant's full message, its diff folded
// away as an attachment). Text edits play as human-like typing — deletions
// fade out at the changed spot, insertions type in; a locus a judge doubts
// wears a blurred AI cover until its next standing state. Judge objections
// hang as amber margin notes beside their paragraph (teacher-on-an-exam
// look). Everything here is PREVIEW: subtly badged, and none of it survives
// into the finished report page — the finalize transition clears the
// workshop and hands over to the ordinary detail view (deepdive.js).
//
// Animation discipline (OSR paint rule): typing and fades are FINITE and
// driven by timeouts on real state changes; the only loop is the sanctioned
// skeleton shimmer on covers/ghosts, which exists only while the desk works.

import { t } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { renderMarkdown } from '../format/markdown.js';
import { wireFigureHover } from '../map/figure-hover.js';
import { figureHtml, linkFigureRefs } from './dd-figures.js';

const SECTION_COUNT = 8;

let sock = null;
let liveEl = null;
/** deepdive.js's "leave the box" callback (back arrow). */
let onBack = null;

/* ---- run state, accumulated from every increment (also while the box is
   closed, so opening replays instantly; the backlog command covers gaps) ---- */

let entries = [];                  // every entry, seq-ordered (the chat's source)
let seenSeq = 0;                   // highest seq applied
let sections = emptySections();    // per index: current standing text | null
let notes = emptyNotes();          // per index: [{par, who, text}]
let pending = emptyPending();      // per index: {par} | null
let charts = [];                   // [{section, title, note, svg}]
/** The triage board: every collected source with its live verdict state. */
let sources = [];                  // [{ref, title, state:'pending'|'ok'|'out'}]
let srcIndex = new Map();          // ref -> source object
let srcEls = new Map();            // ref -> its <li> while the board stands
let phase = null;                  // latest phase token
let subject = null;
let mounted = false;
let chatOpen = true;
let finalizing = false;

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
  sections = emptySections();
  notes = emptyNotes();
  pending = emptyPending();
  charts = [];
  sources = [];
  srcIndex = new Map();
  srcEls = new Map();
  phase = null;
  subject = null;
  finalizing = false;
  jobs.clear();
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
  // A gap while watching means missed increments (reconnect) — replay whole.
  if (mounted && seenSeq > 0 && e.seq > seenSeq + 1) requestBacklog();
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
  phase = null;
  jobs.clear();
  seenSeq = 0;
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

export function requestBacklog() {
  if (sock) sock.send('deepdive', { command: 'live' });
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
  renderAll();
}

export function unmountLive() {
  mounted = false;
  jobs.clear();
  if (liveEl) liveEl.innerHTML = '';
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
  if (e.ph) phase = e.ph;
  const sec = typeof e.sec === 'number' ? e.sec : -1;
  switch (e.k) {
    case 'body':
      if (sec >= 0) {
        pending[sec] = null;
        // A landed rework answers the standing objections on this section —
        // its margin notes are resolved and leave the rail.
        notes[sec] = [];
        queueBody(sec, e.t || '', animate);
      }
      break;
    case 'pending':
      if (sec >= 0) {
        pending[sec] = { par: typeof e.par === 'number' ? e.par : 0 };
        if (mounted) applyCover(sec);
      }
      break;
    case 'settled':
      if (sec >= 0) {
        pending[sec] = null;
        if (mounted) applyCover(sec);
      }
      break;
    case 'note':
      if (sec >= 0) {
        notes[sec].push({ par: e.par || 0, who: e.who || '', text: e.t || '' });
        if (mounted) hangNotes(sec);
      }
      if (mounted) appendChat(e);
      break;
    case 'chat':
      if (mounted) appendChat(e);
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
  if (mounted) updatePhaseChip();
}

/* ---- full render ---- */

function renderAll() {
  if (!liveEl) return;
  liveEl.innerHTML = `
    <div class="dd-live${chatOpen ? ' chat-open' : ''}">
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
        <button class="dd-chat-toggle${chatOpen ? ' is-on' : ''}" type="button"
                title="${escapeHtml(t('dd.live.chat.toggle'))}"
                aria-label="${escapeHtml(t('dd.live.chat.toggle'))}">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        </button>
      </div>
      <div class="dd-live-done" hidden>${escapeHtml(t('dd.live.done'))}</div>
      <div class="dd-live-stage">
        <div class="dd-report dd-live-report"><div class="dd-live-doc"></div></div>
        <aside class="dd-live-chat" ${chatOpen ? '' : 'hidden'}>
          <div class="dd-chat-head">
            <span class="dd-chat-title">${escapeHtml(t('dd.live.chat'))}</span>
            <span class="dd-chat-phase"></span>
          </div>
          <div class="dd-chat-list"></div>
        </aside>
      </div>
    </div>`;
  renderDoc();
  renderFigures();
  renderChatAll();
  updatePhaseChip();
}

function docEl() { return liveEl.querySelector('.dd-live-doc'); }

function renderDoc() {
  const doc = docEl();
  if (!doc) return;
  srcEls.clear();
  const any = sections.some(s => s != null) || charts.length;
  if (!any) {
    // Before the first text: the triage board (the collected sources under
    // the judge's pen) — or, before anything is collected, ghost lines.
    if (sources.length) {
      doc.innerHTML = triageBoardHtml();
      const list = doc.querySelector('.dd-triage-list');
      for (const s of sources) {
        if (s.state === 'out') continue; // struck rows are gone for good
        list.appendChild(srcRowEl(s));
      }
      updateSrcCounts();
    } else {
      doc.innerHTML = `<div class="dd-live-empty">
        <span class="dd-live-empty-note">${escapeHtml(t('dd.live.empty'))}</span>
        <span class="dd-ghost" style="width:88%"></span>
        <span class="dd-ghost" style="width:97%"></span>
        <span class="dd-ghost" style="width:72%"></span>
      </div>`;
    }
    return;
  }
  doc.innerHTML = '';
  for (let i = 0; i < SECTION_COUNT; i++) {
    if (sections[i] == null && !charts.some(f => f.section === i)) continue;
    doc.appendChild(sectionShell(i));
    if (sections[i] != null) setSectionFinal(i, sections[i]);
    hangNotes(i);
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
  // The report takes the stage: the triage board bows out with one fade.
  const board = doc.querySelector('.dd-triage');
  if (board && !board.classList.contains('is-done')) {
    board.classList.add('is-done');
    srcEls.clear();
    setTimeout(() => board.remove(), 420);
  }
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

/* ---- the triage board: the collected sources under the judge's pen —
      rows slide in as they are found, earn a green check, or are struck
      through, slide out left and the list closes the gap ---- */

function triageBoardHtml() {
  return `<div class="dd-triage">
    <div class="dd-triage-head">
      <span class="dd-triage-title">${escapeHtml(t('dd.live.sources'))}</span>
      <span class="dd-triage-count"></span>
    </div>
    <ul class="dd-triage-list"></ul>
  </div>`;
}

function srcRowEl(s) {
  const li = document.createElement('li');
  li.className = 'dd-src' + (s.state === 'ok' ? ' is-ok' : '');
  li.innerHTML = `<span class="dd-src-dot">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 13 4 4L19 7"/></svg>
    </span>
    <span class="dd-src-title">${escapeHtml(s.title)}</span>`;
  srcEls.set(s.ref, li);
  return li;
}

function addSrcRow(s, animate) {
  const doc = docEl();
  if (!doc) return;
  let list = doc.querySelector('.dd-triage-list');
  if (!list) {
    // First source while the ghost stands (or a follow-up wave after the
    // board was rebuilt): a full doc render puts the board up.
    if (sections.some(x => x != null) || charts.length) return; // report stage already
    renderDoc();
    return;
  }
  const li = srcRowEl(s);
  if (animate) li.classList.add('is-new');
  list.appendChild(li);
  updateSrcCounts();
}

function updateSrcRow(s, animate) {
  const li = srcEls.get(s.ref);
  updateSrcCounts();
  if (!li || !li.isConnected) return;
  li.classList.remove('is-new');
  if (s.state === 'ok') {
    li.classList.add('is-ok');
    return;
  }
  // Struck: strike-through first (readable for a beat), then slide out left,
  // fade and collapse — the list closes the gap smoothly.
  srcEls.delete(s.ref);
  if (!animate) { li.remove(); return; }
  li.classList.remove('is-ok');
  li.classList.add('is-out');
  setTimeout(() => {
    li.style.maxHeight = li.offsetHeight + 'px';
    requestAnimationFrame(() => li.classList.add('is-gone'));
    setTimeout(() => li.remove(), 480);
  }, 700);
}

function updateSrcCounts() {
  const chip = liveEl ? liveEl.querySelector('.dd-triage-count') : null;
  if (!chip) return;
  const ok = sources.filter(s => s.state === 'ok').length;
  const out = sources.filter(s => s.state === 'out').length;
  chip.textContent = t('dd.live.src.counts')
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
    hangNotes(sec);
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

/* -- margin notes (teacher-on-an-exam): amber asides on a rail OUTSIDE the
      report page, aligned to their anchored paragraph — the report's layout
      never shifts. The rail shows only while the chat is folded (both want
      the same edge, and the reader focuses on one); a section's notes leave
      when its next rework lands (the objection is answered). -- */

function hangNotes(_sec) {
  renderNotesRail();
}

function renderNotesRail() {
  if (!liveEl) return;
  const report = liveEl.querySelector('.dd-live-report');
  const root = liveEl.querySelector('.dd-live');
  if (!report || !root) return;
  let rail = report.querySelector('.dd-note-rail');
  const items = [];
  for (let sec = 0; sec < SECTION_COUNT; sec++) {
    const body = bodyEl(sec);
    if (!body) continue;
    const blocks = paraEls(body);
    for (const note of notes[sec]) {
      const anchor = note.par >= 1 && note.par <= blocks.length
        ? blocks[note.par - 1] : body;
      items.push({ note, anchor });
    }
  }
  root.classList.toggle('has-notes', items.length > 0);
  if (!items.length) {
    if (rail) rail.remove();
    return;
  }
  if (!rail) {
    rail = document.createElement('div');
    rail.className = 'dd-note-rail';
    report.appendChild(rail);
  }
  rail.innerHTML = '';
  const els = items.map(({ note, anchor }) => {
    const el = document.createElement('aside');
    el.className = 'dd-note';
    el.innerHTML = `<span class="dd-note-who">${escapeHtml(participantLabel(note.who))}</span>
      <span class="dd-note-text">${escapeHtml(note.text)}</span>`;
    // offsetTop is relative to the positioned report container.
    el.dataset.top = String(anchor.offsetTop || 0);
    rail.appendChild(el);
    return el;
  });
  // Second pass after layout: pin each note beside its paragraph, pushing
  // down only to avoid overlapping the previous note.
  requestAnimationFrame(() => {
    let nextFree = 0;
    for (const el of els) {
      const want = Number(el.dataset.top) || 0;
      const top = Math.max(want, nextFree);
      el.style.top = top + 'px';
      nextFree = top + el.offsetHeight + 8;
    }
  });
}

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
  if (report) wireFigureHover(report);
}

/* ---- the desk chat: every voice, nothing trimmed ---- */

function chatListEl() { return liveEl.querySelector('.dd-chat-list'); }

function renderChatAll() {
  const list = chatListEl();
  if (!list) return;
  list.innerHTML = entries.filter(e => e.k === 'chat' || e.k === 'note')
    .map(chatMsgHtml).join('');
  list.scrollTop = list.scrollHeight;
}

function appendChat(e) {
  const list = chatListEl();
  if (!list) return;
  const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 120;
  list.insertAdjacentHTML('beforeend', chatMsgHtml(e));
  if (nearBottom) list.scrollTop = list.scrollHeight;
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

function updatePhaseChip() {
  const chip = liveEl ? liveEl.querySelector('.dd-chat-phase') : null;
  if (chip) chip.textContent = phaseLabel(phase);
}

/* ---- events (view-only: back, chat toggle, attachment fold) ---- */

function onClick(e) {
  if (e.target.closest('.dd-back')) {
    if (onBack) onBack();
    return;
  }
  const toggle = e.target.closest('.dd-chat-toggle');
  if (toggle) {
    chatOpen = !chatOpen;
    toggle.classList.toggle('is-on', chatOpen);
    const root = liveEl.querySelector('.dd-live');
    if (root) root.classList.toggle('chat-open', chatOpen);
    const aside = liveEl.querySelector('.dd-live-chat');
    if (aside) {
      aside.hidden = !chatOpen;
      if (chatOpen) {
        const list = chatListEl();
        if (list) list.scrollTop = list.scrollHeight;
      }
    }
    // The margin-note rail takes the freed edge — re-pin at the new widths.
    renderNotesRail();
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
