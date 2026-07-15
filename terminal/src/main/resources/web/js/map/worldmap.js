// The interactive world map of the Abendausgabe (2026-07-15): one SVG built
// client-side from the report's frozen world stats — literal weather over the
// trading places, physical hazards (storms/quakes/aviation), conflict events
// (country-level), maritime chokepoints and the exchange cities. Hover answers
// with the item's line in a shared tooltip, click jumps to the matching stat
// section, layer chips toggle visibility (persisted). Pure SVG+JS, no
// libraries, no network — the basemap ships baked in world-basemap.js.
// Interactivity belongs to the UI, never the archive (the frozen server
// figures stay the PDF/archive truth).
//
// All animations are hover-state transitions — nothing loops (OSR paint rule).

import { t, currentLang } from '../i18n/i18n.js';
import { escapeHtml } from '../format/escape.js';
import { isNum } from '../format/numbers.js';
import { MAP_W, MAP_H, project, LAND_PATH, CHOKEPOINTS, EXCHANGES } from './world-basemap.js';

// Layer catalog: id ↔ chip label key ↔ the stat section a marker click jumps
// to (null = informational only, no jump target).
const LAYERS = [
  { id: 'weather', key: 'weather.map.layer.weather', section: 'weather.stats.worldweather' },
  { id: 'hazards', key: 'weather.map.layer.hazards', section: 'weather.stats.hazards' },
  { id: 'conflicts', key: 'weather.map.layer.conflicts', section: 'weather.stats.conflicts' },
  { id: 'choke', key: 'weather.map.layer.choke', section: 'weather.stats.chokepoints' },
  { id: 'exchanges', key: 'weather.map.layer.exchanges', section: null },
];

// Hidden layers, persisted client-side (ids are stable across languages).
const OFF_STORE = 'wsbg.weather.map-off';

function loadOff() {
  try {
    return new Set(JSON.parse(localStorage.getItem(OFF_STORE) || '[]'));
  } catch {
    return new Set();
  }
}

function saveOff(off) {
  try {
    localStorage.setItem(OFF_STORE, JSON.stringify([...off]));
  } catch { /* private mode etc. — toggles still work for the session */ }
}

/**
 * Renders the whole map block (chips + SVG) as an HTML string, or '' when no
 * data layer carries anything (the exchange dots alone don't earn a map).
 * `world` is the report's world block (worldWeather/hazards/worldSignals).
 */
export function renderWorldMap(world, opts = {}) {
  const w = world || {};
  const sig = w.worldSignals || {};
  const bodies = {
    weather: weatherMarkers(w.worldWeather),
    hazards: hazardMarkers(w.hazards),
    conflicts: conflictMarkers(sig.conflicts),
    choke: chokeMarkers(sig.chokepoints),
  };
  if (!bodies.weather && !bodies.hazards && !bodies.conflicts && !bodies.choke) return '';
  bodies.exchanges = exchangeMarkers();

  const off = loadOff();
  const chips = LAYERS.map(l => `<button type="button"
      class="weather-map-chip${off.has(l.id) ? ' off' : ''}" data-layer="${l.id}"
      ${bodies[l.id] ? '' : 'disabled'} title="${escapeHtml(t('weather.map.toggle'))}">
      <span class="weather-map-dot ${l.id}" aria-hidden="true"></span>${escapeHtml(t(l.key))}
    </button>`).join('');

  // Paint order: area-ish context first (chokepoints under everything that
  // tells a story), the small labeled glyphs last so they stay readable.
  const order = ['choke', 'exchanges', 'conflicts', 'hazards', 'weather'];
  const groups = order.map(id => `<g class="wm-layer" data-layer="${id}"${off.has(id)
      ? ' style="display:none"' : ''}>${bodies[id] || ''}</g>`).join('');

  return `<div class="weather-map">
    <div class="weather-map-chips">
      ${chips}
      <button type="button" class="weather-map-zoom" title="${escapeHtml(t('weather.figure.zoom'))}">⤢</button>
    </div>
    <div class="weather-map-stage">
      <svg viewBox="0 0 ${MAP_W} ${MAP_H}" role="img" aria-label="${escapeHtml(t('weather.map.label'))}">
        <path class="wm-land" d="${LAND_PATH}"/>
        ${groups}
      </svg>
    </div>
  </div>`;
}

