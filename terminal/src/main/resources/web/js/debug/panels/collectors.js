// Kollektor-Uhr — collectors are scheduled, so the question is a rhythm
// question: did the pass happen, how long did it take, what did it bring, and
// when is the next one booked. That is a time axis, one lane per collector:
// the GAP between markers is the interval, the marker width is the duration,
// its colour the outcome, its height the yield. The hollow marker is the
// booked appointment that has not happened yet.

import { el, card, stat, statRow, table, num, ms, since, clock, empty, sectionError } from '../dom.js';
import { timeline, countdown } from '../viz.js';

export const meta = { id: 'collectors', command: 'collectors', label: 'Collectors', hint: 'schedule' };

const passTone = p => p.error ? 'bad' : (p.fresh > 0 ? 'ok' : (p.items > 0 ? 'info' : 'warn'));

export function beacon(data, now = Date.now()) {
  if (!data || data.error) return null;
  const clock_ = data.clock || [];
  const late = clock_.filter(c => c.nextDueMs && c.nextDueMs < now - 60_000).length;
  const failing = clock_.filter(c => c.lastError).length;
  return {
    label: 'collectors',
    text: failing ? `${failing} erroring` : late ? `${late} overdue` : `${clock_.length} on time`,
    tone: failing ? 'bad' : late ? 'warn' : 'ok',
  };
}

export function create() {
  const root = el('div', 'dbg-panel-body');
  return {
    root,
    update(data, ctx) {
      const now = ctx?.now || Date.now(), spanMs = ctx?.spanMs || 15 * 60_000;
      root.replaceChildren();
      if (data.error) { root.appendChild(sectionError(data.error)); return; }

      const clocks = data.clock || [];
      const passes = data.passes || [];

      const head = card('Passes', `${num(passes.length)} recorded in the ring`);
      head.body.appendChild(statRow(
        stat('collectors', num(clocks.length)),
        stat('passes', num(clocks.reduce((a, c) => a + (c.passes || 0), 0))),
        stat('misses', num(clocks.reduce((a, c) => a + (c.misses || 0), 0)), 'warn', 'pass skipped — already running'),
        stat('erroring', num(clocks.filter(c => c.lastError).length), clocks.some(c => c.lastError) ? 'bad' : 'mute'),
      ));
      root.appendChild(head.card);

      const bySource = new Map();
      for (const p of passes) {
        if (!bySource.has(p.source)) bySource.set(p.source, []);
        bySource.get(p.source).push(p);
      }
      const peakItems = Math.max(1, ...passes.map(p => p.items || 0));
      const lanes = clocks.map(c => ({
        name: c.source,
        due: c.nextDueMs ? { atMs: c.nextDueMs, label: `next due ${clock(c.nextDueMs)} (${since(c.nextDueMs, now)})` } : null,
        events: (bySource.get(c.source) || []).map(p => ({
          atMs: p.atMs,
          durationMs: p.durationMs,
          tone: passTone(p),
          weight: (p.items || 0) / peakItems,
          label: `${clock(p.atMs)} · ${ms(p.durationMs)} · ${num(p.items)} items, ${num(p.fresh)} fresh${p.error ? ` · ERROR ${p.error}` : ''}`,
        })),
      }));
      for (const [source, list] of bySource) {          // passes without a clock row
        if (!clocks.some(c => c.source === source)) {
          lanes.push({ name: source, events: list.map(p => ({ atMs: p.atMs, durationMs: p.durationMs, tone: passTone(p), label: clock(p.atMs) })) });
        }
      }
      const tl = card('Collector clock', 'gap = interval · width = duration · height = yield · hollow = booked, not yet run');
      tl.body.appendChild(timeline(lanes, { spanMs, now }));
      root.appendChild(tl.card);

      const t = card('Per collector', 'the same numbers, exactly');
      t.body.appendChild(table(
        ['Collector', 'Last pass', 'Duration', 'Items', 'Fresh', 'Next due', 'Passes', 'Misses', 'Last error'],
        clocks.map(c => ({
          tone: c.lastError ? 'bad' : (c.nextDueMs && c.nextDueMs < now - 60_000 ? 'warn' : null),
          cells: [
            c.source,
            since(c.lastStartMs, now),
            ms(c.lastDurationMs),
            num(c.lastItems),
            num(c.lastFresh),
            c.nextDueMs ? countdown(Math.max(0, c.nextDueMs - now), Math.max(1, c.nextDueMs - (c.lastStartMs || now))) : '—',
            num(c.passes),
            num(c.misses),
            c.lastError || '',
          ],
        })),
      ));
      if (!clocks.length) t.body.appendChild(empty('no collector has run yet'));
      root.appendChild(t.card);
    },
  };
}
