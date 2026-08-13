// Becken — the article pool. Three questions: how full is it against its
// ceiling (a bar), what is in it (composition), and what flows in (pours over
// time). The age buckets matter more than they look: a full basin of six-hour
// old articles is a different failure from an empty one.

import { el, card, stat, statRow, table, num, since, clock, empty, sectionError } from '../dom.js';
import { meter, hbars, stepLine } from '../viz.js';

export const meta = { id: 'basin', command: 'basin', label: 'Basin', hint: 'article pool' };

export function beacon(data) {
  if (!data || data.error) return null;
  const s = data.stats;
  if (!s || s.error) return { label: 'basin', text: 'no stats', tone: 'warn' };
  const ratio = s.maxItems ? s.size / s.maxItems : 0;
  return {
    label: 'basin',
    text: `${num(s.size)}${s.maxItems ? ` / ${num(s.maxItems)}` : ''}`,
    tone: !s.size ? 'warn' : ratio > 0.95 ? 'warn' : 'ok',
  };
}

const BUCKETS = ['<15m', '15m-1h', '1-6h', '6-24h', '>24h'];
const BUCKET_TONE = ['ok', 'ok', 'info', 'warn', 'mute'];

export function create() {
  const root = el('div', 'dbg-panel-body');
  return {
    root,
    update(data, ctx) {
      const now = ctx?.now || Date.now(), spanMs = ctx?.spanMs || 15 * 60_000;
      root.replaceChildren();
      if (data.error) { root.appendChild(sectionError(data.error)); return; }

      const s = data.stats || {};
      const fill = card('Fill level', 'the pool against the ceiling it is configured with');
      if (s.error) fill.body.appendChild(sectionError(s.error));
      else {
        const ratio = s.maxItems ? s.size / s.maxItems : 0;
        fill.body.appendChild(meter(s.size, s.maxItems, {
          tone: ratio > 0.95 ? 'warn' : 'ok',
          caption: `${num(s.size)} of ${num(s.maxItems)} articles`,
          right: `${(ratio * 100).toFixed(1)} %`,
        }));
        fill.body.appendChild(statRow(
          stat('durable', num(s.durable), 'mute', 'kept beyond the live window'),
          stat('live', num(s.live)),
          stat('sentiment', num(s.sentiment)),
          stat('oldest', since(s.oldestPouredAtMs, now)),
          stat('newest', since(s.newestPouredAtMs, now),
            s.newestPouredAtMs && now - s.newestPouredAtMs > 30 * 60_000 ? 'warn' : null,
            'nothing poured for a while = check the collectors'),
        ));
      }
      root.appendChild(fill.card);

      const ages = s.ageBuckets || {};
      const comp = card('Composition', 'age of what is in the basin, and who filled it');
      const grid = el('div', 'dbg-two');
      const left = el('div');
      left.appendChild(el('h4', 'dbg-sub', 'age'));
      left.appendChild(hbars(BUCKETS.map((b, i) => ({ label: b, value: ages[b] || 0, tone: BUCKET_TONE[i] }))));
      grid.appendChild(left);
      const right = el('div');
      right.appendChild(el('h4', 'dbg-sub', 'by source'));
      const bySource = Object.entries(s.bySource || {}).sort((a, b) => b[1] - a[1]);
      right.appendChild(hbars(bySource.slice(0, 12).map(([label, value]) => ({ label, value }))));
      grid.appendChild(right);
      comp.body.appendChild(grid);
      root.appendChild(comp.card);

      const pours = data.pours || [];
      const flow = card('Inflow', 'basin size at every pour · offered vs. actually fresh');
      flow.body.appendChild(stepLine(pours.map(p => ({ atMs: p.atMs, value: p.basinSize })),
        { spanMs, now, ceiling: s.maxItems }));
      flow.body.appendChild(table(['Source', 'Pours', 'Offered', 'Fresh', 'Duplicate share', 'Last pour'],
        (data.inflowTotals || []).slice().sort((a, b) => b.fresh - a.fresh).map(i => ({
          tone: i.offered > 20 && i.fresh === 0 ? 'warn' : null,
          cells: [
            i.source, num(i.pours), num(i.offered), num(i.fresh),
            i.offered ? `${Math.round((1 - i.fresh / i.offered) * 100)} %` : '—',
            since(i.lastPourMs, now),
          ],
        }))));
      if (!(data.inflowTotals || []).length) flow.body.appendChild(empty('nothing has been poured yet'));
      root.appendChild(flow.card);

      const feed = card('Pour feed', 'newest first');
      feed.body.appendChild(table(['Time', 'Source', 'Offered', 'Fresh', 'Basin after'],
        pours.slice().reverse().slice(0, 80).map(p => ({
          tone: p.offered > 0 && p.fresh === 0 ? 'warn' : null,
          cells: [clock(p.atMs), p.source, num(p.offered), num(p.fresh), num(p.basinSize)],
        }))));
      if (!pours.length) feed.body.appendChild(empty('no pours in the ring'));
      root.appendChild(feed.card);
    },
  };
}