/**
 * Wires tooltip, section jumps, layer chips and the zoom button on a freshly
 * painted host. `opts.jumpToSection(key)` and `opts.openLightbox(el)` are the
 * caller's own mechanisms (weather.js owns collapse state and the lightbox).
 */
export function wireWorldMap(host, opts = {}) {
  const root = host.querySelector('.weather-map');
  if (!root) return;
  const svg = root.querySelector('svg');
  if (!svg) return;

  // One shared tooltip, positioned near the cursor, clamped to the map box.
  const tip = document.createElement('div');
  tip.className = 'weather-map-tip';
  tip.hidden = true;
  root.appendChild(tip);
  let tipFor = null;

  const place = e => {
    const r = root.getBoundingClientRect();
    let x = e.clientX - r.left + 14;
    let y = e.clientY - r.top + 14;
    const tw = tip.offsetWidth, th = tip.offsetHeight;
    if (x + tw > r.width - 6) x = e.clientX - r.left - tw - 14;
    if (y + th > r.height - 6) y = e.clientY - r.top - th - 14;
    tip.style.left = `${Math.max(6, x)}px`;
    tip.style.top = `${Math.max(6, y)}px`;
  };

  svg.addEventListener('pointermove', e => {
    const m = e.target.closest('.wm-marker');
    if (!m) {
      tip.hidden = true;
      tipFor = null;
      return;
    }
    if (m !== tipFor) {
      // dataset decodes the attribute once — the stored string is the tip's
      // own HTML, whose data parts were escaped when it was built.
      tip.innerHTML = (m.dataset.tip || '')
        + (m.dataset.sec ? `<div class="wm-tip-hint">${escapeHtml(t('weather.cast.jump'))}</div>` : '');
      tip.hidden = false;
      tipFor = m;
    }
    place(e);
  });
  svg.addEventListener('pointerleave', () => { tip.hidden = true; tipFor = null; });

  svg.addEventListener('click', e => {
    const m = e.target.closest('.wm-marker');
    if (m && m.dataset.sec && opts.jumpToSection) opts.jumpToSection(m.dataset.sec);
  });

  root.querySelectorAll('.weather-map-chip[data-layer]').forEach(chip => {
    chip.addEventListener('click', () => {
      const id = chip.dataset.layer;
      const nowOff = !chip.classList.contains('off');
      chip.classList.toggle('off', nowOff);
      const g = svg.querySelector(`.wm-layer[data-layer="${id}"]`);
      if (g) g.style.display = nowOff ? 'none' : '';
      const off = loadOff();
      if (nowOff) off.add(id); else off.delete(id);
      saveOff(off);
    });
  });

  const zoom = root.querySelector('.weather-map-zoom');
  if (zoom) {
    if (opts.openLightbox) {
      zoom.addEventListener('click', () => opts.openLightbox(root.querySelector('.weather-map-stage')));
    } else {
      zoom.hidden = true;
    }
  }
}

// ---- marker layers -----------------------------------------------------------

/** One positioned marker: local-coordinate glyph + escaped tooltip HTML. */
function marker(lat, lon, cls, body, tipHtml, sectionKey) {
  if (!isNum(lat) || !isNum(lon)) return '';
  const [x, y] = project(lat, lon);
  if (!isFinite(x) || !isFinite(y)) return '';
  return `<g class="wm-marker ${cls}" transform="translate(${x.toFixed(1)} ${y.toFixed(1)})"
      data-tip="${escapeHtml(tipHtml)}"${sectionKey ? ` data-sec="${escapeHtml(sectionKey)}"` : ''}>${body}</g>`;
}

