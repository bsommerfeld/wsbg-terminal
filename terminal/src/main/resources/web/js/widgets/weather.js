// Wetterbericht widget — the daily AI day report in three states:
//
//   WAITING     big countdown to the next report time (1s setInterval text
//               tick, footer.js pattern — no CSS animation, paint rule),
//   GENERATING  blurred ghost lines with the shared shimmer sweep (the
//               `.loading`/eurusd-skeleton exception: exists only for the few
//               minutes the model writes, removed the moment text lands),
//   REPORT      the markdown day report on top, below it the day's frozen
//               stats (markets with sparklines, most-discussed tickers,
//               most-cited news) — scroll down for more; ‹ › browses the
//               archived days.
//
// Also renders the dedicated grid-card tile (#weather-thumb, .grid-thumb):
// newest report's date + prose excerpt + market chips, or the countdown /
// generating state before that — same payload, same three states.
//
// Payload (topic `weather`): { generating, reportTime, nextRunAt, today,
// reports: [ { date, generatedAt, text, headlineCount, importantCount,
// indices[], tickers[], news[] } … newest first ] }.

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { renderMarkdown } from '../format/markdown.js';
import { fmtDuration } from '../format/time.js';
import { isNum, fmtVol } from '../format/numbers.js';

let lastPayload = null;
let viewIndex = 0;         // 0 = newest archived report
let ticker = null;         // the 1 Hz countdown interval, alive only while a countdown is on screen

export function initWeather(socket) {
  const input = document.getElementById('weather-time-input');
  if (!input) return;
  input.addEventListener('change', () => {
    if (input.value) socket.send('weather', { command: 'set-time', value: input.value });
  });
}

export function renderWeather(host, p) {
  if (!host) return;
  lastPayload = p;
  if (!p) {
    paintSkeleton(host);
    return;
  }
  syncTimeInput(p);
  renderThumb(p);
  const reports = Array.isArray(p.reports) ? p.reports : [];
  viewIndex = Math.max(0, Math.min(viewIndex, Math.max(0, reports.length - 1)));

  if (p.generating && viewIndex === 0) {
    paintGenerating(host);
    ensureTicker();
    return;
  }
  if (!reports.length) {
    paintCountdown(host, p);
    ensureTicker();
    return;
  }
  paintReport(host, p, reports, viewIndex);
  ensureTicker();
}

// ---- states ----------------------------------------------------------------

function paintSkeleton(host) {
  if (host.firstElementChild) return;
  stopTicker();
  host.innerHTML = `<div class="weather-wait">
    <div class="weather-wait-label loading">${escapeHtml(t('weather.countdown.label'))}</div>
  </div>`;
}

function paintGenerating(host) {
  stopTicker();
  // Ghost paragraph: bounded, single-purpose shimmer elements (the sanctioned
  // skeleton exception) — gone as soon as the report text arrives.
  const widths = [92, 100, 97, 88, 64, 0, 96, 90, 42];
  const lines = widths.map(w => w === 0
    ? '<div class="weather-ghost-gap"></div>'
    : `<div class="weather-ghost" style="width:${w}%"></div>`).join('');
  host.innerHTML = `<div class="weather-generating">
    <div class="weather-status">${escapeHtml(t('weather.generating'))}</div>
    <div class="weather-ghosts">${lines}</div>
  </div>`;
}

function paintCountdown(host, p) {
  host.innerHTML = `<div class="weather-wait">
    <div class="weather-wait-label">${escapeHtml(t('weather.countdown.label'))}</div>
    <div class="weather-count js-weather-count">--:--</div>
    <div class="weather-wait-time">${escapeHtml(p.reportTime || '')}</div>
    <p class="weather-wait-hint">${escapeHtml(t('weather.countdown.first'))}</p>
  </div>`;
}

function paintReport(host, p, reports, idx) {
  const r = reports[idx];
  const older = idx < reports.length - 1;
  const newer = idx > 0;
  const dateLabel = fmtDate(r.date);
  const genClock = new Date(r.generatedAt * 1000).toLocaleTimeString(
      currentLang() === 'de' ? 'de-DE' : 'en-GB', { hour: '2-digit', minute: '2-digit' });

  host.innerHTML = `
    <div class="weather-nav">
      <button class="weather-nav-btn js-weather-prev" type="button" ${older ? '' : 'disabled'}
              title="${escapeHtml(t('weather.prev'))}" aria-label="${escapeHtml(t('weather.prev'))}">‹</button>
      <div class="weather-nav-title">
        <span class="weather-nav-date">${escapeHtml(t('weather.report.from'))} ${escapeHtml(dateLabel)}</span>
        <span class="weather-nav-meta">${escapeHtml(genClock)} · ${r.headlineCount} ${escapeHtml(t('weather.stats.headlines'))}${r.importantCount > 0 ? ` · <b>${r.importantCount}</b> ${escapeHtml(t('weather.stats.important'))}` : ''}</span>
      </div>
      <button class="weather-nav-btn js-weather-next" type="button" ${newer ? '' : 'disabled'}
              title="${escapeHtml(t('weather.next'))}" aria-label="${escapeHtml(t('weather.next'))}">›</button>
    </div>
    ${forecastStripHtml(w(r).dayparts)}
    <div class="weather-text">${reportWithFigures(r)}</div>
    ${idx === 0 && !p.generating ? `<div class="weather-next-line">${escapeHtml(t('weather.countdown.label'))} <span class="js-weather-count">--:--</span></div>` : ''}
    <div class="weather-sections">
      ${pulseHtml(w(r).pulse)}
      ${indicesHtml(r.indices, r.sentiment, w(r).putCall)}
      ${sectorsHtml(w(r).sectors)}
      ${ratesHtml(w(r).rates)}
      ${tickersHtml(r.tickers, w(r).depth, w(r).shortVolume)}
      ${adhocsHtml(w(r).adhocs)}
      ${analystsHtml(w(r).analystActions)}
      ${macroHtml(w(r).macroActuals, w(r).macroEvents)}
      ${moversHtml(w(r).movers)}
      ${socialHtml(w(r).social)}
      ${cryptoHtml(w(r).crypto)}
      ${betsHtml(w(r).bets)}
      ${watchlistHtml(w(r).watchlist, w(r).deepDives)}
      ${newsHtml(r.news)}
      ${overnightHtml(w(r).overnight)}
      ${outlookHtml(w(r).outlook)}
      ${colourHtml(w(r))}
    </div>`;

  const prev = host.querySelector('.js-weather-prev');
  const next = host.querySelector('.js-weather-next');
  if (prev) prev.addEventListener('click', () => { viewIndex++; renderWeather(host, lastPayload); });
  if (next) next.addEventListener('click', () => { viewIndex--; renderWeather(host, lastPayload); });
}

