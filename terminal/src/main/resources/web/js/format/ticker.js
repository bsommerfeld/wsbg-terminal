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

// Wraps each resolved subject's company NAME in a gold <span class="subject"> so the
// reader sees at a glance WHAT the line is about. The headline carries the name (never
// the ticker — the prompt forbids a ticker label), so the name is the cue, not the symbol.
// Longest names first (so "Munich Re" wins over a stray "Munich"), first occurrence only,
// never inside an existing tag. Escapes the text itself — pass RAW text, like highlightTickers.
export function highlightSubjects(text, subjects) {
  if (!text) return '';
  let html = escapeHtml(text);
  if (!Array.isArray(subjects)) return html;
  const names = subjects
    .map(s => (s && s.name ? String(s.name).trim() : ''))
    .filter(Boolean)
    .sort((a, b) => b.length - a.length);
  for (const name of names) {
    const esc = escapeHtml(name);
    if (!esc) continue;
    const re = new RegExp(`(?<![\\w])${escapeRegExp(esc)}(?![\\w])`);
    const m = re.exec(html);
    if (m && !insideTag(html, m.index)) {
      html = html.slice(0, m.index)
           + `<span class="subject">${esc}</span>`
           + html.slice(m.index + esc.length);
    }
  }
  return html;
}

function escapeRegExp(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

// True when `idx` falls inside a `<...>` tag (an unclosed '<' precedes it).
function insideTag(html, idx) {
  return html.lastIndexOf('<', idx) > html.lastIndexOf('>', idx);
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}
