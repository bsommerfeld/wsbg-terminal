// Archive search for the Schlagzeilen widget — the rail's magnifier popup.
//
// Three parts:
//   1. The popup panel: an input, live suggestions while typing, and the last
//      5 searches (localStorage, MRU).
//   2. The suggestion resolver: a client-side scoring pass over the archive's
//      subject vocabulary (the `subjects` archive command — every subject the
//      wire ever named, with ticker + headline count). Normalized prefix/token
//      matching over ≤1000 flat entries — microseconds per keystroke, no
//      round-trip, no debounce needed.
//   3. Execution: a suggestion queries the archive by name AND ticker together
//      (the `subject` union command — a subject must never depend on its
//      ticker to be findable; name-only lines count the same), free text runs
//      the full-text `search` command. Results land in the wire list's search
//      mode (reddit.js / headline-list.js) until the banner is closed.
//
// The socket allows ONE handler per topic, so main.js owns `archive-results`
// and routes non-page payloads here (onArchiveResults filters by requestId).

import { t } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { showSearchResults } from './reddit.js';

const RECENT_KEY = 'wsbg.headlineSearch.recent';
const MAX_RECENT = 5;
const MAX_SUGGESTIONS = 6;
const RESULT_LIMIT = 500;           // server cap — one page, no scroll-back in search mode
const VOCAB_TTL_MS = 60_000;        // re-pull the vocabulary at most once a minute

const VOCAB_REQUEST = 'search-vocab';
const SEARCH_REQUEST = 'wire-search';

let socketRef = null;
let panel = null;
let vocab = [];                     // [{ name, ticker, count, norm, tokens, tickNorm }]
let vocabFetchedAt = 0;
let pendingLabel = null;            // display label for the in-flight search
let activeIndex = -1;               // keyboard-highlighted suggestion
let suggestions = [];

export function initHeadlineSearch(socket) {
  socketRef = socket;
  panel = document.querySelector('.js-headline-search');
  const btn = document.querySelector('.js-rail-search-btn');
  if (!panel || !btn) return;

  render();
  window.addEventListener('wsbg:languagechange', render);

  // widget-rail.js registered its toggle first (main.js init order), so by the
  // time this runs the .open class reflects the NEW state.
  btn.addEventListener('click', () => {
    const item = btn.closest('.rail-item');
    if (!item || !item.classList.contains('open')) return;
    requestVocab();
    const input = panel.querySelector('.search-input');
    if (input) setTimeout(() => input.focus(), 80); // after the clip-path reveal starts
  });
}

/**
 * Routes an `archive-results` payload that main.js didn't consume (i.e. not a
 * scroll-back page): the vocabulary answer refreshes the resolver index, a
 * search/ticker answer opens the result view.
 */
export function onArchiveResults(payload) {
  if (!payload) return;
  if (payload.requestId === VOCAB_REQUEST) {
    vocab = buildVocab(payload.items || []);
    updateSuggestions(); // the user may already be typing
  } else if (payload.requestId === SEARCH_REQUEST) {
    showSearchResults(pendingLabel || payload.query || '', payload.total || 0, payload.items || []);
    pendingLabel = null;
  }
}

// ---- panel ----

function render() {
  if (!panel) return;
  panel.innerHTML = `
    <div class="rail-pop-head">${escapeHtml(t('search.title'))}</div>
    <input class="search-input" type="text" autocomplete="off" spellcheck="false"
           placeholder="${escapeHtml(t('search.placeholder'))}"
           aria-label="${escapeHtml(t('search.title'))}">
    <div class="search-suggest" hidden></div>
    <div class="search-recent"></div>`;
  wireInput();
  renderRecent();
}

function wireInput() {
  const input = panel.querySelector('.search-input');
  input.addEventListener('input', () => updateSuggestions());
  input.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (!suggestions.length) return;
      e.preventDefault();
      const step = e.key === 'ArrowDown' ? 1 : -1;
      activeIndex = (activeIndex + step + suggestions.length) % suggestions.length;
      paintSuggestions();
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (activeIndex >= 0 && suggestions[activeIndex]) runEntry(suggestions[activeIndex]);
      else if (input.value.trim()) runFreeText(input.value.trim());
    }
  });
}

function updateSuggestions() {
  if (!panel) return;
  const input = panel.querySelector('.search-input');
  const box = panel.querySelector('.search-suggest');
  if (!input || !box) return;
  suggestions = resolve(input.value);
  activeIndex = suggestions.length ? 0 : -1;
  paintSuggestions();
}

function paintSuggestions() {
  const box = panel.querySelector('.search-suggest');
  if (!box) return;
  if (!suggestions.length) {
    box.setAttribute('hidden', '');
    box.innerHTML = '';
    return;
  }
  box.removeAttribute('hidden');
  box.innerHTML = suggestions.map((s, i) => `
    <button type="button" class="search-option${i === activeIndex ? ' active' : ''}" data-idx="${i}">
      <span class="search-option-name">${escapeHtml(s.name)}</span>
      ${s.ticker ? `<span class="search-option-ticker">${escapeHtml(s.ticker)}</span>` : ''}
    </button>`).join('');
  box.querySelectorAll('.search-option').forEach(el => {
    el.addEventListener('click', () => runEntry(suggestions[Number(el.dataset.idx)]));
  });
}