// ---- sections ---------------------------------------------------------------

// The Abendausgabe's world block; absent on pre-2026-07-13 archive lines.
function w(r) {
  return r.world || {};
}

/* The forecast strip — the report built like an actual weather report:
   morning / midday / evening with a deterministic weather symbol from that
   window's cage mood, plus the docket-based TOMORROW tile (a High-impact
   release is a storm warning, never a prediction). */
const PART_LABELS = { MORNING: 'weather.part.morning', MIDDAY: 'weather.part.midday',
  EVENING: 'weather.part.evening', TOMORROW: 'weather.part.tomorrow' };

function forecastStripHtml(dayparts) {
  if (!Array.isArray(dayparts) || !dayparts.length) return '';
  const labels = PART_LABELS;
  const tiles = dayparts.map(d => {
    const warn = d.key === 'TOMORROW' && d.icon === 'STORM';
    let sub;
    if (d.key === 'TOMORROW') {
      sub = d.note ? escapeHtml(d.note) : escapeHtml(t('weather.part.calm'));
    } else if (d.lines > 0) {
      sub = `${d.lines} ${escapeHtml(t('weather.stats.headlines'))}${d.note ? ` · ${escapeHtml(d.note)}` : ''}`;
    } else {
      sub = escapeHtml(t('weather.part.quiet'));
    }
    return `<div class="weather-cast${warn ? ' warn' : ''}">
      <span class="weather-cast-label">${escapeHtml(t(labels[d.key] || d.key))}</span>
      ${wxIcon(d.icon)}
      <span class="weather-cast-sub">${sub}</span>
      ${d.red > 0 ? `<span class="weather-cast-red">${d.red}!</span>` : ''}
    </div>`;
  }).join('');
  return `<div class="weather-forecast">${tiles}</div>`;
}

// Weather glyphs, house stroke style; drops and bolts read bearish-red.
function wxIcon(token) {
  const cloud = 'M7 17a4 4 0 0 1 0-8 5 5 0 0 1 9.6-1.1A4 4 0 0 1 17 17Z';
  let body;
  switch (token) {
    case 'SUNNY':
      body = `<g class="wx-sun"><circle cx="12" cy="12" r="4.4"/>
        <path d="M12 3.5v2.2M12 18.3v2.2M3.5 12h2.2M18.3 12h2.2M6 6l1.6 1.6M16.4 16.4 18 18M18 6l-1.6 1.6M7.6 16.4 6 18"/></g>`;
      break;
    case 'PARTLY':
      body = `<g class="wx-sun"><circle cx="8" cy="8" r="3"/>
        <path d="M8 2.8v1.6M2.8 8h1.6M3.9 3.9 5 5M12.1 3.9 11 5"/></g>
        <g class="wx-cloud"><path d="M9 19a3.6 3.6 0 0 1 0-7.2 4.5 4.5 0 0 1 8.6-1A3.6 3.6 0 0 1 18 19Z"/></g>`;
      break;
    case 'RAIN':
      body = `<g class="wx-cloud"><path d="${cloud}"/></g>
        <g class="wx-wet"><path d="M9 19.5l-.8 2M13 19.5l-.8 2M17 19.5l-.8 2"/></g>`;
      break;
    case 'STORM':
      body = `<g class="wx-cloud"><path d="${cloud}"/></g>
        <g class="wx-wet"><path d="M12.5 18.5 10.5 21h3l-2 3"/></g>`;
      break;
    case 'FOG':
      body = `<g class="wx-cloud"><path d="M7 15a4 4 0 0 1 0-8 5 5 0 0 1 9.6-1.1A4 4 0 0 1 17 15Z"/>
        <path d="M5.5 18h11M7.5 21h9"/></g>`;
      break;
    default: // CLOUDY
      body = `<g class="wx-cloud"><path d="${cloud}"/></g>`;
  }
  return `<svg class="weather-wx" viewBox="0 0 24 24" aria-hidden="true">${body}</svg>`;
}

/* White-paper layout (the KI-DD pattern): the report's ## sections rendered
   one by one, each followed by its section-anchored figures — server-rendered
   SVG from our own Java builder (trusted markup; captions escaped). */
function reportWithFigures(r) {
  const charts = Array.isArray(r.charts) ? r.charts : [];
  const md = r.text || '';
  if (!charts.length) return renderMarkdown(md);

  const chunks = [];
  let current = { body: [] };
  chunks.push(current);
  for (const line of md.split('\n')) {
    if (line.startsWith('## ')) {
      current = { body: [line] };
      chunks.push(current);
    } else {
      current.body.push(line);
    }
  }
  const parts = [];
  for (let c = 0; c < chunks.length; c++) {
    const text = chunks[c].body.join('\n').trim();
    if (text) parts.push(renderMarkdown(text));
    const sectionIdx = c - 1;
    if (sectionIdx >= 0) {
      for (const fig of charts.filter(f => f.section === sectionIdx)) {
        parts.push(weatherFigureHtml(fig));
      }
    }
  }
  const maxSection = chunks.length - 2;
  for (const fig of charts.filter(f => f.section > maxSection)) {
    parts.push(weatherFigureHtml(fig));
  }
  return parts.join('');
}

