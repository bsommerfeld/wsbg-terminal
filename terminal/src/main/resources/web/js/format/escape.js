// Shared HTML-escaping primitives.
//
// One source of truth for the full 5-character entity map used by every
// string-building renderer (headline rows, FJ rows, ticker highlighting).
// Note: markdown.js deliberately keeps its OWN escape set (it escapes only
// & < > and handles attributes separately, and its blockquote regex depends
// on the exact `&gt;` output), so it does NOT import from here.

const ENTITIES = {
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
};

/** Escapes the 5 HTML-significant characters for use in element text or attributes. */
export function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ENTITIES[c]);
}

// escapeHtml already escapes both quote characters, so an attribute value is
// covered by the same map — kept as a named alias for call-site intent.
export const escapeAttr = escapeHtml;