// Literal weather over the trading places: tiny condition glyph + temp label.
function weatherMarkers(places) {
  if (!Array.isArray(places)) return '';
  return places.map(p => {
    const now = [isNum(p.tempC) ? `${fmtNum(p.tempC, 1)} °C` : '', p.word || '']
        .filter(Boolean).join(', ');
    const tm = isNum(p.tomorrowMaxC)
      ? `${t('weather.worldweather.tomorrow')} ${fmtNum(p.tomorrowMaxC, 0)} °C${p.tomorrowWord ? `, ${p.tomorrowWord}` : ''}`
      : '';
    const tipHtml = `<b>${escapeHtml(p.place || '')}</b>${p.role
        ? ` <span class="wm-tip-mute">(${escapeHtml(p.role)})</span>` : ''}
      ${now ? `<div>${escapeHtml(now)}</div>` : ''}
      ${tm ? `<div class="wm-tip-mute">${escapeHtml(tm)}</div>` : ''}`;
    const label = isNum(p.tempC)
      ? `<text class="wm-temp" x="6.5" y="3.5">${Math.round(p.tempC)}°</text>` : '';
    return marker(p.lat, p.lon, 'wm-place', condGlyph(p.word) + label,
        tipHtml, 'weather.stats.worldweather');
  }).join('');
}

// Physical hazards: storm swirl, magnitude-scaled quake rings, plane glyph.
function hazardMarkers(hazards) {
  if (!Array.isArray(hazards)) return '';
  return hazards.map(h => {
    const kindKey = h.kind === 'STORM' ? 'weather.hazards.storm'
        : h.kind === 'QUAKE' ? 'weather.hazards.quake'
        : h.kind === 'AVIATION' ? 'weather.hazards.aviation' : null;
    const tipHtml = `<b>${escapeHtml(kindKey ? t(kindKey) : (h.kind || ''))}</b>
      <div>${escapeHtml(h.text || '')}</div>`;
    const hi = h.severity === 'HIGH' ? ' hi' : '';
    let body;
    if (h.kind === 'QUAKE') {
      // Concentric rings, scaled by any magnitude the text names ("M6.2").
      const mag = magnitudeIn(h.text);
      const r1 = 4 + Math.max(0, Math.min(6, (mag - 5) * 2));
      body = `<circle class="wm-quake-core" r="1.8"/>
        <circle class="wm-quake-ring" r="${r1.toFixed(1)}"/>
        <circle class="wm-quake-ring outer" r="${(r1 + 3.2).toFixed(1)}"/>`;
    } else if (h.kind === 'AVIATION') {
      body = `<path class="wm-plane" d="M0 -5.5L1 -4.2L1 -1.4L5.4 1L5.4 2.2L1 1.2L1 3.4L2.4 4.6L2.4 5.6L0 4.9L-2.4 5.6L-2.4 4.6L-1 3.4L-1 1.2L-5.4 2.2L-5.4 1L-1 -1.4L-1 -4.2Z"/>`;
    } else { // STORM
      body = `<circle class="wm-storm-eye" r="2"/>
        <path class="wm-storm-arm" d="M0 -6.2A6.2 6.2 0 0 1 6.2 0M0 6.2A6.2 6.2 0 0 1 -6.2 0"/>`;
    }
    return marker(h.lat, h.lon, `wm-hazard${hi}`, body, tipHtml, 'weather.stats.hazards');
  }).join('');
}

// Conflict events, honestly country-level: a dark-red triangle at the
// country centroid; the tooltip says so.
function conflictMarkers(conflicts) {
  if (!Array.isArray(conflicts)) return '';
  return conflicts.map(c => {
    const tipHtml = `<b>${escapeHtml(t('weather.map.region'))} ${escapeHtml(c.country || '')}</b>
      <span class="wm-tip-mute">(${escapeHtml(t('weather.map.countrylevel'))})</span>
      <div>${escapeHtml(c.text || '')}</div>
      ${c.source ? `<div class="wm-tip-mute">(${escapeHtml(c.source)})</div>` : ''}`;
    return marker(c.lat, c.lon, 'wm-conflict',
        `<path d="M0 -4.6L4.2 3.2H-4.2Z"/>`, tipHtml, 'weather.stats.conflicts');
  }).join('');
}