function weatherFigureHtml(fig) {
  return `<figure class="weather-figure">
    <figcaption>
      <span class="weather-figure-title">${escapeHtml(fig.title || '')}</span>
      <span class="weather-figure-rule"></span>
      ${fig.note ? `<span class="weather-figure-note">${escapeHtml(fig.note)}</span>` : ''}
    </figcaption>
    ${fig.svg || ''}
  </figure>`;
}

// The cage's own aggregate line — the restored Käfig-Puls.
function pulseHtml(pulse) {
  if (!pulse) return '';
  const parts = [
    `<span class="weather-pulse-part up">${pulse.bullish} ${escapeHtml(t('weather.pulse.bullish'))}</span>`,
    `<span class="weather-pulse-part down">${pulse.bearish} ${escapeHtml(t('weather.pulse.bearish'))}</span>`,
    `<span class="weather-pulse-part">${pulse.neutral} ${escapeHtml(t('weather.pulse.neutral'))}</span>`,
  ];
  if (pulse.redCount > 0) {
    parts.push(`<span class="weather-pulse-part down"><b>${pulse.redCount}</b> ${escapeHtml(t('weather.stats.important'))}</span>`);
  }
  parts.push(`<span class="weather-pulse-part">${pulse.distinctSubjects} ${escapeHtml(t('weather.pulse.subjects'))}</span>`);
  if (isNum(pulse.busiestHour)) {
    parts.push(`<span class="weather-pulse-part">${escapeHtml(t('weather.pulse.hour'))} ${pulse.busiestHour}:00</span>`);
  }
  return section(t('weather.stats.pulse'), `<div class="weather-pulse">${parts.join('<span class="weather-dot">·</span>')}</div>`);
}

function indicesHtml(indices, sentiment, putCall) {
  const hasIndices = Array.isArray(indices) && indices.length > 0;
  if (!hasIndices && !sentiment && !putCall) return '';
  const body = `${sentimentHtml(sentiment, putCall)}${hasIndices ? indexGridHtml(indices) : ''}`;
  return section(t('weather.stats.indices'), body);
}

// The day's frozen market mood (CNN Fear & Greed) above the tiles — since the
// Abendausgabe joined by the crypto gauge and CBOE's equity put/call ratio.
function sentimentHtml(s, putCall) {
  const bits = [];
  if (s && isNum(s.score)) {
    const cls = bandCls(s.band);
    const prev = isNum(s.previousClose)
      ? ` <span class="weather-fg-prev">(${escapeHtml(t('weather.sentiment.prev'))} ${s.previousClose})</span>` : '';
    bits.push(`<span class="weather-fg-label">${escapeHtml(t('weather.sentiment.label'))}</span>
      <span class="weather-fg-score ${cls}">${s.score}</span>
      <span class="weather-fg-band ${cls}">${escapeHtml(t('fg.' + (s.band || 'NEUTRAL')))}</span>${prev}`);
  }
  if (s && isNum(s.cryptoScore)) {
    const cls = bandCls(s.cryptoBand);
    bits.push(`<span class="weather-fg-label">${escapeHtml(t('weather.sentiment.crypto'))}</span>
      <span class="weather-fg-score ${cls}">${s.cryptoScore}</span>
      <span class="weather-fg-band ${cls}">${escapeHtml(t('fg.' + (s.cryptoBand || 'NEUTRAL')))}</span>`);
  }
  if (putCall && isNum(putCall.equity)) {
    bits.push(`<span class="weather-fg-label" title="${escapeHtml(t('weather.putcall.title'))}">${escapeHtml(t('weather.putcall.label'))}</span>
      <span class="weather-fg-score flat">${fmtNum(putCall.equity, 2)}</span>`);
  }
  if (!bits.length) return '';
  return `<div class="weather-fg-line">${bits.join('<span class="weather-dot">·</span>')}</div>`;
}

function bandCls(band) {
  const b = band || '';
  return b.includes('FEAR') ? 'down' : b.includes('GREED') ? 'up' : 'flat';
}

function indexGridHtml(indices) {
  const cards = indices.map(ix => {
    const dir = dirOf(ix.changePercent);
    return `<div class="weather-idx">
      <div class="weather-idx-head">
        <span class="weather-idx-name">${escapeHtml(ix.name || ix.symbol || '')}</span>
        ${pct(ix.changePercent)}
      </div>
      ${sparkHtml(ix.spark, dir)}
      <div class="weather-idx-meta">
        ${isNum(ix.last) ? `<span>${fmtMarket(ix.last, ix.currency)}</span>` : ''}
        ${isNum(ix.volume) && ix.volume > 0 ? `<span>${escapeHtml(t('weather.volume'))} ${fmtVol(ix.volume)}</span>` : ''}
      </div>
    </div>`;
  }).join('');
  return `<div class="weather-idx-grid">${cards}</div>`;
}

