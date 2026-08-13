// Reddit — three polling lanes on one time axis, plus what the throttle and
// the source chain are doing underneath them.
//
// `lastRatelimitRemaining` is Reddit's own header, and -1 means "never seen",
// which is a different statement from "0 left". It is labelled as such.

import { el, card, stat, statRow, pill, table, num, ms, since, clock, empty, sectionError } from '../dom.js';
import { timeline, countdown } from '../viz.js';

export const meta = { id: 'reddit', command: 'reddit', label: 'Reddit', hint: 'polls · throttle' };

const LANES = ['scan', 'comments', 'gap-fill'];

export function beacon(data) {
  if (!data || data.error) return null;
  const lanes = data.lanes || [];
  const recent = lanes.slice(-12);
  const failed = recent.filter(l => l.failed).length;
  const rl = data.throttle?.lastRatelimitRemaining;
  if (failed) return { label: 'reddit', text: `${failed}/${recent.length} polls failed`, tone: 'bad' };
  if (rl != null && rl >= 0 && rl < 20) return { label: 'reddit', text: `${rl} calls left`, tone: 'warn' };
  return { label: 'reddit', text: `${recent.length ? 'polling' : 'idle'}`, tone: recent.length ? 'ok' : 'warn' };
}

export function create() {
  const root = el('div', 'dbg-panel-body');
  return {
    root,
    update(data, ctx) {
      const now = ctx?.now || Date.now(), spanMs = ctx?.spanMs || 15 * 60_000;
      root.replaceChildren();
      if (data.error) { root.appendChild(sectionError(data.error)); return; }

      const lanes = data.lanes || [];
      const th = data.throttle || {};

      const head = card('Throttle', 'what the rate limiter cost us — and what Reddit itself says is left');
      const seen = th.lastRatelimitRemaining != null && th.lastRatelimitRemaining >= 0;
      head.body.appendChild(statRow(
        stat('rate limit left', seen ? num(th.lastRatelimitRemaining) : 'never seen',
          !seen ? 'mute' : th.lastRatelimitRemaining < 20 ? 'bad' : th.lastRatelimitRemaining < 60 ? 'warn' : 'ok',
          seen ? `Reddit's own header, ${since(th.lastRatelimitSeenAtMs, now)}` : 'no x-ratelimit-remaining header has arrived yet'),
        stat('bucket waits', `${num(th.bucketAcquires)} acquires`, 'mute', `${ms(th.bucketWaitMsTotal)} spent waiting`),
        stat('backoffs', num(th.backoffCount), th.backoffCount ? 'warn' : 'ok', `${ms(th.backoffSleepMsTotal)} slept`),
        stat('avg wait per acquire', th.bucketAcquires ? ms(th.bucketWaitMsTotal / th.bucketAcquires) : '—', 'mute'),
      ));
      root.appendChild(head.card);

      const chain = data.chain || [];
      const chainCard = card('Source chain', 'which delegate is answering, and who is sitting out');
      const chainRow = el('div', 'dbg-chain');
      for (const c of chain) {
        const item = el('div', 'dbg-chain-item');
        item.dataset.state = c.active ? 'active' : (c.demotedUntilMs > now ? 'demoted' : 'idle');
        item.appendChild(el('span', 'dbg-chain-name', c.name));
        item.appendChild(pill(c.active ? 'active' : (c.demotedUntilMs > now ? 'demoted' : 'standby'),
          c.active ? 'ok' : (c.demotedUntilMs > now ? 'bad' : 'mute')));
        if (c.demotedUntilMs > now) {
          item.appendChild(countdown(c.demotedUntilMs - now, 20 * 60_000, { tone: 'bad' }));
        }
        chainRow.appendChild(item);
      }
      chainCard.body.appendChild(chainRow);
      if (!chain.length) chainCard.body.appendChild(empty('a single, non-falling-back source'));
      root.appendChild(chainCard.card);

      const laneCard = card('Poll lanes', 'gap = interval · width = duration · height = yield · red = failed pass');
      const peak = Math.max(1, ...lanes.map(l => (l.newThreads || 0) + (l.newComments || 0) + (l.newUpvotes || 0)));
      laneCard.body.appendChild(timeline(LANES.map(name => ({
        name,
        events: lanes.filter(l => l.lane === name).map(l => ({
          atMs: l.atMs,
          durationMs: l.durationMs,
          tone: l.failed ? 'bad' : ((l.newThreads || l.newComments || l.newUpvotes) ? 'ok' : 'info'),
          weight: ((l.newThreads || 0) + (l.newComments || 0) + (l.newUpvotes || 0)) / peak,
          label: `${clock(l.atMs)} · ${l.scope || ''} · ${ms(l.durationMs)} · ${num(l.scanned)} scanned · `
            + `+${num(l.newThreads)} threads, +${num(l.newComments)} comments, +${num(l.newUpvotes)} upvotes`
            + (l.failed ? ' · FAILED' : ''),
        })),
      })), { spanMs, now }));
      const recent = lanes.slice().reverse().slice(0, 40);
      laneCard.body.appendChild(table(['Time', 'Lane', 'Scope', 'Duration', 'Scanned', 'Threads', 'Comments', 'Upvotes', 'Failed'],
        recent.map(l => ({
          tone: l.failed ? 'bad' : null,
          cells: [clock(l.atMs), l.lane, l.scope || '', ms(l.durationMs), num(l.scanned),
            num(l.newThreads), num(l.newComments), num(l.newUpvotes), l.failed ? 'yes' : ''],
        }))));
      if (!lanes.length) laneCard.body.appendChild(empty('no poll recorded yet'));
      root.appendChild(laneCard.card);

      const events = (data.sourceEvents || []).slice().reverse();
      const evCard = card('Source events', 'newest first — switches, demotions, recoveries');
      evCard.body.appendChild(table(['Time', 'Kind', 'Detail'], events.slice(0, 60).map(e => ({
        tone: e.kind === 'demote' || e.kind === 'degraded' ? 'bad' : e.kind === 'healthy' ? 'ok' : null,
        cells: [clock(e.atMs), e.kind, e.detail || ''],
      }))));
      if (!events.length) evCard.body.appendChild(empty('nothing has switched'));
      root.appendChild(evCard.card);
    },
  };
}
