// Overview — process identity and the two resources a long dev run can quietly
// eat: RAM (this JVM plus our Ollama, against the machine) and disk (the one
// directory the app owns). The heap is a third bar, and deliberately a small
// one: it is a fraction of the JVM's footprint, never the footprint itself.

import { el, card, stat, statRow, pill, num, bytes, ms, since, sectionError } from '../dom.js';
import { meter, hbars } from '../viz.js';

export const meta = { id: 'overview', command: 'overview', label: 'Overview', hint: 'process' };

/** How many app-data entries get their own bar before the tail is lumped. */
const STORAGE_ROWS = 10;

export function beacon(data) {
  if (!data) return null;
  const ratio = data.heapMaxBytes ? data.heapUsedBytes / data.heapMaxBytes : 0;
  return {
    label: 'heap',
    text: `${Math.round(ratio * 100)} %`,
    tone: ratio > 0.9 ? 'bad' : ratio > 0.75 ? 'warn' : 'ok',
  };
}

const fillTone = r => (r > 0.9 ? 'bad' : r > 0.75 ? 'warn' : 'ok');

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

      root.appendChild(memoryCard(data.memory));

      const heap = card('Heap', 'the JVM ceiling — a fraction of the footprint above');
      const ratio = data.heapMaxBytes ? data.heapUsedBytes / data.heapMaxBytes : 0;
      heap.body.appendChild(meter(data.heapUsedBytes, data.heapMaxBytes, {
        tone: fillTone(ratio),
        caption: `${bytes(data.heapUsedBytes)} of ${bytes(data.heapMaxBytes)}`,
        right: `${(ratio * 100).toFixed(1)} %`,
      }));
      root.appendChild(heap.card);

      root.appendChild(storageCard(data.storage));

      const cmds = card('Bridge', 'commands this build answers');
      const row = el('div', 'dbg-pill-row');
      for (const c of data.commands || []) row.appendChild(pill(c, 'mute'));
      cmds.body.appendChild(row);
      root.appendChild(cmds.card);
    },
  };
}

/** Terminal + Ollama as the OS sees them, held against total physical memory. */
function memoryCard(mem) {
  const c = card('Memory', 'terminal + our Ollama against the machine — resident, not heap');
  if (!mem) { c.body.appendChild(el('p', 'dbg-empty', 'no memory data')); return c.card; }
  if (mem.error) { c.body.appendChild(sectionError(mem.error)); return c.card; }

  const total = Number(mem.machineTotalBytes) || 0;
  const used = Number(mem.totalRssBytes) || 0;
  const ratio = total ? used / total : 0;

  if (mem.available === false) {
    const note = el('p', 'dbg-note', 'per-process memory is unavailable on this platform — '
      + 'the numbers below would be a guess, so they are left out');
    note.dataset.tone = 'warn';
    c.body.appendChild(note);
  } else {
    c.body.appendChild(meter(used, total, {
      tone: fillTone(ratio),
      caption: `${bytes(used)} of ${bytes(total)} machine RAM`,
      right: `${(ratio * 100).toFixed(1)} %`,
    }));
  }

  c.body.appendChild(statRow(
    stat('terminal', bytes(mem.terminalRssBytes)),
    stat('ollama', bytes(mem.ollamaRssBytes), null,
      `${(mem.processes || []).length - 1} process(es)`),
    stat('together', bytes(used)),
    stat('machine', bytes(total)),
    stat('OS reports free', bytes(mem.machineFreeBytes), null, 'excludes cache — reads low'),
  ));

  const procs = (mem.processes || []).map(p => ({
    label: `${p.role} · ${p.pid}`,
    title: p.rssBytes == null ? 'no RSS — process gone or unprobeable' : `pid ${p.pid}`,
    value: Number(p.rssBytes) || 0,
    tone: p.own ? 'info' : 'ok',
  }));
  if (procs.length) c.body.appendChild(hbars(procs, { max: total || undefined, format: bytes }));
  if (procs.length === 1) {
    c.body.appendChild(el('p', 'dbg-note', 'no Ollama process of ours is running — '
      + 'the model holds its gigabytes in a runner child, and there is none'));
  }
  return c.card;
}

/** The app data dir: one directory holds everything, so its size IS the footprint. */
function storageCard(st) {
  const c = card('Storage', 'the app data dir — everything the app ever writes');
  if (!st) { c.body.appendChild(el('p', 'dbg-empty', 'no storage data')); return c.card; }
  if (st.error) { c.body.appendChild(sectionError(st.error)); return c.card; }

  const total = Number(st.totalBytes) || 0;
  const volume = Number(st.volumeTotalBytes) || 0;
  const share = volume ? total / volume : 0;
  c.body.appendChild(meter(total, volume, {
    tone: 'info',
    caption: `${bytes(total)} of a ${bytes(volume)} volume`,
    right: `${(share * 100).toFixed(1)} %`,
  }));

  c.body.appendChild(statRow(
    stat('footprint', bytes(total), null, 'freed by an uninstall'),
    stat('files', num(st.files)),
    stat('volume free', bytes(st.volumeFreeBytes)),
    stat('measured', since(st.sampledAtMs), null, 'walk is cached ~15 s'),
    st.unreadable ? stat('unreadable', num(st.unreadable), 'warn', 'missing from the total') : null,
  ));

  const entries = st.entries || [];
  const shown = entries.slice(0, STORAGE_ROWS).map(e => ({
    label: e.name,
    title: `${e.name} — ${num(e.files)} file(s)`,
    value: Number(e.bytes) || 0,
  }));
  const tail = entries.slice(STORAGE_ROWS);
  if (tail.length) {
    shown.push({
      label: `${tail.length} smaller entries`,
      value: tail.reduce((a, e) => a + (Number(e.bytes) || 0), 0),
      tone: 'mute',
    });
  }
  c.body.appendChild(hbars(shown, { max: total || undefined, format: bytes }));
  c.body.appendChild(el('p', 'dbg-note', st.path));
  return c.card;
}
