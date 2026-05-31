// Renders the FinancialJuice news feed.
//
// New entries (guids not seen in the previous render) get the
// .new-row class so the gold flash animation plays once on entry —
// same visual cue as Reddit headlines.
//
// Items that carry a multi-line body (earnings breakdowns, data splits)
// become .expandable rows: clicking the row toggles a .detail block.
// The expanded set is tracked per host so the 60 s snapshot re-render
// doesn't collapse a card the user just opened.

import { highlightTickers } from '../format/ticker.js';
import { colorizeSignedNumbers } from '../format/numbers.js';
import { fmtClock } from '../format/time.js';

const seenKeys = new WeakMap();      // host -> Set of guids seen last render
const expandedKeys = new WeakMap();  // host -> Set of guids currently expanded
const wiredHosts = new WeakSet();    // hosts with the delegated click listener

export function renderFjNews(host, items) {
  if (!host) return;
  wireToggle(host);

  if (!items || items.length === 0) {
    host.innerHTML = `<div class="row placeholder"><div class="time"></div><div class="body">Warte auf Financial Juice…</div></div>`;
    seenKeys.set(host, new Set());
    return;
  }

  const prev = seenKeys.get(host) || new Set();
  const isFirstRender = prev.size === 0;
  const expanded = expandedKeys.get(host) || new Set();

  host.innerHTML = items
    .map(n => toRow(n, !isFirstRender && !prev.has(n.guid), expanded.has(n.guid)))
    .join('');

  seenKeys.set(host, new Set(items.map(n => n.guid)));
}

// Single delegated click listener per host — survives every re-render
// because it lives on the host, not on the rows it toggles.
function wireToggle(host) {
  if (wiredHosts.has(host)) return;
  wiredHosts.add(host);
  host.addEventListener('click', (e) => {
    const row = e.target.closest('.row.expandable');
    if (!row || !host.contains(row)) return;
    const guid = row.dataset.guid;
    if (!guid) return;
    const set = expandedKeys.get(host) || new Set();
    if (set.has(guid)) {
      set.delete(guid);
      row.classList.remove('expanded');
    } else {
      set.add(guid);
      row.classList.add('expanded');
    }
    expandedKeys.set(host, set);
  });
}

function toRow(n, isNew, isExpanded) {
  const head = colorizeSignedNumbers(highlightTickers(n.title));
  const detail = extractDetail(n);

  const classes = ['row'];
  if (n.isRed) classes.push('mover');
  if (isNew)   classes.push('new-row');
  if (detail)  classes.push('expandable');
  if (detail && isExpanded) classes.push('expanded');

  const meta = buildMeta(n);
  const time = fmtClock(n.publishedUtc);
  const guidAttr = detail ? ` data-guid="${escapeText(n.guid)}"` : '';
  const indicator = detail
    ? '<svg class="expand-ind" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>'
    : '';

  return `<div class="${classes.join(' ')}"${guidAttr}>
    <div class="time">${time}</div>
    <div class="body">
      <div class="head">${head}</div>
      ${meta ? `<div class="meta">${meta}</div>` : ''}
      ${detail ? `<div class="detail">${detail}</div>` : ''}
    </div>
    ${indicator}
  </div>`;
}

// Returns the extra body text beyond the headline, rendered as HTML with
// one line per entry. FinancialJuice descriptions usually repeat the
// headline as their first line — that line is dropped so the expanded
// view shows only what the headline doesn't already say. Returns '' when
// there's no additional detail.
function extractDetail(n) {
  const desc = (n.description || '').trim();
  if (!desc) return '';

  const lines = desc.split('\n').map(l => l.trim()).filter(Boolean);
  if (lines.length && norm(lines[0]) === norm(n.title)) lines.shift();
  if (lines.length === 0) return '';

  const rest = lines.join('\n');
  if (norm(rest) === norm(n.title)) return '';

  return lines
    .map(l => colorizeSignedNumbers(highlightTickers(l)))
    .join('<br>');
}

function norm(s) {
  return (s || '').toLowerCase().replace(/\s+/g, ' ').trim();
}

function buildMeta(n) {
  const tags = (n.tags || []);
  if (tags.length === 0) return '';
  return tags.map(t => `<span class="tag">${escapeText(t)}</span>`).join(' ');
}

function escapeText(s) {
  return s.replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}
