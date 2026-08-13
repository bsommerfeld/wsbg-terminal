// Subjects — the living register, read as an answer to two questions the app
// has actually failed at, not as a table dump:
//
//   1. "Is this subject even the right paper?"  Both known failures ("Gemini"
//      resolved to a junk coin on CCC at 0.0001, "IREN" priced off a Milan
//      utility while its news was about the Nasdaq paper) had a MATCHING name
//      and ticker — the contradiction sat in symbol, venue, currency, ISIN
//      country and price magnitude. So the identity check is a pair: what the
//      pipeline claims on the left, what Yahoo actually answered on the right,
//      with the diverging field marked. Suspects come first; the clean ones
//      are one click away.
//
//   2. "Why does no headline arrive for this subject?"  uncomposedEvidence,
//      dirtySinceMs, lastComposedAtMs and evidenceVersion answer it. A unit
//      dirty for twenty minutes with uncomposed evidence is stuck — that is
//      shown as an elapsed bar against the stall threshold, not as a timestamp
//      the reader has to subtract.
//
// Everything else (names, counters, history) is context and lives in the
// register table, one expandable row per unit.
//
// The panel holds no timer of its own: the shell polls only while it is the
// open panel and the window is visible.

import { el, card, stat, statRow, pill, num, ms, since, clock, empty, sectionError } from '../dom.js';
import { meter } from '../viz.js';

export const meta = { id: 'subjects', command: 'subjects', label: 'Subjects', hint: 'identity · backlog' };

/** Dirty for longer than this with nothing composed = the unit is stuck. */
const STALL_MS = 10 * 60_000;

// Yahoo's exchange codes, mapped to the country the price actually comes from.
// Only used to contrast with the ISIN's country — an unknown code says nothing
// and is never flagged.
const VENUE_COUNTRY = {
  NMS: 'US', NGM: 'US', NCM: 'US', NYQ: 'US', NYS: 'US', PCX: 'US', ASE: 'US',
  BTS: 'US', PNK: 'US', OTC: 'US', OQB: 'US', OQX: 'US',
  GER: 'DE', FRA: 'DE', BER: 'DE', HAM: 'DE', MUN: 'DE', STU: 'DE', DUS: 'DE', HAN: 'DE',
  MIL: 'IT', LSE: 'GB', IOB: 'GB', PAR: 'FR', AMS: 'NL', BRU: 'BE', MCE: 'ES',
  VIE: 'AT', LIS: 'PT', DUB: 'IE', EBS: 'CH', SWX: 'CH', VTX: 'CH',
  TOR: 'CA', VAN: 'CA', CNQ: 'CA', HKG: 'HK', TYO: 'JP', JPX: 'JP', ASX: 'AU',
  STO: 'SE', CPH: 'DK', OSL: 'NO', HEL: 'FI', SAO: 'BR', MEX: 'MX',
  CCC: 'crypto', CCY: 'fx',
};
const EURO = new Set(['DE', 'IT', 'FR', 'NL', 'BE', 'ES', 'AT', 'IE', 'FI', 'PT', 'GR', 'LU', 'SK', 'SI', 'EE', 'LV', 'LT', 'CY', 'MT']);
// What a venue in this country normally quotes in. A price in a currency the
// venue does not use is an inconsistency inside the answer itself.
const COUNTRY_CURRENCY = {
  US: 'USD', CA: 'CAD', GB: 'GBP', CH: 'CHF', JP: 'JPY', HK: 'HKD', AU: 'AUD',
  SE: 'SEK', DK: 'DKK', NO: 'NOK', BR: 'BRL', MX: 'MXN',
};

const norm = s => (s == null ? '' : String(s).trim().toUpperCase().replace(/^\$/, ''));

/** Prices span a junk coin and a €4000 share — significant digits, not 2 decimals. */
export function price(v, currency) {
  if (v == null || Number.isNaN(v)) return '—';
  const n = Number(v);
  const abs = Math.abs(n);
  const text = abs >= 1000 ? n.toFixed(0) : abs >= 1 ? n.toFixed(2) : abs >= 0.01 ? n.toFixed(4) : n.toPrecision(2);
  return currency ? `${text} ${currency}` : text;
}

