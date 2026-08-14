// Runtime — model, gate, queues.
//
// The context pair is the reason this panel exists in this shape. The declared
// num_ctx is what the app ASKS for; the MLX runner does not read it at all and
// the GGUF runner truncates well below it. So the declared value is never
// shown alone: it stands next to what Ollama actually counted, with the real
// ceiling marked across both bars. A single number here would send a search
// for a truncation bug in exactly the wrong direction.

import { el, card, stat, statRow, pill, table, num, ms, clock, empty, sectionError } from '../dom.js';
import { meter, pairMeter, splitBars, hbars } from '../viz.js';

export const meta = { id: 'runtime', command: 'runtime', label: 'Runtime', hint: 'model · gate' };

export function beacon(data) {
  if (!data || data.error) return null;
  const g = data.gate;
  if (!g) return { label: 'gate', text: 'no gate', tone: 'mute' };
  const waiting = (g.interactiveWaiting || 0) + (g.backgroundWaiting || 0);
  return {
    label: 'llm gate',
    text: `${num(g.inUse)}/${num(g.permits)}${waiting ? ` · ${waiting} waiting` : ''}`,
    tone: waiting > 4 ? 'bad' : waiting ? 'warn' : 'ok',
  };
}

/** Gauges read better grouped by their dotted prefix than as one flat list. */
function gaugeGroups(gauges) {
  const groups = new Map();
  for (const [name, value] of Object.entries(gauges || {})) {
    const key = name.split('.')[0];
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push([name, value]);
  }
  return groups;
}

export function create() {
  const root = el('div', 'dbg-panel-body');
  return {
    root,
    update(data) {
      root.replaceChildren();
      if (data.error) { root.appendChild(sectionError(data.error)); return; }

      const m = data.model, measured = data.llmMeasured || {};
      const model = card('Context window', 'declared vs. measured — never one of them alone');
      if (!m) model.body.appendChild(empty('no model bound in this run'));
      else {
        const row = el('div', 'dbg-model-head');
        row.appendChild(pill(m.tag || 'unknown model', 'info'));
        row.appendChild(pill(`runner: ${m.runner}`, m.contextEnforcedByRunner ? 'mute' : 'warn'));
        row.appendChild(pill(`num_predict ${num(m.numPredict)}`, 'mute'));
        model.body.appendChild(row);
        model.body.appendChild(pairMeter({
          declaredLabel: 'declared num_ctx',
          declared: m.contextTokensDeclared,
          measuredLabel: 'measured peak tokens in',
          measured: measured.maxTokensIn || 0,
          limit: m.contextEnforcedByRunner ? m.truncationLimitIfGguf : 0,
          limitLabel: 'truncation point',
          note: m.contextEnforcedByRunner
            ? `GGUF runner: Ollama truncates the prompt at ~${num(m.truncationLimitIfGguf)} tokens, not at the declared ${num(m.contextTokensDeclared)}.`
            : `MLX runner: num_ctx is ignored entirely — the declared ${num(m.contextTokensDeclared)} has no effect on what the runner accepts.`,
          noteTone: 'warn',
        }));
        model.body.appendChild(statRow(
          stat('calls', num(measured.calls)),
          stat('last in / out', `${num(measured.lastTokensIn)} / ${num(measured.lastTokensOut)}`),
          stat('peak in / out', `${num(measured.maxTokensIn)} / ${num(measured.maxTokensOut)}`),
          stat('total in / out', `${num(measured.totalTokensIn)} / ${num(measured.totalTokensOut)}`),
          stat('gate wait total', ms(measured.totalGateWaitMs), 'mute'),
          stat('generation total', ms(measured.totalGenMs), 'mute'),
        ));
      }
      root.appendChild(model.card);

      const g = data.gate;
      const gate = card('LLM gate', 'permits in use, and who is queueing behind them');
      if (!g) gate.body.appendChild(empty('no gate in this run'));
      else {
        gate.body.appendChild(meter(g.inUse, g.permits, {
          tone: g.inUse >= g.permits ? 'warn' : 'ok',
          caption: `${num(g.inUse)} of ${num(g.permits)} permits in use`,
          right: g.priority ? `priority: ${g.priority}` : '',
        }));
        gate.body.appendChild(statRow(
          stat('interactive waiting', num(g.interactiveWaiting), g.interactiveWaiting ? 'warn' : null),
          stat('background waiting', num(g.backgroundWaiting), g.backgroundWaiting > 4 ? 'warn' : 'mute'),
          stat('interactive streak', num(g.interactiveStreak), 'mute', 'consecutive interactive grants'),
          stat('editorial queue', `${num(data.editorialQueue?.size)} queued · ${num(data.editorialQueue?.inFlight)} in flight`,
            data.editorialQueue?.size > 20 ? 'warn' : null),
        ));
      }
      root.appendChild(gate.card);

      const calls = (data.llmCalls || []).slice().reverse();
      const callCard = card('Recent calls', 'where the wall-clock of a call went — waiting for a permit, or generating');
      callCard.body.appendChild(splitBars(
        calls.slice(0, 24).map(c => ({
          label: clock(c.atMs),
          value: ms((c.gateWaitMs || 0) + (c.genMs || 0)),
          parts: [
            { name: 'gate wait', value: c.gateWaitMs || 0, tone: c.gateWaitMs > (c.genMs || 0) ? 'warn' : 'mute' },
            { name: 'generation', value: c.genMs || 0, tone: 'info' },
          ],
        })),
        { legend: [['gate wait', 'mute'], ['generation', 'info']] },
      ));
      callCard.body.appendChild(table(['Time', 'Thread', 'Gate wait', 'Generation', 'Tokens in', 'Tokens out'],
        calls.slice(0, 40).map(c => ({
          tone: c.gateWaitMs > 5000 ? 'warn' : null,
          cells: [clock(c.atMs), c.thread, ms(c.gateWaitMs), ms(c.genMs), num(c.tokensIn), num(c.tokensOut)],
        }))));
      if (!calls.length) callCard.body.appendChild(empty('the model has not been called yet'));
      root.appendChild(callCard.card);

      const gauges = data.gauges || {};
      const gaugeCard = card('Gauges', 'live counters from the digest caches and pipeline pools');
      for (const [group, entries] of gaugeGroups(gauges)) {
        gaugeCard.body.appendChild(el('h4', 'dbg-sub', group));
        gaugeCard.body.appendChild(hbars(
          entries.map(([name, value]) => ({
            label: name.slice(group.length + 1),
            value,
            tone: name.endsWith('discarded') && value > 0 ? 'bad' : 'info',
          })),
        ));
      }
      if (!Object.keys(gauges).length) gaugeCard.body.appendChild(empty('no gauges registered'));
      root.appendChild(gaugeCard.card);
    },
  };
}