function tickersHtml(tickers, depth, shortVolume) {
  if (!tickers || !tickers.length) return '';
  const depthBy = {};
  (depth || []).forEach(d => { depthBy[d.ticker] = d; });
  const shortBy = {};
  (shortVolume || []).forEach(s => { shortBy[s.symbol] = s; });
  const rows = tickers.map(tk => `<div class="weather-tick-block">
    <div class="weather-tick">
      <span class="weather-tick-count">${tk.headlineCount}×</span>
      <span class="weather-tick-name">${escapeHtml(tk.name || tk.ticker || '')}
        <span class="weather-tick-sym">${escapeHtml(tk.ticker || '')}</span>
        ${tk.importantCount > 0 ? `<span class="weather-tick-red" title="${escapeHtml(t('weather.stats.important'))}">${tk.importantCount}!</span>` : ''}
      </span>
      <span class="weather-tick-quote">
        ${tradedHtml(tk)}
        ${isNum(tk.price) ? `<span class="weather-tick-price">${fmtQuote(tk.price, tk.currency)}</span>` : ''}
        ${pct(tk.changePercent)}
      </span>
    </div>
    ${depthLineHtml(depthBy[tk.ticker], shortBy[tk.ticker])}
  </div>`).join('');
  return section(t('weather.stats.tickers'), `<div class="weather-tick-list">${rows}</div>`);
}

// Street depth under a ticker row: consensus target, rating split, next
// event, disclosed shorts, insider note, FINRA short-volume — whatever the
// freeze actually carries.
function depthLineHtml(d, sv) {
  const parts = [];
  if (d) {
    if (isNum(d.targetPrice)) {
      let target = `${escapeHtml(t('weather.depth.target'))} ${fmtQuote(d.targetPrice, d.targetCurrency)}`;
      if (isNum(d.upsidePercent)) target += ` (${pct(d.upsidePercent)})`;
      parts.push(target);
    }
    if (isNum(d.buy)) {
      parts.push(`${d.buy}/${isNum(d.hold) ? d.hold : 0}/${isNum(d.sell) ? d.sell : 0} ${escapeHtml(t('weather.depth.ratings'))}`);
    }
    if (d.nextEventTitle) {
      parts.push(`${escapeHtml(d.nextEventTitle)}${d.nextEventDate ? ' ' + escapeHtml(fmtDate(d.nextEventDate)) : ''}`);
    }
    if (isNum(d.shortPercent)) {
      let shorts = `${escapeHtml(t('weather.depth.shorts'))} ${fmtNum(d.shortPercent, 2)} %`;
      if (d.topShortHolder) shorts += ` (${escapeHtml(d.topShortHolder)})`;
      parts.push(shorts);
    }
    if (d.insiderNote) parts.push(`${escapeHtml(t('weather.depth.insider'))} ${escapeHtml(d.insiderNote)}`);
  }
  if (sv && isNum(sv.shortPercent)) {
    parts.push(`<span title="${escapeHtml(t('weather.shortvol.title'))}">${escapeHtml(t('weather.shortvol.label'))} ${fmtNum(sv.shortPercent, 0)} %</span>`);
  }
  if (!parts.length) return '';
  return `<div class="weather-tick-depth">${parts.join('<span class="weather-dot">·</span>')}</div>`;
}

// US sector rotation: name + direction-scaled bar + day move.
function sectorsHtml(sectors) {
  if (!sectors || !sectors.length) return '';
  const withChg = sectors.filter(s => isNum(s.changePercent));
  if (!withChg.length) return '';
  const sorted = [...withChg].sort((a, b) => b.changePercent - a.changePercent);
  const max = Math.max(...sorted.map(s => Math.abs(s.changePercent)), 0.01);
  const rows = sorted.map(s => {
    const dir = dirOf(s.changePercent);
    const width = Math.max(3, Math.abs(s.changePercent) / max * 100);
    return `<div class="weather-sector">
      <span class="weather-sector-name">${escapeHtml(s.name || s.symbol || '')}</span>
      <span class="weather-sector-track"><span class="weather-sector-bar ${dir}" style="width:${width.toFixed(0)}%"></span></span>
      ${pct(s.changePercent)}
    </div>`;
  }).join('');
  return section(t('weather.stats.sectors'), `<div class="weather-sector-list">${rows}</div>`);
}

// The two yield lines every professional briefing carries.
function ratesHtml(rates) {
  if (!rates || !rates.length) return '';
  const rows = rates.filter(r => isNum(r.percent)).map(r => `<div class="weather-plain">
      <span class="weather-plain-main">${escapeHtml(r.name || '')}</span>
      <span class="weather-plain-trail">
        <span class="weather-tick-price">${fmtNum(r.percent, 2)} %</span>
        ${isNum(r.previousPercent) ? `<span class="weather-plain-mute">${escapeHtml(t('weather.sentiment.prev'))} ${fmtNum(r.previousPercent, 2)} %</span>` : ''}
        ${r.dateIso ? `<span class="weather-plain-mute">${escapeHtml(fmtDate(r.dateIso))}</span>` : ''}
      </span>
    </div>`).join('');
  return rows ? section(t('weather.stats.rates'), `<div class="weather-plain-list">${rows}</div>`) : '';
}

// The day's EQS ad-hoc disclosures; one on a cage paper carries a gold chip.
function adhocsHtml(adhocs) {
  if (!adhocs || !adhocs.length) return '';
  const rows = adhocs.map(a => `<div class="weather-plain">
      ${a.time ? `<span class="weather-plain-time">${escapeHtml(a.time)}</span>` : ''}
      <span class="weather-plain-main wrap">${escapeHtml(a.title || '')}
        ${a.kaefigTicker ? `<span class="weather-chip-cage">${escapeHtml(t('weather.adhoc.inkaefig'))} ${escapeHtml(a.kaefigTicker)}</span>` : ''}
      </span>
    </div>`).join('');
  return section(t('weather.stats.adhocs'), `<div class="weather-plain-list">${rows}</div>`);
}

