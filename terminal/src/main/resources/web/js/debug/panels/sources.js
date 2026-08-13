// Quellenmesser — the point of this panel is to notice a source that died
// quietly. So the loud thing on screen is not the healthy majority: it is the
// run comb (one column per run, a failed run dips BELOW the baseline) and the
// "last delivery" age. A source that still runs but has answered nothing for
// an hour looks exactly as wrong as one that throws.

import { el, card, stat, statRow, pill, table, num, ms, since, clock, empty, sectionError } from '../dom.js';
import { columns, countdown } from '../viz.js';

export const meta = { id: 'sources', command: 'sources', label: 'Sources', hint: 'health' };

const STALE_MS = 45 * 60_000;   // no delivery for this long = suspicious, whatever the status says

function rowTone(h, now) {
  if (h.consecutiveFailures >= 3) return 'bad';
  if (h.consecutiveFailures >= 1) return 'warn';
  if (h.lastSuccessMs && now - h.lastSuccessMs > STALE_MS) return 'warn';
  if (!h.lastSuccessMs && h.runs > 0) return 'bad';
  return 'ok';
}

export function beacon(data, now = Date.now()) {
  if (!data || data.error) return null;
  const health = data.health || [];
  const bad = health.filter(h => rowTone(h, now) === 'bad').length;
  const warn = health.filter(h => rowTone(h, now) === 'warn').length;
  return {
    label: 'sources',
    text: bad || warn ? `${bad} down · ${warn} weak` : `${health.length} ok`,
    tone: bad ? 'bad' : warn ? 'warn' : 'ok',
  };
}

export function create() {
  const root = el('div', 'dbg-panel-body');
  let showAll = false;
  const toggle = el('button', 'dbg-btn', 'show healthy sources');
  toggle.addEventListener('click', () => { showAll = !showAll; render(); });
  let last = null, lastNow = Date.now();

  function render() {
    const data = last, now = lastNow;
    root.replaceChildren();
    if (!data) return;
    if (data.error) { root.appendChild(sectionError(data.error)); return; }

    const health = (data.health || []).slice();
    const b = beacon(data, now);
    const summary = card('Delivery', `${health.length} sources · ${num(data.totalEvents)} outcomes recorded`);
    summary.body.appendChild(statRow(
      stat('down', num(health.filter(h => rowTone(h, now) === 'bad').length), 'bad', '3+ failures in a row, or never delivered'),
      stat('weak', num(health.filter(h => rowTone(h, now) === 'warn').length), 'warn', `failing, or silent > ${ms(STALE_MS)}`),
      stat('ok', num(health.filter(h => rowTone(h, now) === 'ok').length), 'ok'),
      stat('host gates', num(data.hostGates), 'mute', 'per-host in-flight limiters'),
    ));
    root.appendChild(summary.card);

    const troubled = health.filter(h => rowTone(h, now) !== 'ok');
    const shown = showAll ? health : (troubled.length ? troubled : health);
    toggle.textContent = showAll ? 'only the troubled ones' : `show all ${health.length}`;

    const t = card('Source meter', 'one column per run · red below the line = failed run');
    t.head.appendChild(toggle);
    t.body.appendChild(table(
      ['Source', 'Status', 'Last runs', 'Items', 'Last delivery', 'Runs', 'Failures', 'Note'],
      shown.map(h => ({
        tone: rowTone(h, now),
        cells: [
          h.source,
          pill(h.lastStatus || '—', h.lastStatus === 'DELIVERED' ? 'ok'
            : h.lastStatus === 'FAILED' ? 'bad' : h.lastStatus === 'EMPTY' ? 'warn' : 'mute'),
          columns(h.recentItemCounts || [], { label: `${h.source}: recent runs` }),
          num(h.lastItemCount),
          since(h.lastSuccessMs, now),
          num(h.runs),
          h.consecutiveFailures ? `${num(h.failures)} (${h.consecutiveFailures} in a row)` : num(h.failures),
          h.lastNote || '',
        ],
      })),
    ));
    if (!shown.length) t.body.appendChild(empty('no source has reported yet'));
    root.appendChild(t.card);

    const gates = card('Host gates', 'the fetcher backing off — cooldowns expire, silences mute a host+transport');
    const cooldowns = data.cooldowns || [], silences = data.silences || [];
    if (cooldowns.length) {
      gates.body.appendChild(el('h4', 'dbg-sub', 'cooldowns'));
      gates.body.appendChild(table(['Host', 'Strikes', 'Left'], cooldowns.map(c => ({
        tone: 'warn',
        cells: [c.host, num(c.strikes), countdown(c.leftMs, 15 * 60_000)],
      }))));
    }
    if (silences.length) {
      gates.body.appendChild(el('h4', 'dbg-sub', 'silences'));
      gates.body.appendChild(table(['Host · transport', 'Strikes', 'Muted for'], silences.map(s => ({
        tone: 'bad',
        cells: [s.hostTransport, num(s.strikes), countdown(s.leftMs, 30 * 60_000, { tone: 'bad' })],
      }))));
    }
    if (!cooldowns.length && !silences.length) gates.body.appendChild(empty('nothing is being throttled'));
    root.appendChild(gates.card);

    const events = (data.events || []).slice().reverse();
    const feed = card('Outcome feed', 'newest first — every outcome line the funnel saw');
    feed.body.appendChild(table(['Time', 'Source', 'Status', 'Items', 'Note'], events.slice(0, 120).map(e => ({
      tone: e.status === 'FAILED' ? 'bad' : e.status === 'EMPTY' ? 'warn' : null,
      cells: [clock(e.atMs), e.source, e.status, num(e.itemCount), e.note || ''],
    }))));
    if (!events.length) feed.body.appendChild(empty('no outcomes recorded yet'));
    root.appendChild(feed.card);
  }

  return {
    root,
    update(data, ctx) { last = data; lastNow = ctx?.now || Date.now(); render(); },
  };
}
