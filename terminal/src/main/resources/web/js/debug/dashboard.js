// The debug dashboard — an isolated full-screen command centre over the
// terminal. Dev-only: it is loaded by main.js ONLY after the bridge has
// answered a `debug` probe, its assets are excluded from the package, and the
// bridge binding does not exist in a shipped build.
//
// THE PERFORMANCE CONTRACT (the whole point of the on-demand bridge):
//   * closed  -> not one request. The loop is not merely paused, it is cleared,
//                and pending requests are abandoned.
//   * open    -> the ACTIVE panel refreshes on its own cadence (2 s default,
//                switchable, pausable). Everything else refreshes only on the
//                slow beacon sweep, so the header can still say "sources: 2
//                down" without eight panels polling in the background.
//   * hidden  -> window/tab not visible: the loop stops until it returns.
// Nothing here holds an interval that outlives a close().

import { DebugClient } from './client.js';
import { el } from './dom.js';
import * as overview from './panels/overview.js';
import * as sources from './panels/sources.js';
import * as collectors from './panels/collectors.js';
import * as basin from './panels/basin.js';
import * as runtime from './panels/runtime.js';
import * as config from './panels/config.js';
import * as log from './panels/log.js';
import * as reddit from './panels/reddit.js';
import * as subjects from './panels/subjects.js';

const PANELS = [overview, sources, collectors, basin, runtime, subjects, config, log, reddit];
// Commands that feed a header beacon; `config` is not among them (it does not
// change on its own, so it is only fetched when its panel is open).
const BEACON_COMMANDS = ['overview', 'sources', 'collectors', 'basin', 'runtime', 'subjects', 'log', 'reddit'];
const SWEEP_MS = 20_000;
const RATES = [[2000, '2 s'], [5000, '5 s'], [10_000, '10 s'], [0, 'manual']];
const SPANS = [[5 * 60_000, '5 m'], [15 * 60_000, '15 m'], [60 * 60_000, '1 h'], [6 * 60 * 60_000, '6 h']];

const ICON = '<svg viewBox="0 0 24 24"><path d="M8 4h8l-1 3H9L8 4z"/><rect x="6" y="7" width="12" height="12" rx="5"/>'
  + '<path d="M3 11h3M18 11h3M3 16h3M18 16h3M9 21l1-2M15 21l-1-2M9 3 8 1M15 3l1-2"/></svg>';

const REFRESH_ICON = '<svg viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.3-5.6"/><path d="M20 4v5h-5"/></svg>';

export function initDebug(socket) {
  if (document.querySelector('.js-debug-toggle')) return;   // never twice
  const client = new DebugClient(socket);

  // The stylesheet is pulled in from here, not from index.html: the shipped
  // page must carry no trace of the tool, and /css/debug.css is not in the JAR.
  if (!document.querySelector('link[data-debug-css]')) {
    const link = el('link');
    link.rel = 'stylesheet';
    link.href = '/css/debug.css';
    link.dataset.debugCss = '1';
    document.head.appendChild(link);
  }

  const btn = el('button', 'iconbtn debug-btn js-debug-toggle');
  btn.type = 'button';
  btn.title = 'Debug dashboard (dev only)';
  btn.setAttribute('aria-label', 'Debug dashboard');
  btn.innerHTML = ICON;
  const grid = document.querySelector('.js-grid-toggle');
  if (grid && grid.parentNode) grid.parentNode.insertBefore(btn, grid);
  else document.querySelector('.tb-actions')?.appendChild(btn);

  const dash = buildDashboard(client);
  btn.addEventListener('click', () => dash.toggle());
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && dash.isOpen()) { e.stopPropagation(); dash.close(); }
    else if (e.key === 'D' && e.shiftKey && (e.ctrlKey || e.metaKey)) { e.preventDefault(); dash.toggle(); }
  }, true);
}

