// Config — every @Key of every @Section, live value against a freshly built
// default. The honesty limit is printed on the card and is not cosmetic: after
// the first start the TOML holds every key, so the layer can prove "differs
// from the default", never "the user set this".

import { el, card, stat, statRow, table, num, empty, sectionError } from '../dom.js';

export const meta = { id: 'config', command: 'config', label: 'Config', hint: 'vs. default' };

export function beacon(data) {
  if (!data || data.error) return null;
  return { label: 'config', text: `${num(data.differing)} off default`, tone: 'mute' };
}

export function create() {
  const root = el('div', 'dbg-panel-body');
  let query = '', onlyDiffs = false, last = null;

  const search = el('input', 'dbg-input');
  search.type = 'search';
  search.placeholder = 'filter keys and values…';
  search.addEventListener('input', () => { query = search.value.trim().toLowerCase(); render(); });

  const toggle = el('button', 'dbg-btn', 'only differences');
  toggle.addEventListener('click', () => { onlyDiffs = !onlyDiffs; render(); });

  function render() {
    root.replaceChildren();
    const data = last;
    if (!data) return;
    if (data.error) { root.appendChild(sectionError(data.error)); return; }

    const entries = data.entries || [];
    const c = card('Configuration', 'differences first — “differs from default”, NOT “set by the user”: after first start the file contains every key, so its presence proves nothing');
    const bar = el('div', 'dbg-filterbar');
    bar.appendChild(search);
    bar.appendChild(toggle);
    toggle.dataset.active = String(onlyDiffs);
    c.body.appendChild(bar);
    c.body.appendChild(statRow(
      stat('keys', num(entries.length)),
      stat('differ from default', num(data.differing), data.differing ? 'warn' : 'ok'),
    ));

    const shown = entries.filter(e =>
      (!onlyDiffs || e.differs) &&
      (!query || `${e.key} ${e.value} ${e.default}`.toLowerCase().includes(query)));
    c.body.appendChild(table(['Key', 'Value', 'Default'], shown.map(e => ({
      tone: e.differs ? 'warn' : null,
      cells: [e.key, e.value ?? '—', e.differs ? (e.default ?? '—') : ''],
    }))));
    if (!shown.length) c.body.appendChild(empty('no key matches'));
    root.appendChild(c.card);
  }

  return { root, update(data) { last = data; render(); } };
}
