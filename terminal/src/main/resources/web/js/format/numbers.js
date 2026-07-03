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
