import { t, currentLang } from '../i18n/i18n.js';

// Auto-colours signed percentages and absolute moves: green for up,
// red for down. Catches a wide range of formats:
//   +14%        +14.5%      -1,490.5%      +2,9 %       (decimals + thousand sep)
//   -$5.29      +$1.34b     -2.9M          +606K        (currency + magnitude)
//   +3.4 bps    -5.31 %     $94.29/bbl     +3.0400/MMBTU
//
// Runs over already-escaped HTML, so the ticker pass should happen
// first — otherwise the surrounding spans would be re-wrapped.
//
// Number body: `\d[\d.,]*` accepts any combo of digits + comma/dot
// separators (1,490.5 = "1,490.5" in one shot, no fragmentation).
// Suffix: single-letter magnitudes (M/K/B) gated by `(?![A-Za-z])`
// so we don't eat the M of "Markt".
const SIGN_RE = /(?:^|[\s(>])([+\-−])(\$?\d[\d.,]*(?:\s*(?:%|bps|bbl|MMBTU|Mio\.?|Mrd\.?|Bn|[MKBkmb])(?![A-Za-z]))?)/g;

export function colorizeSignedNumbers(html) {
  return html.replace(SIGN_RE, (full, sign, value) => {
    const isUp = sign === '+';
    const cls = isUp ? 'up' : 'down';
    const lead = full[0] === sign ? '' : full[0];
    return `${lead}<span class="pct ${cls}">${sign}${value}</span>`;
  });
}

// ---- Quote/price formatters (shared by the headline quote strip) ----

// Price with a currency glyph for the majors; pennystocks (< 1) get more
// decimals so a 0.0042 → 0.0061 move is still legible. A stock index carries
// the "PTS" marker (priced in points, not a currency) → "24.013 Pkt".
export function fmtPrice(p, ccy) {
  if (ccy === 'PTS') return Math.round(p).toLocaleString(currentLang() === 'de' ? 'de-DE' : 'en-US') + ' ' + t('quote.points');
  const sym = ccy === 'USD' ? '$' : ccy === 'EUR' ? '€' : ccy === 'GBP' ? '£' : '';
  const v = Math.abs(p) < 1 ? p.toFixed(4) : p.toFixed(2);
  return sym + v;
}

export function isNum(v) { return typeof v === 'number' && isFinite(v); }

export function fmtNum(v) { return Math.abs(v) < 1 ? v.toFixed(4) : v.toFixed(2); }

export function fmtVol(v) {
  if (v >= 1e9) return (v / 1e9).toFixed(1) + 'B';
  if (v >= 1e6) return (v / 1e6).toFixed(1) + 'M';
  if (v >= 1e3) return (v / 1e3).toFixed(1) + 'K';
  return String(v);
}