function buildDashboard(client) {
  const view = el('div', 'dbg-view');
  view.hidden = true;
  view.setAttribute('role', 'dialog');
  view.setAttribute('aria-label', 'Debug dashboard');

  // ---- header -------------------------------------------------------
  const head = el('header', 'dbg-head');
  const topRow = el('div', 'dbg-head-row');
  const brand = el('div', 'dbg-brand');
  brand.appendChild(el('span', 'dbg-brand-dot'));
  brand.appendChild(el('span', 'dbg-brand-name', 'DEBUG'));
  topRow.appendChild(brand);

  // The beacon strip gets its own row: it is the first thing to read, and it
  // must not push the controls around as its wording changes.
  const beacons = el('div', 'dbg-beacons');

  const controls = el('div', 'dbg-controls');
  const spanSeg = segmented(SPANS, 15 * 60_000, v => { state.spanMs = v; renderActive(); });
  const rateSeg = segmented(RATES, 2000, v => { state.rateMs = v; restartLoop(); });
  controls.appendChild(labelled('window', spanSeg.node));
  controls.appendChild(labelled('refresh', rateSeg.node));
  // Icon, not a word: on a line that already carries two segmented controls a
  // third labelled button is what makes the header read as a toolbar.
  const refreshBtn = el('button', 'dbg-icon');
  refreshBtn.type = 'button';
  refreshBtn.title = 'Refresh now';
  refreshBtn.setAttribute('aria-label', 'Refresh now');
  refreshBtn.innerHTML = REFRESH_ICON;
  refreshBtn.addEventListener('click', () => { pullActive(); sweep(); });
  controls.appendChild(refreshBtn);
  const status = el('span', 'dbg-status', '');
  controls.appendChild(status);
  const closeBtn = el('button', 'dbg-close', '✕');
  closeBtn.title = 'Close (Esc)';
  closeBtn.addEventListener('click', () => close());
  controls.appendChild(closeBtn);
  topRow.appendChild(controls);
  head.appendChild(topRow);
  head.appendChild(beacons);
  view.appendChild(head);

  // ---- body: rail + panel ------------------------------------------
  const body = el('div', 'dbg-body');
  const rail = el('nav', 'dbg-rail');
  const stage = el('div', 'dbg-stage');
  body.appendChild(rail);
  body.appendChild(stage);
  view.appendChild(body);

  const state = { open: false, active: 'overview', spanMs: 15 * 60_000, rateMs: 2000, timer: null, sweepTimer: null };
  const cache = new Map();      // command -> {data, at}
  const instances = new Map();  // panel id -> {root, update}
  const tabs = new Map();

  for (const panel of PANELS) {
    const tab = el('button', 'dbg-tab');
    tab.appendChild(el('span', 'dbg-tab-dot'));
    const text = el('span', 'dbg-tab-text');
    text.appendChild(el('span', 'dbg-tab-label', panel.meta.label));
    text.appendChild(el('span', 'dbg-tab-hint', panel.meta.hint));
    tab.appendChild(text);
    tab.addEventListener('click', () => select(panel.meta.id));
    tabs.set(panel.meta.id, tab);
    rail.appendChild(tab);
  }
  const railNote = el('p', 'dbg-rail-note', 'Only the open panel refreshes.');
  rail.appendChild(railNote);

  document.body.appendChild(view);

  function panelById(id) { return PANELS.find(p => p.meta.id === id); }

  function instanceFor(id) {
    if (!instances.has(id)) {
      const inst = panelById(id).create();
      instances.set(id, inst);
    }
    return instances.get(id);
  }

  function select(id) {
    state.active = id;
    for (const [tabId, tab] of tabs) tab.dataset.active = String(tabId === id);
    stage.replaceChildren(instanceFor(id).root);
    renderActive();
    pullActive();          // the newly opened panel is the one that may be stale
    restartLoop();
  }

  function renderActive() {
    const entry = cache.get(panelById(state.active).meta.command);
    if (!entry) return;
    instanceFor(state.active).update(entry.data, { now: Date.now(), spanMs: state.spanMs });
  }

  function renderBeacons() {
    beacons.replaceChildren();
    for (const panel of PANELS) {
      const entry = cache.get(panel.meta.command);
      const b = entry && panel.beacon ? panel.beacon(entry.data, Date.now()) : null;
      if (!b) continue;
      const node = el('button', 'dbg-beacon');
      node.dataset.tone = b.tone;
      node.appendChild(el('span', 'dbg-beacon-label', b.label));
      node.appendChild(el('span', 'dbg-beacon-value', b.text));
      node.addEventListener('click', () => select(panel.meta.id));
      beacons.appendChild(node);
      const tab = tabs.get(panel.meta.id);
      if (tab) tab.dataset.tone = b.tone;
    }
  }

  function note(text, tone) {
    status.textContent = text;
    status.dataset.tone = tone || '';
  }

  async function pull(command) {
    try {
      const answer = await client.ask(command);
      cache.set(command, { data: answer.data, at: answer.at });
      if (!state.open) return;                     // closed while in flight
      if (panelById(state.active).meta.command === command) renderActive();
      renderBeacons();
      note(`answered ${new Date(answer.at).toLocaleTimeString()}`, 'ok');
    } catch (e) {
      if (state.open) note(String(e.message || e), 'bad');
    }
  }

  function pullActive() { if (state.open) pull(panelById(state.active).meta.command); }

  /** The slow lap that keeps the header honest without eight live panels. */
  function sweep() {
    if (!state.open) return;
    const active = panelById(state.active).meta.command;
    for (const command of BEACON_COMMANDS) if (command !== active) pull(command);
  }

  function restartLoop() {
    clearInterval(state.timer);
    state.timer = null;
    if (!state.open || document.hidden || !state.rateMs) return;
    state.timer = setInterval(pullActive, state.rateMs);
  }

  function restartSweep() {
    clearInterval(state.sweepTimer);
    state.sweepTimer = null;
    if (!state.open || document.hidden) return;
    state.sweepTimer = setInterval(sweep, SWEEP_MS);
  }

  function open() {
    if (state.open) return;
    state.open = true;
    view.hidden = false;
    document.querySelector('.js-debug-toggle')?.setAttribute('aria-pressed', 'true');
    select(state.active);
    sweep();                       // one full lap so the beacons mean something
    restartSweep();
  }

  function close() {
    if (!state.open) return;
    state.open = false;
    view.hidden = true;
    document.querySelector('.js-debug-toggle')?.setAttribute('aria-pressed', 'false');
    clearInterval(state.timer); state.timer = null;
    clearInterval(state.sweepTimer); state.sweepTimer = null;
    client.abandon();              // nothing in flight survives the close
  }

  document.addEventListener('visibilitychange', () => { restartLoop(); restartSweep(); });

  return {
    open, close,
    isOpen: () => state.open,
    toggle: () => (state.open ? close() : open()),
  };
}

// ---- small controls ---------------------------------------------------

function segmented(options, initial, onChange) {
  const node = el('div', 'dbg-seg');
  let value = initial;
  const buttons = options.map(([v, label]) => {
    const b = el('button', 'dbg-seg-btn', label);
    b.addEventListener('click', () => {
      value = v;
      for (const other of buttons) other.dataset.active = String(other === b);
      onChange(v);
    });
    b.dataset.active = String(v === initial);
    node.appendChild(b);
    return b;
  });
  return { node, get value() { return value; } };
}

function labelled(text, node) {
  const wrap = el('div', 'dbg-control');
  wrap.appendChild(el('span', 'dbg-control-label', text));
  wrap.appendChild(node);
  return wrap;
}