/**
 * The identity verdict for one unit: every way in which "what we claim" and
 * "what Yahoo answered" disagree. `field` names the resolved-side chip the
 * flag belongs to, so the divergence is marked where it is visible.
 */
export function identityFlags(u) {
  const flags = [];
  const m = u.market;
  const ticker = norm(u.ticker);
  const isinCountry = u.isin ? String(u.isin).slice(0, 2).toUpperCase() : null;

  if (!m) {
    if (u.instrument) {
      flags.push({ tone: 'warn', field: 'symbol', text: 'an instrument, but no market data was ever resolved' });
    }
    return flags;
  }
  const sym = norm(m.symbol);
  const [base, suffix] = sym.split('.');
  const venue = (m.exchange || '').toUpperCase();
  const venueCountry = VENUE_COUNTRY[venue];
  const cur = m.currency ? m.currency.toUpperCase() : null;

  if (venueCountry === 'crypto' || venueCountry === 'fx' || /-(USD|USDT|EUR)$/.test(sym)) {
    flags.push({ tone: 'bad', field: 'exchange', text: `priced off a ${venueCountry === 'fx' ? 'currency' : 'crypto'} venue (${m.exchange || '?'}) — not an equity quote` });
  }
  if (ticker && base && base !== ticker) {
    flags.push({ tone: 'bad', field: 'symbol', text: `we claim ${u.ticker}, Yahoo answered ${m.symbol}` });
  } else if (ticker && suffix) {
    // The IREN shape: right letters, foreign line. Whether that line is the
    // right company cannot be decided from the register alone (a German
    // subject on .DE is perfectly correct), so this is "look at it", not
    // "it is broken" — the pair above it is what settles the question.
    flags.push({
      tone: 'warn',
      field: 'symbol',
      text: `we claim the bare ${u.ticker}, the price comes from the .${suffix} listing`
        + (cur && cur !== 'USD' ? ` in ${cur}` : ''),
    });
  }
  if (m.price != null && Number(m.price) > 0 && Number(m.price) < 0.01) {
    flags.push({ tone: 'bad', field: 'price', text: `${price(m.price, m.currency)} — sub-cent, the price of a worthless coin` });
  }
  if (isinCountry && venueCountry && venueCountry.length === 2 && isinCountry !== venueCountry) {
    flags.push({ tone: 'warn', field: 'exchange', text: `ISIN is ${isinCountry}, the price comes from ${venue} (${venueCountry}) — cross-listing, or the wrong paper` });
  }
  // Currency against the venue, not against the ISIN: a US venue quoting EUR
  // is an inconsistency inside the answer itself, whoever the issuer is.
  if (cur && venueCountry && venueCountry.length === 2) {
    const expected = EURO.has(venueCountry) ? 'EUR' : COUNTRY_CURRENCY[venueCountry];
    if (expected && expected !== cur && !(expected === 'GBP' && cur === 'GBP')) {
      flags.push({ tone: 'warn', field: 'currency', text: `${venue} quotes in ${expected}, this price is in ${cur}` });
    }
  }
  return flags;
}

const worst = flags => (flags.some(f => f.tone === 'bad') ? 'bad' : flags.length ? 'warn' : 'ok');

/** How long has this unit been waiting for a compose, and is that too long? */
export function stall(u, now) {
  const neverComposed = !u.lastComposedAtMs && (u.evidenceCount > 0 || u.newsCount > 0);
  if (!u.dirtySinceMs && !u.uncomposedEvidence && !neverComposed) return null;
  const dirtyForMs = u.dirtySinceMs ? Math.max(0, now - u.dirtySinceMs) : 0;
  const reason = u.dirtySinceMs ? 'dirty' : u.uncomposedEvidence ? 'unconsumed' : 'never';
  const tone = dirtyForMs > STALL_MS ? 'bad' : reason === 'dirty' || u.uncomposedEvidence ? 'warn' : 'info';
  return { dirtyForMs, tone, reason };
}

