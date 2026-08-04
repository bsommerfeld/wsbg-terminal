// Erwähnungs-Zähler: what the cage talks about, counted mechanically.
//
// Two halves, deliberately unequal. OURS is the widget — our own count out of
// every line r/wallstreetbetsGER writes, addressable over any span of time.
// THEIRS sits below a hard rule: two foreign WSB rankings, shown exactly as
// their sources publish them (their window, their method), because a number we
// did not measure must not be dressed up as one we did.
//
// The time control is a panorama viewport over the whole history: drag the
// window to travel, drag an edge to widen, wheel to zoom. Every change re-asks
// the backend for that span — day, week, month, year, all one view.

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';

const DAY_MS = 86400000;
const REQUEST_DEBOUNCE_MS = 90;
const REFRESH_MS = 60000;

let socket = null;
let detail = null;
let thumb = null;
let requestTimer = null;
let dragging = false;

// The full picture as the backend last described it. `days` is the DENSE
// timeline (one slot per calendar day, gaps filled with zero) the panorama
// rides over; `sel` is the visible window as indices into it.
const state = {
  days: [],
  sel: { from: 0, to: 0 },
  rows: [],
  total: 0,
  // The grid card is always TODAY, never the open window — it ships alongside
  // every answer so the card can never drift into labelling a month as a day.
  todayTop: [],
  todayTotal: 0,
  today: null,
  earliest: null,
  loading: false,
  sources: null,
  sourcesAsked: false,
};

// ---------------------------------------------------------------- dates

function toDate(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return Date.UTC(y, m - 1, d);
}

function toIso(ms) {
  return new Date(ms).toISOString().slice(0, 10);
}

function daysBetween(fromIso, toIso_) {
  return Math.round((toDate(toIso_) - toDate(fromIso)) / DAY_MS);
}

/** The dense day axis: every calendar day from the oldest count to today. */
function buildDays(timeline, earliest, today) {
  if (!today) return [];
  const first = earliest || today;
  const span = Math.max(0, daysBetween(first, today));
  const counts = new Map((timeline || []).map(d => [d.day, d]));
  const out = [];
  for (let i = 0; i <= span; i++) {
    const iso = toIso(toDate(first) + i * DAY_MS);
    const hit = counts.get(iso);
    out.push({ iso, total: hit ? hit.total : 0, items: hit ? hit.items : 0 });
  }
  return out;
}