// Maritime chokepoints: dot tinted by the week delta, radius scaled by |Δ|.
function chokeMarkers(chokepoints) {
  if (!Array.isArray(chokepoints)) return '';
  const coords = new Map(CHOKEPOINTS.map(([name, lat, lon]) => [name, [lat, lon]]));
  return chokepoints.map(c => {
    const at = coords.get(c.name);
    if (!at) return '';
    const d = c.weekDeltaPercent;
    const dir = isNum(d) ? (d >= 0 ? 'up' : 'down') : 'na';
    const r = isNum(d) ? 2.8 + Math.min(2.6, Math.abs(d) * 0.12) : 2.8;
    const bits = [];
    if (isNum(c.transits)) bits.push(`${fmtNum(c.transits, 0)} ${t('weather.choke.transits')}`);
    if (isNum(d)) bits.push(`${t('weather.map.weekdelta')} ${d > 0 ? '+' : ''}${fmtNum(d, 1)} %`);
    const tipHtml = `<b>${escapeHtml(c.name || '')}</b>
      ${bits.length ? `<div>${escapeHtml(bits.join(' · '))}</div>` : ''}`;
    return marker(at[0], at[1], `wm-choke ${dir}`,
        `<circle r="${r.toFixed(1)}"/>`, tipHtml, 'weather.stats.chokepoints');
  }).join('');
}

// The exchange cities — small gold squares, name on hover.
function exchangeMarkers() {
  return EXCHANGES.map(([exchange, city, lat, lon]) => marker(lat, lon, 'wm-exchange',
      `<rect x="-2.4" y="-2.4" width="4.8" height="4.8" rx="0.9"/>`,
      `<b>${escapeHtml(exchange)}</b> <span class="wm-tip-mute">${escapeHtml(city)}</span>`,
      null)).join('');
}

// ---- small helpers -----------------------------------------------------------

// The frozen condition word (WorldWeatherClient.codeWord German tokens; kept
// tolerant of English) bucketed into a tiny stroke glyph, house wx grammar.
function condGlyph(word) {
  const w = (word || '').toLowerCase();
  if (w.includes('gewitter') || w.includes('thunder')) {
    return `<g class="wm-cond"><path class="cloud" d="${TINY_CLOUD}"/><path class="wet" d="M0.6 2.6L-1 4.8H0.6L-0.9 7"/></g>`;
  }
  if (w.includes('schnee') || w.includes('snow')) {
    return `<g class="wm-cond"><path class="cloud" d="${TINY_CLOUD}"/><path class="wet" d="M-1.6 4.4v0.01M1.6 4.4v0.01M0 6v0.01"/></g>`;
  }
  if (w.includes('regen') || w.includes('niesel') || w.includes('schauer')
      || w.includes('rain') || w.includes('drizzle') || w.includes('shower')) {
    return `<g class="wm-cond"><path class="cloud" d="${TINY_CLOUD}"/><path class="wet" d="M-2 4.2l-0.6 1.6M0.4 4.2l-0.6 1.6M2.8 4.2l-0.6 1.6"/></g>`;
  }
  if (w.includes('nebel') || w.includes('fog')) {
    return `<g class="wm-cond"><path class="cloud" d="M-3 3.4h6M-2.2 5.4h4.4M-3 1.4a2 2 0 0 1 0-4 2.6 2.6 0 0 1 5-0.6 2 2 0 0 1 0.4 4.1"/></g>`;
  }
  if (w.includes('klar') || w.includes('heiter') || w.includes('clear') || w.includes('sunn')) {
    return `<g class="wm-cond"><circle class="sun" r="2.3"/>
      <path class="sun" d="M0 -4.4v1.3M0 4.4v-1.3M-4.4 0h1.3M4.4 0h-1.3M-3.1 -3.1l0.9 0.9M3.1 3.1l-0.9 -0.9M3.1 -3.1l-0.9 0.9M-3.1 3.1l0.9 -0.9"/></g>`;
  }
  // bedeckt / cloudy / everything else
  return `<g class="wm-cond"><path class="cloud" d="${TINY_CLOUD}"/></g>`;
}

const TINY_CLOUD = 'M-3 2.6a2.3 2.3 0 0 1 0-4.6 3 3 0 0 1 5.8-0.7 2.3 2.3 0 0 1 0.2 4.6Z';

/** First "M6.2"-shaped magnitude in a hazard text, or a neutral default. */
function magnitudeIn(text) {
  const m = /\bM\s?(\d+(?:[.,]\d+)?)/.exec(text || '');
  if (!m) return 5.5;
  const v = Number(m[1].replace(',', '.'));
  return isFinite(v) ? v : 5.5;
}

function fmtNum(v, decimals) {
  return v.toLocaleString(currentLang() === 'de' ? 'de-DE' : 'en-GB',
      { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}