function renderRecent() {
  const host = panel.querySelector('.search-recent');
  if (!host) return;
  const recent = loadRecent();
  if (!recent.length) { host.innerHTML = ''; return; }
  host.innerHTML = `
    <div class="search-recent-head">${escapeHtml(t('search.recent'))}</div>
    ${recent.map((r, i) => `
      <button type="button" class="search-option search-option-recent" data-idx="${i}">
        <span class="search-option-name">${escapeHtml(r.label)}</span>
        ${r.ticker ? `<span class="search-option-ticker">${escapeHtml(r.ticker)}</span>` : ''}
      </button>`).join('')}`;
  host.querySelectorAll('.search-option').forEach(el => {
    el.addEventListener('click', () => {
      const r = loadRecent()[Number(el.dataset.idx)];
      if (!r) return;
      if (r.ticker) send({ command: 'subject', query: r.query || null, symbol: r.ticker }, r.label, r);
      else send({ command: 'search', query: r.query || r.label }, r.label, r);
    });
  });
}

// ---- execution ----

function runEntry(entry) {
  if (!entry) return;
  const label = entry.ticker && entry.name !== entry.ticker
    ? `${entry.name} (${entry.ticker})` : entry.name;
  if (entry.ticker) {
    // Name + ticker union: also finds lines that name the subject without a
    // resolved ticker — the ticker widens the net, it is never a requirement.
    send({ command: 'subject', query: entry.name, symbol: entry.ticker }, label,
        { label, ticker: entry.ticker, query: entry.name });
  } else {
    send({ command: 'search', query: entry.name }, label, { label, query: entry.name });
  }
}

function runFreeText(text) {
  send({ command: 'search', query: text }, text, { label: text, query: text });
}

function send(payload, label, recentEntry) {
  if (!socketRef) return;
  pendingLabel = label;
  socketRef.send('archive', { ...payload, limit: RESULT_LIMIT, requestId: SEARCH_REQUEST });
  pushRecent(recentEntry);
  closePopup();
}

function closePopup() {
  const btn = document.querySelector('.js-rail-search-btn');
  const item = btn ? btn.closest('.rail-item') : null;
  if (item) item.classList.remove('open');
  if (btn) btn.setAttribute('aria-expanded', 'false');
  const input = panel && panel.querySelector('.search-input');
  if (input) input.value = '';
  suggestions = [];
  activeIndex = -1;
  paintSuggestions();
  renderRecent();
}

// ---- recent searches (localStorage, MRU, max 5) ----

function loadRecent() {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    const arr = raw ? JSON.parse(raw) : [];
    return Array.isArray(arr) ? arr.filter(r => r && typeof r.label === 'string') : [];
  } catch { return []; }
}

function pushRecent(entry) {
  if (!entry || !entry.label) return;
  const rest = loadRecent().filter(r => r.label !== entry.label);
  const next = [entry, ...rest].slice(0, MAX_RECENT);
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(next)); } catch { /* ignore */ }
}

// ---- vocabulary + resolver ----

function requestVocab() {
  if (!socketRef) return;
  const now = Date.now();
  if (now - vocabFetchedAt < VOCAB_TTL_MS && vocab.length) return;
  vocabFetchedAt = now;
  socketRef.send('archive', { command: 'subjects', requestId: VOCAB_REQUEST });
}

function buildVocab(items) {
  const out = [];
  for (const it of items) {
    const name = (it.name || it.ticker || '').trim();
    if (!name) continue;
    const norm = normalize(name);
    out.push({
      name,
      ticker: it.ticker || null,
      count: it.count || 1,
      norm,
      tokens: norm.split(/[^a-z0-9]+/).filter(Boolean),
      tickNorm: it.ticker ? normalize(it.ticker) : null,
    });
  }
  return out;
}

/** Lowercase + fold German umlauts/diacritics, so "käfig" matches "Kafig" and vice versa. */
function normalize(s) {
  return s.toLowerCase().replace(/ß/g, 'ss')
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

/**
 * The resolver: scores every vocabulary entry against the typed prefix.
 * Exact ticker > ticker prefix > name prefix > word prefix > substring;
 * a small log-scaled count boost breaks ties toward the room's favourites.
 */
function resolve(qRaw) {
  const q = normalize((qRaw || '').trim());
  if (!q) return [];
  const scored = [];
  for (const e of vocab) {
    let s = 0;
    if (e.tickNorm === q || e.norm === q) s = 100;
    else if (e.tickNorm && e.tickNorm.startsWith(q)) s = 85;
    else if (e.norm.startsWith(q)) s = 75;
    else if (e.tokens.some(tk => tk.startsWith(q))) s = 60;
    else if (q.length >= 3 && e.norm.includes(q)) s = 40;
    if (s > 0) scored.push([s + Math.min(10, Math.log2(1 + e.count)), e]);
  }
  scored.sort((a, b) => b[0] - a[0]);
  return scored.slice(0, MAX_SUGGESTIONS).map(x => x[1]);
}