function analystsHtml(actions) {
  if (!actions || !actions.length) return '';
  const rows = actions.map(a => `<div class="weather-plain">
      ${a.time ? `<span class="weather-plain-time">${escapeHtml(a.time)}</span>` : ''}
      <span class="weather-plain-main wrap">${escapeHtml(a.title || '')}</span>
    </div>`).join('');
  return section(t('weather.stats.analysts'), `<div class="weather-plain-list">${rows}</div>`);
}

// Macro: released figures (the number sits in the title) + today's docket.
function macroHtml(actuals, events) {
  const a = actuals || [], e = events || [];
  if (!a.length && !e.length) return '';
  const actualRows = a.map(m => `<div class="weather-plain">
      <span class="weather-plain-time">${escapeHtml(m.source || '')}</span>
      <span class="weather-plain-main wrap">${escapeHtml(m.title || '')}</span>
    </div>`).join('');
  const eventRows = e.map(m => {
    const extras = [];
    if (m.forecast) extras.push(`${escapeHtml(t('weather.macro.forecast'))} ${escapeHtml(m.forecast)}`);
    if (m.previous) extras.push(`${escapeHtml(t('weather.macro.previous'))} ${escapeHtml(m.previous)}`);
    return `<div class="weather-plain">
      <span class="weather-plain-time">${escapeHtml(m.time || '')} ${escapeHtml(m.source || '')}</span>
      <span class="weather-plain-main wrap">${escapeHtml(m.title || '')}
        ${m.impact ? `<span class="weather-chip-impact ${m.impact === 'High' ? 'high' : ''}">${escapeHtml(m.impact)}</span>` : ''}
        ${extras.length ? `<span class="weather-plain-mute">${extras.join(' · ')}</span>` : ''}
      </span>
    </div>`;
  }).join('');
  return section(t('weather.stats.macro'),
      `<div class="weather-plain-list">${actualRows}${eventRows}</div>`);
}

// US movers, one wrapped chip line per kind; cage overlap marked gold.
function moversHtml(movers) {
  if (!movers || !movers.length) return '';
  const kinds = [['GAINER', 'weather.movers.gainers'], ['LOSER', 'weather.movers.losers'],
    ['ACTIVE', 'weather.movers.active']];
  const lines = kinds.map(([kind, key]) => {
    const of = movers.filter(m => m.kind === kind);
    if (!of.length) return '';
    const chips = of.map(m => `<span class="weather-mover">
        <span class="weather-mover-name">${escapeHtml(m.name || m.symbol || '')}</span>
        ${pct(m.changePercent)}
        ${m.inKaefig ? `<span class="weather-chip-cage">${escapeHtml(t('weather.movers.inkaefig'))}</span>` : ''}
      </span>`).join('');
    return `<div class="weather-mover-line">
      <span class="weather-plain-time">${escapeHtml(t(key))}</span>
      <span class="weather-mover-chips">${chips}</span>
    </div>`;
  }).join('');
  return lines ? section(t('weather.stats.movers'), lines) : '';
}

// The neighbour boards' pulse; big rank climbs are the signal.
function socialHtml(social) {
  if (!social || !social.length) return '';
  const rows = social.map(s => `<div class="weather-plain">
      <span class="weather-plain-time">#${s.rank}</span>
      <span class="weather-plain-main">${escapeHtml(s.name || s.ticker || '')}
        <span class="weather-tick-sym">${escapeHtml(s.ticker || '')}</span>
      </span>
      <span class="weather-plain-trail">
        <span class="weather-plain-mute">${s.mentions} ${escapeHtml(t('weather.social.mentions'))}</span>
        ${isNum(s.rankClimb) && s.rankClimb > 0 ? `<span class="weather-pct up">▲${s.rankClimb} ${escapeHtml(t('weather.social.ranks'))}</span>` : ''}
      </span>
    </div>`).join('');
  return section(t('weather.stats.social'), `<div class="weather-plain-list">${rows}</div>`);
}

// Crypto: the market line plus derivatives temperature plus trending chips.
function cryptoHtml(c) {
  if (!c) return '';
  const bits = [];
  if (isNum(c.marketCapUsd)) {
    let mcap = `${escapeHtml(t('weather.crypto.mcap'))} ${fmtBigUsd(c.marketCapUsd)}`;
    if (isNum(c.mcapChangePercent)) mcap += ` (${pct(c.mcapChangePercent)})`;
    bits.push(mcap);
  }
  if (isNum(c.btcDominance)) bits.push(`${escapeHtml(t('weather.crypto.dominance'))} ${fmtNum(c.btcDominance, 1)} %`);
  if (isNum(c.fearGreedScore)) {
    bits.push(`${escapeHtml(t('weather.crypto.fg'))} <span class="weather-fg-score ${bandCls(c.fearGreedBand)}">${c.fearGreedScore}</span>`);
  }
  if (isNum(c.fundingRatePercent)) bits.push(`${escapeHtml(t('weather.crypto.funding'))} ${fmtNum(c.fundingRatePercent, 4)} %`);
  if (isNum(c.dvol)) bits.push(`${escapeHtml(t('weather.crypto.dvol'))} ${fmtNum(c.dvol, 0)}`);
  const line = bits.length ? `<div class="weather-fg-line">${bits.join('<span class="weather-dot">·</span>')}</div>` : '';
  const trending = (c.trending || []).length
    ? `<div class="weather-mover-line">
        <span class="weather-plain-time">${escapeHtml(t('weather.crypto.trending'))}</span>
        <span class="weather-mover-chips">${c.trending.map(tc => `<span class="weather-mover">
          <span class="weather-mover-name">${escapeHtml(tc.name || tc.symbol || '')}</span>
          ${pct(tc.changePercent)}
        </span>`).join('')}</span>
      </div>` : '';
  if (!line && !trending) return '';
  return section(t('weather.stats.crypto'), line + trending);
}