export function beacon(data, now = Date.now()) {
  if (!data || data.error) return null;
  const units = data.units || [];
  const suspects = units.filter(u => identityFlags(u).length).length;
  const bad = units.filter(u => identityFlags(u).some(f => f.tone === 'bad')).length;
  const stalled = units.filter(u => stall(u, now)?.tone === 'bad').length;
  if (bad) return { label: 'subjects', text: `${bad} wrong paper?`, tone: 'bad' };
  if (stalled) return { label: 'subjects', text: `${stalled} stalled`, tone: 'warn' };
  if (suspects) return { label: 'subjects', text: `${suspects} to check`, tone: 'warn' };
  return { label: 'subjects', text: `${num(data.total)} units · ${num(data.dirtyCount)} dirty`, tone: 'ok' };
}

// ---- identity block ---------------------------------------------------

function chip(label, value, tone) {
  const c = el('div', 'dbg-chip');
  if (tone) c.dataset.tone = tone;
  c.appendChild(el('span', 'dbg-chip-label', label));
  c.appendChild(el('span', 'dbg-chip-value', value == null || value === '' ? '—' : String(value)));
  return c;
}

function identityBlock(u, flags, now) {
  const tone = worst(flags);
  const box = el('div', 'dbg-ident');
  box.dataset.tone = tone;

  const head = el('div', 'dbg-ident-head');
  head.appendChild(el('span', 'dbg-ident-name', u.canonicalName || u.id));
  head.appendChild(pill(u.instrument ? 'instrument' : 'theme / person', u.instrument ? 'info' : 'mute'));
  if (u.dirty) head.appendChild(pill('dirty', 'warn'));
  head.appendChild(el('span', 'dbg-ident-age', `active ${since(u.lastActivityMs, now)}`));
  box.appendChild(head);

  const fieldTone = f => {
    const hit = flags.filter(x => x.field === f);
    return hit.some(x => x.tone === 'bad') ? 'bad' : hit.length ? 'warn' : null;
  };

  const pair = el('div', 'dbg-ident-pair');
  const left = el('div', 'dbg-ident-side');
  left.appendChild(el('span', 'dbg-ident-side-label', 'claimed by the pipeline'));
  const leftChips = el('div', 'dbg-chip-row');
  leftChips.appendChild(chip('ticker', u.ticker));
  leftChips.appendChild(chip('isin', u.isin));
  leftChips.appendChild(chip('id', u.id));
  left.appendChild(leftChips);
  pair.appendChild(left);

  pair.appendChild(el('span', 'dbg-ident-arrow', '→'));

  const right = el('div', 'dbg-ident-side');
  right.appendChild(el('span', 'dbg-ident-side-label', 'what Yahoo answered'));
  const rightChips = el('div', 'dbg-chip-row');
  const m = u.market;
  rightChips.appendChild(chip('symbol', m ? m.symbol : 'not resolved', fieldTone('symbol')));
  const venueCountry = m ? VENUE_COUNTRY[(m.exchange || '').toUpperCase()] : null;
  rightChips.appendChild(chip('exchange',
    m ? (venueCountry ? `${m.exchange} · ${venueCountry}` : m.exchange) : null, fieldTone('exchange')));
  rightChips.appendChild(chip('currency', m ? m.currency : null, fieldTone('currency')));
  rightChips.appendChild(chip('price', m ? price(m.price, null) : null, fieldTone('price')));
  if (m && m.dayChangePercent != null) {
    rightChips.appendChild(chip('day', `${Number(m.dayChangePercent) >= 0 ? '+' : ''}${Number(m.dayChangePercent).toFixed(2)} %`));
  }
  right.appendChild(rightChips);
  pair.appendChild(right);
  box.appendChild(pair);

  if (flags.length) {
    const list = el('ul', 'dbg-ident-flags');
    for (const f of flags) {
      const li = el('li', null, f.text);
      li.dataset.tone = f.tone;
      list.appendChild(li);
    }
    box.appendChild(list);
  }
  return box;
}

