// Fear & Greed gauge: a compact CNN-style speedometer for whole-market sentiment.
// Sits beside the room's BULLISH/BEARISH badge in the Reddit header. Fed by the
// `fear-greed` socket payload {score, rating, band, previousClose}.

const FG_LIVE = true; // kill switch

const BAND_LABEL = {
  EXTREME_FEAR: 'Extreme Angst',
  FEAR: 'Angst',
  NEUTRAL: 'Neutral',
  GREED: 'Gier',
  EXTREME_GREED: 'Extreme Gier',
};

export function renderFearGreed(host, d) {
  if (!host) return;
  if (!FG_LIVE || !d || typeof d.score !== 'number' || !isFinite(d.score)) {
    host.hidden = true;
    return;
  }
  const score = Math.max(0, Math.min(100, d.score));
  const band = d.band || 'NEUTRAL';

  // Needle: score 0 → points left (180°), 100 → points right (0°). SVG y is down,
  // so the semicircle sits on top; subtract the sin component.
  const theta = Math.PI * (1 - score / 100);
  const R = 13, cx = 20, cy = 22;
  const nx = (cx + R * Math.cos(theta)).toFixed(1);
  const ny = (cy - R * Math.sin(theta)).toFixed(1);

  const label = BAND_LABEL[band] || d.rating || '';
  host.dataset.band = band;
  host.title = `Fear & Greed Index: ${Math.round(score)} — ${label}`;
  host.innerHTML = `
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
    </svg>
    <span class="fg-val">${Math.round(score)}</span>`;
  host.hidden = false;
}
