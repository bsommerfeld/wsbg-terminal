// Log — the ring of this JVM (the launcher logs elsewhere). The aggregate is
// the point: which logger is producing the warnings, not just that there are
// some. The line list is the evidence underneath it.

import { el, card, stat, statRow, num, clock, empty, sectionError, shortLogger } from '../dom.js';
import { hbars } from '../viz.js';

export const meta = { id: 'log', command: 'log', label: 'Log', hint: 'warn · error' };

export function beacon(data) {
  if (!data || data.error) return null;
  const a = data.aggregate || {};
  return {
    label: 'log',
    text: `${num(a.errorCount)} err · ${num(a.warnCount)} warn`,
    tone: a.errorCount ? 'bad' : a.warnCount ? 'warn' : 'ok',
  };
}

const LEVEL_TONE = { ERROR: 'bad', WARN: 'warn', INFO: 'info', DEBUG: 'mute', TRACE: 'mute' };

export function create() {
  const root = el('div', 'dbg-panel-body');
  let query = '', level = 'ALL', last = null;

  const search = el('input', 'dbg-input');
  search.type = 'search';
  search.placeholder = 'filter message, logger, thread…';
  search.addEventListener('input', () => { query = search.value.trim().toLowerCase(); render(); });

  const levels = el('div', 'dbg-seg');
  for (const l of ['ALL', 'ERROR', 'WARN', 'INFO']) {
    const b = el('button', 'dbg-seg-btn', l);
    b.addEventListener('click', () => { level = l; render(); });
    b.dataset.level = l;
    levels.appendChild(b);
  }

  function render() {
    root.replaceChildren();
    const data = last;
    if (!data) return;
    if (data.error) { root.appendChild(sectionError(data.error)); return; }

    const a = data.aggregate || {};
    const top = card('Aggregate', 'over the ring window — who is complaining');
    top.body.appendChild(statRow(
      stat('lines in window', num(a.lines)),
      stat('errors', num(a.errorCount), a.errorCount ? 'bad' : 'ok'),
      stat('warnings', num(a.warnCount), a.warnCount ? 'warn' : 'ok'),
      stat('appended since start', num(data.totalAppended), 'mute', 'ring holds the newest 4000'),
    ));
    const byLogger = Object.entries(a.warnAndErrorByLogger || {}).sort((x, y) => y[1] - x[1]);
    if (byLogger.length) {
      top.body.appendChild(el('h4', 'dbg-sub', 'warn + error by logger'));
      top.body.appendChild(hbars(byLogger.slice(0, 10).map(([label, value]) => ({ label: shortLogger(label), value, tone: 'warn', title: label }))));
    }
    root.appendChild(top.card);

    const lines = (data.lines || []).slice().reverse();
    const shown = lines.filter(l =>
      (level === 'ALL' || l.level === level) &&
      (!query || `${l.message} ${l.logger} ${l.thread} ${l.error || ''}`.toLowerCase().includes(query)));

    const c = card('Lines', 'newest first');
    const bar = el('div', 'dbg-filterbar');
    bar.appendChild(levels);
    bar.appendChild(search);
    for (const b of levels.children) b.dataset.active = String(b.dataset.level === level);
    c.body.appendChild(bar);

    const list = el('div', 'dbg-log');
    for (const l of shown.slice(0, 400)) {
      const row = el('div', 'dbg-log-row');
      row.dataset.tone = LEVEL_TONE[l.level] || 'mute';
      row.appendChild(el('span', 'dbg-log-time', clock(l.atMs)));
      row.appendChild(el('span', 'dbg-log-level', l.level));
      const logger = el('span', 'dbg-log-logger', shortLogger(l.logger));
      logger.title = `${l.logger} · ${l.thread}`;
      row.appendChild(logger);
      const msg = el('span', 'dbg-log-msg', l.message);
      if (l.error) {
        msg.appendChild(el('span', 'dbg-log-error', ` ${l.error}`));
      }
      row.appendChild(msg);
      list.appendChild(row);
    }
    c.body.appendChild(list);
    if (!shown.length) c.body.appendChild(empty('no line matches'));
    root.appendChild(c.card);
  }

  return { root, update(data) { last = data; render(); } };
}