// ---- panel ------------------------------------------------------------

export function create() {
  const root = el('div', 'dbg-panel-body');
  let last = null, lastNow = Date.now();
  let showClean = false, query = '';
  const open = new Set();          // unit ids whose detail row is expanded

  const cleanToggle = el('button', 'dbg-btn', 'show the clean ones');
  cleanToggle.addEventListener('click', () => { showClean = !showClean; render(); });

  const search = el('input', 'dbg-input');
  search.type = 'search';
  search.placeholder = 'filter name, ticker, isin, symbol…';
  search.addEventListener('input', () => { query = search.value.trim().toLowerCase(); render(); });

  function matches(u) {
    if (!query) return true;
    return `${u.canonicalName} ${u.ticker || ''} ${u.isin || ''} ${u.id} ${u.market?.symbol || ''}`
      .toLowerCase().includes(query);
  }

  function detailBlock(u, flags, now) {
    const box = el('div', 'dbg-detail');

    const times = statRow(
      stat('first seen', since(u.firstSeenMs, now), 'mute', clock(u.firstSeenMs)),
      stat('last activity', since(u.lastActivityMs, now), 'mute', clock(u.lastActivityMs)),
      stat('last composed', u.lastComposedAtMs ? since(u.lastComposedAtMs, now) : 'never',
        u.lastComposedAtMs ? 'mute' : 'warn'),
      stat('evidence version', num(u.evidenceVersion), 'mute', 'bumps on every new piece of evidence'),
      stat('headlines', num(u.headlineCount), u.headlineCount ? 'ok' : 'mute'),
    );
    box.appendChild(times);

    const bars = el('div', 'dbg-detail-bars');
    bars.appendChild(meter(u.seenEvidenceCount, u.evidenceCount || 1, {
      tone: u.uncomposedEvidence ? 'warn' : 'ok',
      caption: 'evidence consumed',
      right: `${num(u.seenEvidenceCount)} / ${num(u.evidenceCount)}`,
    }));
    bars.appendChild(meter(u.coveredNewsCount, u.newsCount || 1, {
      tone: u.newsCount && u.coveredNewsCount < u.newsCount ? 'info' : 'ok',
      caption: 'news covered by a headline',
      right: `${num(u.coveredNewsCount)} / ${num(u.newsCount)}`,
    }));
    box.appendChild(bars);

    if (u.firstPrice != null || u.market?.price != null) {
      const cur = u.market?.currency || null;
      const from = u.firstPrice, to = u.market?.price;
      const move = from && to ? ((to - from) / from) * 100 : null;
      box.appendChild(statRow(
        stat('first price', price(from, cur), 'mute', u.firstPriceAtMs ? since(u.firstPriceAtMs, now) : null),
        stat('now', price(to, cur), 'mute'),
        stat('since first seen', move == null ? '—' : `${move >= 0 ? '+' : ''}${move.toFixed(1)} %`,
          move == null ? 'mute' : move >= 0 ? 'ok' : 'bad'),
      ));
    }

    if (u.lastHeadline) {
      const h = el('div', 'dbg-headline');
      const meta2 = el('div', 'dbg-headline-meta');
      meta2.appendChild(pill(u.lastHeadline.sentiment || 'no sentiment', u.lastHeadline.sentiment ? 'info' : 'mute'));
      meta2.appendChild(el('span', null, since(u.lastHeadline.atMs, now)));
      h.appendChild(meta2);
      h.appendChild(el('p', 'dbg-headline-text', u.lastHeadline.text));
      box.appendChild(h);
    } else {
      box.appendChild(empty('this subject has never published a headline'));
    }

    box.appendChild(identityBlock(u, flags, now));
    return box;
  }

  function registerRow(u, flags, now, tbody) {
    const st = stall(u, now);
    const tr = el('tr', 'dbg-row-head');
    const tone = worst(flags) === 'bad' ? 'bad' : st?.tone === 'bad' ? 'bad' : worst(flags) === 'warn' || st ? 'warn' : null;
    if (tone) tr.dataset.tone = tone;

    const name = el('td');
    const caret = el('span', 'dbg-caret', open.has(u.id) ? '▾' : '▸');
    name.appendChild(caret);
    name.appendChild(el('span', 'dbg-row-name', u.canonicalName || u.id));
    tr.appendChild(name);

    const cells = [
      u.ticker || (u.instrument ? '—' : 'theme'),
      u.market?.symbol || '—',
      u.market ? price(u.market.price, u.market.currency) : '—',
      u.market?.exchange || '—',
      flags.length ? pill(`${flags.length} flag${flags.length > 1 ? 's' : ''}`, worst(flags)) : '',
      u.dirty ? (u.dirtySinceMs ? ms(now - u.dirtySinceMs) : 'yes') : '',
      u.uncomposedEvidence ? 'yes' : '',
      `${num(u.seenEvidenceCount)}/${num(u.evidenceCount)}`,
      `${num(u.coveredNewsCount)}/${num(u.newsCount)}`,
      num(u.headlineCount),
      since(u.lastActivityMs, now),
    ];
    for (const c of cells) {
      const td = el('td');
      if (c instanceof Node) td.appendChild(c); else td.textContent = c;
      tr.appendChild(td);
    }
    tbody.appendChild(tr);

    if (open.has(u.id)) {
      const detail = el('tr', 'dbg-row-detail');
      const td = el('td');
      td.colSpan = cells.length + 1;
      td.appendChild(detailBlock(u, flags, now));
      detail.appendChild(td);
      tbody.appendChild(detail);
    }
    tr.addEventListener('click', () => {
      if (open.has(u.id)) open.delete(u.id); else open.add(u.id);
      render();
    });
  }

  function render() {
    const data = last, now = lastNow;
    root.replaceChildren();
    if (!data) return;
    if (data.error) { root.appendChild(sectionError(data.error)); return; }

    const units = data.units || [];
    const flagged = new Map(units.map(u => [u.id, identityFlags(u)]));
    const suspects = units.filter(u => flagged.get(u.id).length);
    const stalled = units.filter(u => stall(u, now)?.tone === 'bad');

    // ---- the register in numbers (plain numbers stay plain numbers) ----
    const top = card('Register', data.shown < data.total
      ? `${num(data.shown)} of ${num(data.total)} units shown — interesting-first, the tail is cut`
      : 'the whole register, interesting-first');
    top.body.appendChild(statRow(
      stat('units', num(data.total)),
      stat('instruments', num(data.instrumentCount), 'mute', `${num(data.total - data.instrumentCount)} themes / people`),
      stat('dirty', num(data.dirtyCount), data.dirtyCount ? 'warn' : 'ok', 'waiting for a compose'),
      stat('identity suspects', num(suspects.length), suspects.some(u => flagged.get(u.id).some(f => f.tone === 'bad')) ? 'bad' : suspects.length ? 'warn' : 'ok'),
      stat('stalled', num(stalled.length), stalled.length ? 'bad' : 'ok', `dirty for more than ${ms(STALL_MS)}`),
    ));
    root.appendChild(top.card);

    // ---- 1. is this the right paper? -----------------------------------
    const idCard = card('Identity check',
      'what we claim against what Yahoo answered — red is a contradiction inside the answer, amber means the pair has to be read (a foreign listing can be perfectly correct)');
    idCard.head.appendChild(cleanToggle);
    const clean = units.filter(u => !flagged.get(u.id).length);
    cleanToggle.textContent = showClean ? 'only the suspects' : `show the ${clean.length} clean ones`;
    const idList = el('div', 'dbg-ident-list');
    const rank = { bad: 0, warn: 1, ok: 2 };
    const idUnits = (showClean ? units : suspects).filter(matches)
      .slice().sort((a, b) => rank[worst(flagged.get(a.id))] - rank[worst(flagged.get(b.id))]);
    for (const u of idUnits) idList.appendChild(identityBlock(u, flagged.get(u.id), now));
    idCard.body.appendChild(idList);
    if (!idUnits.length) {
      idCard.body.appendChild(empty(suspects.length
        ? 'no subject matches the filter'
        : 'every resolved subject agrees with its ticker, ISIN, venue and currency'));
    }
    root.appendChild(idCard.card);

    // ---- 2. why does no headline arrive? -------------------------------
    const waiting = units.filter(u => stall(u, now)).filter(matches);
    const backlog = card('Compose backlog',
      `why no headline arrives — dirty since when, evidence still unconsumed, or never composed at all; past ${ms(STALL_MS)} dirty the unit is stuck`);
    const list = el('div', 'dbg-backlog');
    for (const u of waiting) {
      const st = stall(u, now);
      const row = el('div', 'dbg-backlog-row');
      row.dataset.tone = st.tone;
      const head = el('div', 'dbg-backlog-head');
      head.appendChild(el('span', 'dbg-backlog-name', u.canonicalName || u.id));
      if (u.uncomposedEvidence) head.appendChild(pill('uncomposed evidence', 'warn'));
      if (u.dirty) head.appendChild(pill('dirty', st.tone === 'bad' ? 'bad' : 'warn'));
      if (st.reason === 'never') head.appendChild(pill('never composed', 'info'));
      head.appendChild(el('span', 'dbg-backlog-note',
        `v${num(u.evidenceVersion)} · last composed ${u.lastComposedAtMs ? since(u.lastComposedAtMs, now) : 'never'} · `
        + `${num(u.evidenceCount - u.seenEvidenceCount)} of ${num(u.evidenceCount)} evidence unseen`));
      row.appendChild(head);
      // A dirty unit gets the elapsed bar against the stall mark; the other two
      // cases have nothing running against a threshold, so they stay a sentence.
      if (st.reason === 'dirty') {
        row.appendChild(meter(Math.min(st.dirtyForMs, STALL_MS * 2), STALL_MS * 2, {
          tone: st.tone,
          caption: `dirty for ${ms(st.dirtyForMs)}`,
          right: `stall mark ${ms(STALL_MS)}`,
        }));
      } else {
        row.appendChild(el('p', 'dbg-backlog-line', st.reason === 'unconsumed'
          ? 'not dirty — but evidence sits unconsumed'
          : `never composed — ${num(u.evidenceCount)} evidence, ${num(u.newsCount)} news, no headline`));
      }
      list.appendChild(row);
    }
    backlog.body.appendChild(list);
    if (!waiting.length) backlog.body.appendChild(empty('nothing is waiting — every unit is composed up to date'));
    root.appendChild(backlog.card);

    // ---- the register itself, details on request ------------------------
    const reg = card('All subjects', 'click a row for the whole unit');
    const bar = el('div', 'dbg-filterbar');
    bar.appendChild(search);
    reg.body.appendChild(bar);
    const t = el('table', 'dbg-table dbg-subjects');
    const thead = el('thead');
    const hr = el('tr');
    for (const h of ['Subject', 'Ticker', 'Resolved', 'Price', 'Venue', 'Identity', 'Dirty for', 'Uncomposed', 'Evidence', 'News', 'Headlines', 'Last activity']) {
      hr.appendChild(el('th', null, h));
    }
    thead.appendChild(hr);
    t.appendChild(thead);
    const tbody = el('tbody');
    const shown = units.filter(matches);
    for (const u of shown) registerRow(u, flagged.get(u.id), now, tbody);
    t.appendChild(tbody);
    reg.body.appendChild(t);
    if (!shown.length) reg.body.appendChild(empty(units.length ? 'no subject matches the filter' : 'the register is empty'));
    root.appendChild(reg.card);
  }

  return {
    root,
    update(data, ctx) { last = data; lastNow = ctx?.now || Date.now(); render(); },
  };
}
