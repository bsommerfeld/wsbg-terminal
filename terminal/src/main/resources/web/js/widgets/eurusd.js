// Renders the EUR/USD ticker in the FinancialJuice widget header.
//
// Minimal: [EU flag] rate [US flag]. The rate text tints green/red
// briefly on direction changes; NEUTRAL ticks are silent.

import { restartAnimation } from '../chrome/anim.js';

// Dwell ~2s in green/red on a direction change, then the base color
// transition (see .eurusd .rate in fj.css) fades back to white.
const FLASH_MS = 2000;

// Twelve yellow 5-point stars arranged in a ring on a blue field —
// the proper EU emblem layout. Building the polygon list once at
// module load keeps the per-render path cheap.
const FLAG_EU = buildEuFlag();

// Stylised US flag — 13 stripes alternating red/white plus the blue
// canton. Stars in the canton are omitted (illegible at this size).
const FLAG_US = `<svg class="flag flag-us" viewBox="0 0 30 20" xmlns="http://www.w3.org/2000/svg" aria-label="USD">` +
  `<rect width="30" height="20" fill="#B22234"/>` +
  `<g fill="#FFFFFF">` +
    `<rect y="1.54" width="30" height="1.54"/>` +
    `<rect y="4.62" width="30" height="1.54"/>` +
    `<rect y="7.69" width="30" height="1.54"/>` +
    `<rect y="10.77" width="30" height="1.54"/>` +
    `<rect y="13.85" width="30" height="1.54"/>` +
    `<rect y="16.92" width="30" height="1.54"/>` +
  `</g>` +
  `<rect width="12" height="10.77" fill="#3C3B6E"/>` +
`</svg>`;

function buildEuFlag() {
  const cx = 15, cy = 10, ringR = 6;
  // Single 5-point star at origin, R=1.3 outer / 0.52 inner.
  const starPts =
    "0,-1.3 0.31,-0.42 1.24,-0.4 0.49,0.16 0.76,1.05 " +
    "0,0.52 -0.76,1.05 -0.49,0.16 -1.24,-0.4 -0.31,-0.42";

  let stars = '';
  for (let i = 0; i < 12; i++) {
    const a = -Math.PI / 2 + i * (Math.PI / 6);   // 12 o'clock first, clockwise
    const x = (cx + ringR * Math.cos(a)).toFixed(2);
    const y = (cy + ringR * Math.sin(a)).toFixed(2);
    stars += `<polygon points="${starPts}" fill="#FFCC00" transform="translate(${x} ${y})"/>`;
  }
  return `<svg class="flag flag-eu" viewBox="0 0 30 20" xmlns="http://www.w3.org/2000/svg" aria-label="EUR">` +
         `<rect width="30" height="20" fill="#003399"/>${stars}</svg>`;
}

export function renderEurUsd(host, q) {
  if (!host || !q || typeof q.rate !== 'number') return;

  host.innerHTML = `${FLAG_EU}<span class="rate">${q.rate.toFixed(4)}</span>${FLAG_US}`;

  const dir = q.direction;
  if (dir === 'UP' || dir === 'DOWN') {
    restartAnimation(host, dir === 'UP' ? 'flash-up' : 'flash-down', FLASH_MS);
  }
}

// Paint an always-visible placeholder — flags framing a blurred, shimmering
// rate — as soon as the DOM is ready, so the EUR/USD slot exists before the
// first quote arrives instead of popping in. The first renderEurUsd() swaps
// in the live value and drops the .loading class. Self-initialising so no
// wiring is needed in main.js; module scripts are deferred, so #eurusd-badge
// is already in the DOM by the time this runs.
function paintEurUsdSkeleton() {
  const host = document.getElementById('eurusd-badge');
  if (host && !host.querySelector('.rate')) {
    host.innerHTML =
      `${FLAG_EU}<span class="rate loading" aria-hidden="true">0.0000</span>${FLAG_US}`;
  }
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', paintEurUsdSkeleton);
} else {
  paintEurUsdSkeleton();
}