// Prediction markets: real-money odds on the questions of the day.
function betsHtml(bets) {
  if (!bets || !bets.length) return '';
  const rows = bets.map(b => `<div class="weather-plain">
      <span class="weather-plain-main wrap">${escapeHtml(b.question || '')}</span>
      <span class="weather-plain-trail">
        ${isNum(b.probabilityPercent) ? `<span class="weather-tick-price">${escapeHtml(b.outcome || '')} ${fmtNum(b.probabilityPercent, 0)} %</span>` : ''}
        ${isNum(b.volume24hUsd) ? `<span class="weather-plain-mute">${fmtBigUsd(b.volume24hUsd)}</span>` : ''}
      </span>
    </div>`).join('');
  return section(t('weather.stats.bets'), `<div class="weather-plain-list">${rows}</div>`);
}

// The house desk: watchlist day moves + the deep dives written today.
function watchlistHtml(watchlist, deepDives) {
  const wl = watchlist || [], dd = deepDives || [];
  if (!wl.length && !dd.length) return '';
  const rows = wl.map(e => `<div class="weather-plain">
      <span class="weather-plain-main">${escapeHtml(e.name || e.ticker || '')}
        ${e.ticker ? `<span class="weather-tick-sym">${escapeHtml(e.ticker)}</span>` : ''}
      </span>
      <span class="weather-plain-trail">
        ${isNum(e.price) ? `<span class="weather-tick-price">${fmtQuote(e.price, e.currency)}</span>` : ''}
        ${pct(e.changePercent)}
      </span>
    </div>`).join('');
  const ddLine = dd.length
    ? `<div class="weather-plain"><span class="weather-plain-main wrap">${escapeHtml(t('weather.watchlist.dd'))} ${escapeHtml(dd.join(', '))}</span></div>` : '';
  return section(t('weather.stats.watchlist'), `<div class="weather-plain-list">${rows}${ddLine}</div>`);
}

// Futures + Asia — the night after the report.
function overnightHtml(overnight) {
  if (!overnight || !overnight.length) return '';
  return section(t('weather.stats.overnight'), indexGridHtml(overnight));
}

// Tomorrow's docket — the reason to read the edition in the evening.
function outlookHtml(outlook) {
  if (!outlook || !outlook.length) return '';
  const rows = outlook.map(o => {
    if (o.kind === 'EARNINGS') {
      return `<div class="weather-plain">
        <span class="weather-plain-time">${escapeHtml(t('weather.outlook.earnings'))}</span>
        <span class="weather-plain-main wrap">${escapeHtml(o.title || '')}
          ${o.detail ? `<span class="weather-plain-mute">${escapeHtml(o.detail)}</span>` : ''}
          ${o.time ? `<span class="weather-plain-mute">${escapeHtml(o.time)}</span>` : ''}
        </span>
      </div>`;
    }
    return `<div class="weather-plain">
      <span class="weather-plain-time">${escapeHtml(o.time || '')} ${escapeHtml(o.detail || '')}</span>
      <span class="weather-plain-main wrap">${escapeHtml(o.title || '')}
        ${o.impact ? `<span class="weather-chip-impact ${o.impact === 'High' ? 'high' : ''}">${escapeHtml(o.impact)}</span>` : ''}
      </span>
    </div>`;
  }).join('');
  return section(t('weather.stats.outlook'), `<div class="weather-plain-list">${rows}</div>`);
}

// The colour footer: the river, the debt clock, the literal exchange
// weather, and the moon — because zum Mond.
function colourHtml(world) {
  if (!world) return '';
  const rows = [];
  if (world.pegel && isNum(world.pegel.centimeters)) {
    const low = world.pegel.state === 'low';
    rows.push(`<div class="weather-plain">
      <span class="weather-plain-main wrap">${escapeHtml(t('weather.colour.pegel'))}
        <span class="weather-tick-price">${fmtNum(world.pegel.centimeters, 0)} cm</span>
        ${low ? `<span class="weather-chip-impact high">${escapeHtml(t('weather.colour.pegel.low'))}</span>` : ''}
      </span>
    </div>`);
  }
  if (isNum(world.usDebtUsd)) {
    rows.push(`<div class="weather-plain">
      <span class="weather-plain-main">${escapeHtml(t('weather.colour.debt'))}
        <span class="weather-tick-price">${fmtBigUsd(world.usDebtUsd)}</span>
      </span>
    </div>`);
  }
  if (world.exchangeWeather && isNum(world.exchangeWeather.temperatureCelsius)) {
    rows.push(`<div class="weather-plain">
      <span class="weather-plain-main">${escapeHtml(t('weather.colour.weather'))}
        <span class="weather-tick-price">${fmtNum(world.exchangeWeather.temperatureCelsius, 1)} °C</span>
        <span class="weather-plain-mute">${escapeHtml(wxWord(world.exchangeWeather.icon))}</span>
      </span>
    </div>`);
  }
  if (world.moon && isNum(world.moon.daysToFull)) {
    const moonText = world.moon.daysToFull === 0
      ? t('weather.colour.moon.full')
      : `${t('weather.colour.moon.in')} ${world.moon.daysToFull} ${t('weather.colour.moon.days')}`;
    rows.push(`<div class="weather-plain">
      <span class="weather-plain-main">${escapeHtml(t('weather.colour.moon'))}
        <span class="weather-tick-price">${world.moon.illuminationPercent} %</span>
        <span class="weather-plain-mute">${escapeHtml(moonText)}</span>
      </span>
    </div>`);
  }
  if (!rows.length) return '';
  return section(t('weather.stats.colour'), `<div class="weather-plain-list">${rows.join('')}</div>`);
}

