// Fear & Greed gauge: a compact CNN-style speedometer for whole-market sentiment.
// Sits beside the room's BULLISH/BEARISH badge in the Reddit header. Fed by the
// `fear-greed` socket payload {score, rating, band, previousClose}.

import { t } from '../i18n/i18n.js';

const FG_LIVE = true; // kill switch

const BANDS = ['EXTREME_FEAR', 'FEAR', 'NEUTRAL', 'GREED', 'EXTREME_GREED'];

// Build the gauge markup for a given score (0–100). The arc + hub are static;
// only the needle angle and the figure change. `loading` renders the value as a
// shimmering placeholder (see .fg-val.loading in reddit.css) for the skeleton.
function gaugeSvg(score, loading) {
  // Needle: score 0 → points left (180°), 100 → points right (0°). SVG y is down,
  // so the semicircle sits on top; subtract the sin component.
  const theta = Math.PI * (1 - score / 100);
  const R = 13, cx = 20, cy = 22;
  const nx = (cx + R * Math.cos(theta)).toFixed(1);
  const ny = (cy - R * Math.sin(theta)).toFixed(1);
  const val = loading
    ? `<span class="fg-val loading" aria-hidden="true">50</span>`
    : `<span class="fg-val">${Math.round(score)}</span>`;
  return `
    <svg class="fg-gauge" viewBox="0 0 40 26" width="38" height="25" aria-hidden="true">
      <defs>
        <linearGradient id="fg-grad" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stop-color="#e2504a"/>
          <stop offset="0.5" stop-color="#e0b000"/>
          <stop offset="1" stop-color="#3fb950"/>
        </linearGradient>
      </defs>
      <path class="fg-track" d="M5,22 A15,15 0 0 1 35,22" fill="none"
            stroke="url(#fg-grad)" stroke-width="4" stroke-linecap="round"/>
      <line class="fg-needle" x1="20" y1="22" x2="${nx}" y2="${ny}"/>
      <circle class="fg-hub" cx="20" cy="22" r="2.4"/>
    </svg>${val}`;
}

export function renderFearGreed(host, d) {
  if (!host) return;
  if (!FG_LIVE || !d || typeof d.score !== 'number' || !isFinite(d.score)) {
    // Don't yank the gauge back to hidden once a skeleton/value is showing —
    // a transient empty payload would otherwise make it flicker out.
    if (!host.querySelector('.fg-gauge')) host.hidden = true;
    return;
  }
  const score = Math.max(0, Math.min(100, d.score));
  const band = d.band || 'NEUTRAL';

  const label = BANDS.includes(band) ? t('fg.' + band) : (d.rating || '');
  host.dataset.band = band;
  host.title = `Fear & Greed Index: ${Math.round(score)} — ${label}\n${t('fg.open')}`;
  host.innerHTML = gaugeSvg(score, false);
  host.hidden = false;
}

// Paint an always-visible placeholder — the gradient arc with a centred needle
// and a shimmering figure — as soon as the DOM is ready, so the gauge slot
// exists before the first reading arrives instead of popping in (~8 s after
// launch, then only every 5 min). The first renderFearGreed() swaps in the live
// value. Self-initialising so no wiring is needed in main.js; module scripts are
// deferred, so #fear-greed-badge is already in the DOM by the time this runs.
function paintFearGreedSkeleton() {
  if (!FG_LIVE) return;
  const host = document.getElementById('fear-greed-badge');
  if (host && !host.querySelector('.fg-gauge')) {
    host.dataset.band = 'NEUTRAL';
    host.innerHTML = gaugeSvg(50, true);   // needle straight up, neutral
    host.hidden = false;
  }
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', paintFearGreedSkeleton);
} else {
  paintFearGreedSkeleton();
}