function fmtDay(iso) {
  return new Date(toDate(iso)).toLocaleDateString(
    currentLang() === 'en' ? 'en-GB' : 'de-DE',
    { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
}

function fmtDayShort(iso) {
  return new Date(toDate(iso)).toLocaleDateString(
    currentLang() === 'en' ? 'en-GB' : 'de-DE',
    { day: '2-digit', month: '2-digit', timeZone: 'UTC' });
}

function fmtInt(n) {
  return (n || 0).toLocaleString(currentLang() === 'en' ? 'en-GB' : 'de-DE');
}

/** The window's headline: one day by name, a span as "from - to". */
function windowLabel() {
  const days = state.days;
  if (!days.length) return '';
  const a = days[state.sel.from], b = days[state.sel.to];
  if (!a || !b) return '';
  if (a.iso === b.iso) {
    return a.iso === state.today ? t('mv.today') + ' · ' + fmtDay(a.iso) : fmtDay(a.iso);
  }
  return fmtDay(a.iso) + ' - ' + fmtDay(b.iso);
}

// ---------------------------------------------------------------- requests

function askWindow() {
  if (!socket || !state.days.length) return;
  const from = state.days[state.sel.from], to = state.days[state.sel.to];
  if (!from || !to) return;
  clearTimeout(requestTimer);
  requestTimer = setTimeout(() => {
    state.loading = true;
    socket.send('mentions', { command: 'window', from: from.iso, to: to.iso });
  }, REQUEST_DEBOUNCE_MS);
}

function askSources() {
  if (!socket || state.sourcesAsked) return;
  state.sourcesAsked = true;
  socket.send('mentions', { command: 'sources' });
}

// ---------------------------------------------------------------- panorama

/**
 * The panorama track: one thin bar per day (the room's volume over the whole
 * history) with the visible window laid over it. Bars are a single recessive
 * measure — no legend, no per-bar labels; the selection carries the reading.
 */
function trackHtml() {
  const days = state.days;
  if (!days.length) return '';
  const peak = Math.max(1, ...days.map(d => d.total));
  const bars = days.map((d, i) => {
    const h = d.total ? Math.max(6, Math.round((d.total / peak) * 100)) : 2;
    const inside = i >= state.sel.from && i <= state.sel.to;
    return `<span class="mv-bar${inside ? ' in' : ''}" style="height:${h}%"
            data-i="${i}" title="${escapeHtml(fmtDayShort(d.iso))} · ${fmtInt(d.total)}"></span>`;
  }).join('');
  const left = (state.sel.from / days.length) * 100;
  const width = ((state.sel.to - state.sel.from + 1) / days.length) * 100;
  return `
    <div class="mv-track" role="group" aria-label="${escapeHtml(t('mv.track.aria'))}">
      <div class="mv-bars">${bars}</div>
      <div class="mv-window" style="left:${left}%;width:${width}%">
        <span class="mv-grip mv-grip-l" data-grip="l" aria-hidden="true"></span>
        <span class="mv-grip mv-grip-r" data-grip="r" aria-hidden="true"></span>
      </div>
    </div>`;
}

const PRESETS = [
  { key: 'day', days: 1 },
  { key: 'week', days: 7 },
  { key: 'month', days: 30 },
  { key: 'year', days: 365 },
];

function presetsHtml() {
  const len = state.sel.to - state.sel.from + 1;
  return PRESETS.map(p => {
    const on = len === Math.min(p.days, state.days.length) ? ' on' : '';
    return `<button type="button" class="mv-preset${on}" data-preset="${p.days}">${
      escapeHtml(t('mv.span.' + p.key))}</button>`;
  }).join('');
}

// ---------------------------------------------------------------- rows

/**
 * One ranked row: rank, ticker, name, a magnitude bar and the count. The bar
 * is the only colored mark; every figure stays in text ink. An unresolved
 * spelling is marked by a "?" badge and its own muted bar — never by color
 * alone.
 */
function rowsHtml() {
  if (!state.rows.length) {
    return `<div class="mv-empty">${escapeHtml(t('mv.empty'))}</div>`;
  }
  const peak = Math.max(1, ...state.rows.map(r => r.mentions));
  return state.rows.map((r, i) => {
    const pct = Math.max(2, Math.round((r.mentions / peak) * 100));
    const head = r.resolved
      ? `<span class="mv-sym">${escapeHtml(r.symbol)}</span>`
      : `<span class="mv-sym mv-open" title="${escapeHtml(t('mv.unresolved.hint'))}">?</span>`;
    const name = r.resolved && r.label !== r.symbol ? r.label : (r.resolved ? '' : r.label);
    return `
      <li class="mv-row${r.resolved ? '' : ' unresolved'}">
        <span class="mv-rank">${i + 1}</span>
        ${head}
        <span class="mv-name">${escapeHtml(name)}</span>
        <span class="mv-bar-cell"><span class="mv-bar-fill" style="width:${pct}%"></span></span>
        <span class="mv-count">${fmtInt(r.mentions)}</span>
      </li>`;
  }).join('');
}

// ---------------------------------------------------------------- neighbours

function sourcesHtml() {
  const s = state.sources;
  if (!s || !Array.isArray(s.sources)) {
    return `<div class="mv-src-wait">${escapeHtml(t('mv.sources.loading'))}</div>`;
  }
  return s.sources.map(src => {
    const rows = (src.rows || []).slice(0, 12);
    const body = rows.length
      ? `<ol class="mv-src-list">${rows.map((r, i) => `
          <li class="mv-src-row">
            <span class="mv-rank">${i + 1}</span>
            <span class="mv-sym">${escapeHtml(r.symbol || '')}</span>
            <span class="mv-name">${escapeHtml(r.name || '')}</span>
            <span class="mv-count">${fmtInt(r.mentions)}</span>
          </li>`).join('')}</ol>`
      : `<div class="mv-src-wait">${escapeHtml(t('mv.sources.none'))}</div>`;
    return `
      <section class="mv-src">
        <header class="mv-src-head">
          <span class="mv-src-name">${escapeHtml(t('mv.src.' + src.id))}</span>
          <span class="mv-src-meta">${escapeHtml(t('mv.scope.' + src.scope))} · ${
            escapeHtml(t('mv.win.' + src.window))}</span>
        </header>
        ${body}
      </section>`;
  }).join('');
}

// ---------------------------------------------------------------- painting

function paint() {
  if (!detail) return;
  const stepping = state.sel.from === state.sel.to;
  detail.innerHTML = `
    <div class="mv-ours${state.loading ? ' loading' : ''}">
      <div class="mv-controls">
        <div class="mv-presets">${presetsHtml()}</div>
        <div class="mv-step">
          <button type="button" class="mv-nav" data-step="-1" aria-label="${
            escapeHtml(t('mv.prev'))}" title="${escapeHtml(t('mv.prev'))}">‹</button>
          <button type="button" class="mv-nav" data-step="1" aria-label="${
            escapeHtml(t('mv.next'))}" title="${escapeHtml(t('mv.next'))}"${
            state.sel.to >= state.days.length - 1 ? ' disabled' : ''}>›</button>
        </div>
      </div>
      ${trackHtml()}
      <div class="mv-window-head">
        <span class="mv-when">${escapeHtml(windowLabel())}</span>
        <span class="mv-sum">${fmtInt(state.total)} ${escapeHtml(
          state.total === 1 ? t('mv.mention') : t('mv.mentions'))}${
          stepping ? '' : ' · ' + escapeHtml(t('mv.spanDays').replace('{n}',
            fmtInt(state.sel.to - state.sel.from + 1)))}</span>
      </div>
      <ol class="mv-rows">${rowsHtml()}</ol>
    </div>
    <div class="mv-fence" role="separator"><span>${escapeHtml(t('mv.fence'))}</span></div>
    <div class="mv-theirs">
      <p class="mv-theirs-note">${escapeHtml(t('mv.theirs.note'))}</p>
      ${sourcesHtml()}
    </div>`;
  paintThumb();
}

/**
 * The grid card shows OURS only — today's leader and the day's volume. The
 * neighbours are context you open the widget for, never the headline.
 */
/**
 * The cheap in-gesture update: move the window, re-shade the bars inside it and
 * rewrite the reading — without touching the track element itself.
 */
function syncWindow() {
  if (!detail || !state.days.length) return;
  const win = detail.querySelector('.mv-window');
  if (win) {
    win.style.left = (state.sel.from / state.days.length) * 100 + '%';
    win.style.width = ((state.sel.to - state.sel.from + 1) / state.days.length) * 100 + '%';
  }
  detail.querySelectorAll('.mv-bar').forEach(bar => {
    const i = Number(bar.dataset.i);
    bar.classList.toggle('in', i >= state.sel.from && i <= state.sel.to);
  });
  const when = detail.querySelector('.mv-when');
  if (when) when.textContent = windowLabel();
}

/** The rows + the reading only — used while a drag holds the track in place. */
function paintRows() {
  if (!detail) return;
  const list = detail.querySelector('.mv-rows');
  if (list) list.innerHTML = rowsHtml();
  const sum = detail.querySelector('.mv-sum');
  if (sum) {
    const stepping = state.sel.from === state.sel.to;
    sum.textContent = fmtInt(state.total) + ' '
      + (state.total === 1 ? t('mv.mention') : t('mv.mentions'))
      + (stepping ? '' : ' · ' + t('mv.spanDays')
        .replace('{n}', fmtInt(state.sel.to - state.sel.from + 1)));
  }
  paintThumb();
}

function paintThumb() {
  if (!thumb) return;
  const lead = state.todayTop[0];
  if (!lead) {
    thumb.innerHTML = `<div class="mv-thumb-empty">${escapeHtml(t('mv.empty'))}</div>`;
    return;
  }
  thumb.innerHTML = `
    <div class="mv-thumb-head">${escapeHtml(t('mv.thumb.head'))}</div>
    <div class="mv-thumb-lead">
      <span class="mv-thumb-sym">${escapeHtml(lead.resolved ? lead.symbol : lead.label)}</span>
      <span class="mv-thumb-n">${fmtInt(lead.mentions)}</span>
    </div>
    <div class="mv-thumb-sum">${fmtInt(state.todayTotal)} ${escapeHtml(t('mv.mentions'))}</div>
    <ol class="mv-thumb-rest">${state.todayTop.slice(1, 5).map(r => `
      <li><span>${escapeHtml(r.resolved ? r.symbol : r.label)}</span><span>${
        fmtInt(r.mentions)}</span></li>`).join('')}</ol>`;
}

// ---------------------------------------------------------------- selection

function clampSel(from, to) {
  const n = state.days.length;
  if (!n) return;
  let f = Math.max(0, Math.min(n - 1, Math.round(from)));
  let tt = Math.max(0, Math.min(n - 1, Math.round(to)));
  if (f > tt) { const s = f; f = tt; tt = s; }
  if (f === state.sel.from && tt === state.sel.to) return;
  state.sel = { from: f, to: tt };
  // Mid-gesture the track must stay the SAME element — a full repaint would
  // swap it out from under the pointer and the drag would lose its geometry.
  if (dragging) syncWindow(); else paint();
  askWindow();
}

/** Moves the window without changing its width — the panorama pan. */
function moveSel(deltaDays) {
  const len = state.sel.to - state.sel.from;
  let f = state.sel.from + deltaDays;
  f = Math.max(0, Math.min(state.days.length - 1 - len, f));
  clampSel(f, f + len);
}

function setSpan(days) {
  const n = state.days.length;
  if (!n) return;
  const len = Math.min(days, n) - 1;
  const to = state.sel.to;
  clampSel(Math.max(0, to - len), to);
}

// ---------------------------------------------------------------- interaction

function dayIndexAt(track, clientX) {
  const box = track.getBoundingClientRect();
  if (box.width <= 0) return 0;
  const ratio = (clientX - box.left) / box.width;
  return Math.max(0, Math.min(state.days.length - 1,
    Math.floor(ratio * state.days.length)));
}

/**
 * Pointer handling for the track: an edge grip resizes, the window body pans,
 * bare track jumps the window to that day. One capture per gesture so the drag
 * survives the pointer leaving the element.
 */
function onTrackPointerDown(e) {
  const track = e.target.closest('.mv-track');
  if (!track || !state.days.length) return;
  const grip = e.target.closest('.mv-grip');
  const inWindow = !grip && e.target.closest('.mv-window');
  const start = dayIndexAt(track, e.clientX);
  const anchor = { from: state.sel.from, to: state.sel.to, start };

  if (!grip && !inWindow) {
    const len = state.sel.to - state.sel.from;
    clampSel(start - Math.round(len / 2), start - Math.round(len / 2) + len);
    anchor.from = state.sel.from;
    anchor.to = state.sel.to;
  }

  const mode = grip ? grip.dataset.grip : 'move';
  const move = ev => {
    const at = dayIndexAt(track, ev.clientX);
    const shift = at - anchor.start;
    if (mode === 'l') clampSel(anchor.from + shift, anchor.to);
    else if (mode === 'r') clampSel(anchor.from, anchor.to + shift);
    else {
      const len = anchor.to - anchor.from;
      const f = Math.max(0, Math.min(state.days.length - 1 - len, anchor.from + shift));
      clampSel(f, f + len);
    }
  };
  const up = () => {
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', up);
    document.body.classList.remove('mv-dragging');
    dragging = false;
    paint(); // one honest repaint once the geometry can safely change again
  };
  dragging = true;
  document.body.classList.add('mv-dragging');
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', up);
  e.preventDefault();
}

/** Wheel over the track zooms the window around its centre — the editor gesture. */
function onTrackWheel(e) {
  if (!e.target.closest('.mv-track') || !state.days.length) return;
  e.preventDefault();
  const len = state.sel.to - state.sel.from + 1;
  const grow = e.deltaY > 0 ? 1 : -1;
  const next = Math.max(1, Math.round(len * (grow > 0 ? 1.35 : 0.74)));
  if (next === len) return;
  const centre = (state.sel.from + state.sel.to) / 2;
  clampSel(centre - next / 2, centre + next / 2 - 1);
}

function onClick(e) {
  const preset = e.target.closest('[data-preset]');
  if (preset) { setSpan(Number(preset.dataset.preset)); return; }
  const step = e.target.closest('[data-step]');
  if (step) moveSel(Number(step.dataset.step) * (state.sel.to - state.sel.from + 1));
}

// ---------------------------------------------------------------- entry

export function initMentions(sock) {
  socket = sock;
  detail = document.getElementById('mentions-detail');
  thumb = document.getElementById('mentions-thumb');
  if (!detail) return;
  detail.addEventListener('pointerdown', onTrackPointerDown);
  detail.addEventListener('wheel', onTrackWheel, { passive: false });
  detail.addEventListener('click', onClick);
  paint();
  socket.send('mentions', { command: 'window' });
  askSources();
  // The room keeps writing while the widget is open, so the current reading
  // keeps refreshing. Disk-only and unthrottled by the network — the foreign
  // legs are NOT re-fetched here (their own TTL governs that).
  setInterval(() => { if (!dragging) askWindow(); }, REFRESH_MS);
}

/** Our own count for the requested window (and the panorama it sits in). */
export function renderMentions(p) {
  if (!p) return;
  state.loading = false;
  state.today = p.today || state.today;
  state.earliest = p.earliest || p.today;
  state.rows = Array.isArray(p.rows) ? p.rows : [];
  state.total = p.total || 0;
  state.todayTop = Array.isArray(p.todayTop) ? p.todayTop : [];
  state.todayTotal = p.todayTotal || 0;

  const rebuilt = buildDays(p.timeline, state.earliest, state.today);
  if (rebuilt.length) {
    state.days = rebuilt;
    const from = state.days.findIndex(d => d.iso === p.from);
    const to = state.days.findIndex(d => d.iso === p.to);
    if (dragging) {
      // A drag owns the selection; the answer only fills in the numbers.
    } else if (from >= 0 && to >= 0) {
      state.sel = { from, to };
    } else {
      state.sel = { from: state.days.length - 1, to: state.days.length - 1 };
    }
  }
  if (dragging) paintRows(); else paint();
}

/** The neighbours' rankings — raw, in their own window, below the fence. */
export function renderMentionSources(p) {
  state.sources = p || null;
  if (dragging) return; // repainted the moment the gesture ends
  paint();
}

window.addEventListener('wsbg:languagechange', () => { if (detail) paint(); });