// BrightSky icon token → a short localized word.
function wxWord(icon) {
  const i = icon || '';
  if (i.startsWith('clear')) return t('weather.wx.clear');
  if (i.startsWith('partly')) return t('weather.wx.partly');
  if (i === 'cloudy') return t('weather.wx.cloudy');
  if (i === 'fog') return t('weather.wx.fog');
  if (i === 'wind') return t('weather.wx.wind');
  if (i === 'rain' || i === 'sleet' || i === 'hail') return t('weather.wx.rain');
  if (i === 'snow') return t('weather.wx.snow');
  if (i === 'thunderstorm') return t('weather.wx.storm');
  return '';
}

// "What actually traded" for a ticker row: day volume in shares and, where
// Tradegate answered at freeze time, the EUR turnover ("Vol. 1,2M · 3,4M €").
function tradedHtml(tk) {
  const parts = [];
  if (isNum(tk.volume) && tk.volume > 0) parts.push(`${escapeHtml(t('weather.volume'))} ${fmtVol(tk.volume)}`);
  if (isNum(tk.turnoverEur) && tk.turnoverEur > 0) parts.push(`${fmtVol(tk.turnoverEur)} €`);
  if (!parts.length) return '';
  return `<span class="weather-tick-vol" title="${escapeHtml(t('weather.traded.title'))}">${parts.join(' · ')}</span>`;
}

function newsHtml(news) {
  if (!news || !news.length) return '';
  const rows = news.map(n => `<div class="weather-news">
      <span class="weather-news-count">${n.citations}×</span>
      <span class="weather-news-title">${escapeHtml(n.title || '')}
        ${n.source ? `<span class="weather-news-src">${escapeHtml(n.source)}</span>` : ''}
      </span>
    </div>`).join('');
  return section(t('weather.stats.news'), `<div class="weather-news-list">${rows}</div>`);
}

function section(title, body) {
  return `<section class="weather-section">
    <h3 class="weather-section-title">${escapeHtml(title)}</h3>
    ${body}
  </section>`;
}

// House sparkline (quote-strip construction): area polygon + polyline + end
// dot, direction-tinted. Single series — no legend, no axes; the change pill
// beside it carries the value in text ink.
function sparkHtml(spark, dir) {
  if (!Array.isArray(spark) || spark.length < 2) return '<div class="weather-spark-empty"></div>';
  const W = 150, H = 36, pad = 3;
  let min = Infinity, max = -Infinity;
  for (const v of spark) { if (v < min) min = v; if (v > max) max = v; }
  const range = max - min || 1;
  const px = i => pad + (i / (spark.length - 1)) * (W - 2 * pad);
  const py = v => H - pad - ((v - min) / range) * (H - 2 * pad);
  const pts = spark.map((v, i) => `${px(i).toFixed(1)},${py(v).toFixed(1)}`).join(' ');
  const area = `${pad},${H - pad} ${pts} ${(W - pad).toFixed(1)},${H - pad}`;
  const lastX = px(spark.length - 1).toFixed(1);
  const lastY = py(spark[spark.length - 1]).toFixed(1);
  return `<svg class="weather-spark ${dir}" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" aria-hidden="true">
    <polygon class="area" points="${area}"/>
    <polyline class="line" points="${pts}"/>
    <circle class="dot" cx="${lastX}" cy="${lastY}" r="2"/>
  </svg>`;
}

// ---- countdown ticker --------------------------------------------------------

// One 1 Hz interval feeds EVERY countdown surface of the widget (focus view
// AND the grid-card thumb — they can be on screen in the same paint), alive
// only while at least one is in the DOM.
function ensureTicker() {
  if (!document.querySelector('#widget-weather .js-weather-count')) {
    stopTicker();
    return;
  }
  if (ticker) return;
  const tick = () => {
    if (!lastPayload) return;
    const remain = (lastPayload.nextRunAt || 0) - Date.now();
    const next = remain > 0 ? fmtDuration(remain) : '…';
    document.querySelectorAll('#widget-weather .js-weather-count').forEach(el => {
      if (el.textContent !== next) el.textContent = next;
    });
  };
  tick();
  ticker = setInterval(tick, 1000);
}

function stopTicker() {
  if (ticker) { clearInterval(ticker); ticker = null; }
}

// ---- small formatters ---------------------------------------------------------

function dirOf(chg) {
  if (!isNum(chg) || Math.abs(chg) < 0.005) return 'flat';
  return chg > 0 ? 'up' : 'down';
}

function pct(chg) {
  if (!isNum(chg)) return '';
  const dir = dirOf(chg);
  const sign = chg > 0 ? '+' : '';
  return `<span class="weather-pct ${dir}">${sign}${chg.toLocaleString(numLocale(), { minimumFractionDigits: 2, maximumFractionDigits: 2 })} %</span>`;
}

function fmtPoints(v) {
  return `${v.toLocaleString(numLocale(), { maximumFractionDigits: v >= 1000 ? 0 : 2 })} ${t('weather.points')}`;
}

