// Wetterbericht widget — the daily AI day report in three states:
//
//   WAITING     big countdown to the next report time (1s setInterval text
//               tick, footer.js pattern — no CSS animation, paint rule),
//   GENERATING  blurred ghost lines with the shared shimmer sweep (the
//               `.loading`/eurusd-skeleton exception: exists only for the few
//               minutes the model writes, removed the moment text lands),
//   REPORT      the white-paper text on top, below it the day's frozen stats
//               (indices with sparklines, most-discussed tickers, most-cited
//               news) — scroll down for more; ‹ › browses the archived days.
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
  syncTimeInput(p.reportTime);
  const reports = Array.isArray(p.reports) ? p.reports : [];
  viewIndex = Math.max(0, Math.min(viewIndex, Math.max(0, reports.length - 1)));

  if (p.generating && viewIndex === 0) {
    paintGenerating(host);
    return;
  }
  if (!reports.length) {
    paintCountdown(host, p);
    return;
  }
  paintReport(host, p, reports, viewIndex);
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
  startTicker(host);
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
    <div class="weather-text">${renderMarkdown(r.text || '')}</div>
    ${idx === 0 && !p.generating ? `<div class="weather-next-line">${escapeHtml(t('weather.countdown.label'))} <span class="js-weather-count">--:--</span></div>` : ''}
    <div class="weather-sections">
      ${indicesHtml(r.indices)}
      ${tickersHtml(r.tickers)}
      ${newsHtml(r.news)}
    </div>`;

  const prev = host.querySelector('.js-weather-prev');
  const next = host.querySelector('.js-weather-next');
  if (prev) prev.addEventListener('click', () => { viewIndex++; renderWeather(host, lastPayload); });
  if (next) next.addEventListener('click', () => { viewIndex--; renderWeather(host, lastPayload); });

  if (host.querySelector('.js-weather-count')) startTicker(host); else stopTicker();
}

// ---- sections ---------------------------------------------------------------

function indicesHtml(indices) {
  if (!indices || !indices.length) return '';
  const cards = indices.map(ix => {
    const dir = dirOf(ix.changePercent);
    return `<div class="weather-idx">
      <div class="weather-idx-head">
        <span class="weather-idx-name">${escapeHtml(ix.name || ix.symbol || '')}</span>
        ${pct(ix.changePercent)}
      </div>
      ${sparkHtml(ix.spark, dir)}
      <div class="weather-idx-meta">
        ${isNum(ix.last) ? `<span>${fmtPoints(ix.last)}</span>` : ''}
        ${isNum(ix.volume) && ix.volume > 0 ? `<span>${escapeHtml(t('weather.volume'))} ${fmtVol(ix.volume)}</span>` : ''}
      </div>
    </div>`;
  }).join('');
  return section(t('weather.stats.indices'), `<div class="weather-idx-grid">${cards}</div>`);
}

function tickersHtml(tickers) {
  if (!tickers || !tickers.length) return '';
  const rows = tickers.map(tk => `<div class="weather-tick">
      <span class="weather-tick-count">${tk.headlineCount}×</span>
      <span class="weather-tick-name">${escapeHtml(tk.name || tk.ticker || '')}
        <span class="weather-tick-sym">${escapeHtml(tk.ticker || '')}</span>
        ${tk.importantCount > 0 ? `<span class="weather-tick-red" title="${escapeHtml(t('weather.stats.important'))}">${tk.importantCount}!</span>` : ''}
      </span>
      <span class="weather-tick-quote">
        ${isNum(tk.price) ? `<span class="weather-tick-price">${fmtQuote(tk.price, tk.currency)}</span>` : ''}
        ${pct(tk.changePercent)}
      </span>
    </div>`).join('');
  return section(t('weather.stats.tickers'), `<div class="weather-tick-list">${rows}</div>`);
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

function startTicker(host) {
  stopTicker();
  const tick = () => {
    const el = host.querySelector('.js-weather-count');
    if (!el || !lastPayload) return;
    const remain = (lastPayload.nextRunAt || 0) - Date.now();
    const next = remain > 0 ? fmtDuration(remain) : '…';
    if (el.textContent !== next) el.textContent = next;
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

function syncTimeInput(reportTime) {
  const input = document.getElementById('weather-time-input');
  if (input && reportTime && document.activeElement !== input && input.value !== reportTime) {
    input.value = reportTime;
  }
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
