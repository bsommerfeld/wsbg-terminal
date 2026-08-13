// Overview — process identity and the one thing that can quietly kill a long
// dev run: the heap. Nothing here is a chart except the heap fill.

import { el, card, stat, statRow, pill, num, bytes, ms, sectionError } from '../dom.js';
import { meter } from '../viz.js';

export const meta = { id: 'overview', command: 'overview', label: 'Overview', hint: 'process' };

export function beacon(data) {
  if (!data) return null;
  const ratio = data.heapMaxBytes ? data.heapUsedBytes / data.heapMaxBytes : 0;
  return {
    label: 'heap',
    text: `${Math.round(ratio * 100)} %`,
    tone: ratio > 0.9 ? 'bad' : ratio > 0.75 ? 'warn' : 'ok',
  };
}

export function create() {
  const root = el('div', 'dbg-panel-body');
  return {
    root,
    update(data) {
      root.replaceChildren();
      if (data.error) { root.appendChild(sectionError(data.error)); return; }

      const proc = card('Process', 'this JVM only — the launcher runs its own');
      proc.body.appendChild(statRow(
        stat('dev mode', data.devMode ? 'active' : 'off', data.devMode ? 'ok' : 'bad',
          data.devMode ? 'classes on disk' : 'shipped build — this view should not exist'),
        stat('pid', num(data.pid)),
        stat('java', data.javaVersion),
        stat('os', data.os),
        stat('cores', num(data.processors)),
        stat('uptime', ms(data.uptimeMs)),
      ));
      root.appendChild(proc.card);

      const heap = card('Heap', 'used against the ceiling the JVM was given');
      const ratio = data.heapMaxBytes ? data.heapUsedBytes / data.heapMaxBytes : 0;
      heap.body.appendChild(meter(data.heapUsedBytes, data.heapMaxBytes, {
        tone: ratio > 0.9 ? 'bad' : ratio > 0.75 ? 'warn' : 'ok',
        caption: `${bytes(data.heapUsedBytes)} of ${bytes(data.heapMaxBytes)}`,
        right: `${(ratio * 100).toFixed(1)} %`,
      }));
      root.appendChild(heap.card);

      const cmds = card('Bridge', 'commands this build answers');
      const row = el('div', 'dbg-pill-row');
      for (const c of data.commands || []) row.appendChild(pill(c, 'mute'));
      cmds.body.appendChild(row);
      root.appendChild(cmds.card);
    },
  };
}