function fmtNum(v, decimals) {
  return v.toLocaleString(numLocale(), { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

// Compact USD magnitudes for the world sections: 2.27e12 → "2,3 Bio $".
function fmtBigUsd(v) {
  const abs = Math.abs(v);
  if (abs >= 1e12) return `${fmtNum(v / 1e12, 2)} ${t('weather.big.tril')} $`;
  if (abs >= 1e9) return `${fmtNum(v / 1e9, 1)} ${t('weather.big.bil')} $`;
  if (abs >= 1e6) return `${fmtNum(v / 1e6, 1)} ${t('weather.big.mil')} $`;
  return `$${fmtNum(v, 0)}`;
}

// Market-tile level: "PTS"/absent = index points (the pre-currency archive
// lines), "FX" = an exchange rate (4 decimals, unitless), else a currency.
function fmtMarket(v, ccy) {
  if (!ccy || ccy === 'PTS') return fmtPoints(v);
  if (ccy === 'FX') return v.toLocaleString(numLocale(), { minimumFractionDigits: 4, maximumFractionDigits: 4 });
  return fmtQuote(v, ccy);
}

function fmtQuote(price, ccy) {
  const p = price.toLocaleString(numLocale(), { minimumFractionDigits: 2, maximumFractionDigits: price < 1 ? 4 : 2 });
  if (ccy === 'EUR') return `${p} €`;
  if (ccy === 'USD') return `$${p}`;
  if (ccy === 'PTS') return `${p} ${t('weather.points')}`;
  return ccy ? `${p} ${escapeHtml(ccy)}` : p;
}

function fmtDate(iso) {
  try {
    return new Intl.DateTimeFormat(currentLang() === 'de' ? 'de-DE' : 'en-GB',
        { day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(`${iso}T12:00:00`));
  } catch {
    return iso;
  }
}

function numLocale() {
  return currentLang() === 'de' ? 'de-DE' : 'en-GB';
}

function syncTimeInput(p) {
  const input = document.getElementById('weather-time-input');
  if (input && p.reportTime && document.activeElement !== input && input.value !== p.reportTime) {
    input.value = p.reportTime;
  }
  // One report per day is a hard rule (the archive is idempotent per date):
  // once today's report exists, a new time only takes effect tomorrow — say so
  // right where the time is entered.
  const note = document.getElementById('weather-time-note');
  if (note) {
    const reports = Array.isArray(p.reports) ? p.reports : [];
    note.hidden = !(reports.length && reports[0].date === p.today);
  }
}

// ---- the dedicated grid-card tile (.grid-thumb, shown instead of the
// miniature view in the overview): a real forecast tile — the newest
// report's date as kicker, the daypart forecast strip LARGE (label + weather
// symbol per window), a short excerpt of the opening prose and the day's
// market moves as chips. Before the first report of the day's cycle the big
// countdown takes the tile. Laid out at natural pane size; widget-grid.css
// zooms it with the card, so the type is LARGE. ----

const THUMB_MARKETS = 4;

function renderThumb(p) {
  const thumb = document.getElementById('weather-thumb');
  if (!thumb) return;
  const reports = Array.isArray(p.reports) ? p.reports : [];

  if (p.generating) {
    const widths = [96, 100, 92, 62];
    thumb.innerHTML = `<div class="weather-thumb-wait">
      <div class="weather-thumb-label">${escapeHtml(t('weather.generating'))}</div>
      <div class="weather-ghosts weather-thumb-ghosts">${widths.map(w =>
        `<div class="weather-ghost" style="width:${w}%"></div>`).join('')}</div>
    </div>`;
    return;
  }
  if (!reports.length) {
    thumb.innerHTML = `<div class="weather-thumb-wait">
      <div class="weather-thumb-label">${escapeHtml(t('weather.countdown.label'))}</div>
      <div class="weather-thumb-count js-weather-count">--:--</div>
      <div class="weather-thumb-time">${escapeHtml(p.reportTime || '')}</div>
    </div>`;
    return;
  }
  const r = reports[0];
  const dayparts = Array.isArray(w(r).dayparts) ? w(r).dayparts : [];
  const cast = dayparts.length
    ? `<div class="weather-thumb-forecast">${dayparts.map(d => {
        const warn = d.key === 'TOMORROW' && d.icon === 'STORM';
        return `<div class="weather-thumb-cast${warn ? ' warn' : ''}">
          ${wxIcon(d.icon)}
          <span class="weather-thumb-cast-label">${escapeHtml(t(PART_LABELS[d.key] || d.key))}</span>
          ${d.red > 0 ? `<span class="weather-thumb-cast-red">${d.red}!</span>` : ''}
        </div>`;
      }).join('')}</div>`
    : '';
  const chips = (r.indices || []).slice(0, THUMB_MARKETS).map(ix => `
    <span class="weather-thumb-chip">
      <span class="weather-thumb-chip-name">${escapeHtml(ix.name || ix.symbol || '')}</span>
      ${pct(ix.changePercent)}
    </span>`).join('');
  // With the forecast strip the prose is a teaser; without one (pre-strip
  // archive lines) it carries the tile and may run longer.
  const excerpt = plainExcerpt(r.text, cast ? 200 : 320);
  thumb.innerHTML = `<div class="weather-thumb-report${cast ? ' has-cast' : ''}">
    <div class="weather-thumb-date">${escapeHtml(t('weather.report.from'))} ${escapeHtml(fmtDate(r.date))}</div>
    ${cast}
    <p class="weather-thumb-text">${escapeHtml(excerpt)}</p>
    ${chips ? `<div class="weather-thumb-markets">${chips}</div>` : ''}
  </div>`;
}

// The report's opening prose without its markdown dress: heading lines drop,
// emphasis markers strip — a card-sized plain excerpt, cut at a word boundary.
function plainExcerpt(md, max = 320) {
  const text = (md || '')
      .split('\n')
      .filter(line => !/^\s*#/.test(line))
      .join(' ')
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/\*([^*]+)\*/g, '$1')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/\s+/g, ' ')
      .trim();
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  return `${cut.slice(0, Math.max(cut.lastIndexOf(' '), max - 40))}…`;
}

// Self-init: paint the waiting skeleton as soon as the DOM is ready.
function paintWeatherSkeleton() {
  const host = document.getElementById('weather-detail');
  if (host && !host.firstElementChild) renderWeather(host, null);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', paintWeatherSkeleton);
} else {
  paintWeatherSkeleton();
}
