// Ticker highlighting.
//
// Two sources of truth merge here:
//   1. `$XYZ` patterns in the text (regex, opt-in syntax — zero false
//      positives because plain English never starts a word with $).
//   2. Explicit `tickerSymbol` from the agent metadata, which marks the
//      headline's primary asset even when the body omits the $-prefix.
//
// No hard-coded symbol list. The regex spans 1-5 uppercase letters
// optionally followed by `.X` (BRK.A style).

const TICKER_RE = /\$[A-Z]{1,5}(?:\.[A-Z])?\b/g;

export function highlightTickers(text, explicitSymbol) {
  if (!text) return '';
  let html = escapeHtml(text).replace(TICKER_RE,
      match => `<span class="ticker">${match}</span>`);

  // If the agent supplied an explicit ticker that isn't in the text
  // as $XYZ already, search for the bare symbol on word boundaries
  // and wrap the first occurrence so the visual cue still applies.
  if (explicitSymbol) {
    const sym = explicitSymbol.replace(/^\$/, '').toUpperCase();
    const bare = new RegExp(`(?<![\\w$])${sym}(?![\\w])`);
    if (!html.includes(`>$${sym}<`) && bare.test(html)) {
      html = html.replace(bare, `<span class="ticker">$${sym}</span>`);
    }
  }
  return html;
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}
