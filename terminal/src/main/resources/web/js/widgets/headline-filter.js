// Headline filter — the client-side scan filter over the live wire + archive.
//
// A faceted "show me only …" filter that runs purely in JS over the headline
// objects already received on the socket (no backend round-trip): every field it
// needs (highlight, snapshot, newsEnriched, sentiment, assetClass) is on the
// record. The list engine (headline-list.js) calls matches(h) per row and hides
// the ones that don't pass; this module owns the spec, its persistence, and the
// change notification.
//
// Facet model — the "clever" AND the user asked for:
//   - highlight   exclusive: 'ALL' (default) | 'IMPORTANT' (red only)
//   - price       boolean pair: null (egal) | 'with' | 'without'   (mutually exclusive)
//   - news        boolean pair: null (egal) | 'with' | 'without'
// A record must satisfy EVERY constrained facet (AND across facets). The
// mutually-exclusive pairs are one facet each, so "with" and "without" can never
// both be active — the contradiction the user wanted ruled out falls straight
// out of the model. (Sentiment/asset-class facets were dropped: sentiment has no
// UI on the rows yet, and asset class was too coarse to be useful.)

const STORE_KEY = 'wsbg.headlineFilter';

function emptySpec() {
  return { highlight: 'ALL', price: null, news: null };
}

let spec = load();
const listeners = new Set();

function load() {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (!raw) return emptySpec();
    const p = JSON.parse(raw);
    // Defensive merge: an older/broken payload must never crash the wire.
    return {
      highlight: p.highlight === 'IMPORTANT' ? 'IMPORTANT' : 'ALL',
      price: p.price === 'with' || p.price === 'without' ? p.price : null,
      news: p.news === 'with' || p.news === 'without' ? p.news : null,
    };
  } catch {
    return emptySpec();
  }
}

function save() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(spec)); } catch { /* ignore */ }
}

/** The current spec (a live reference — treat as read-only; mutate via the setters). */
export function getSpec() {
  return spec;
}

/** Subscribe to spec changes; returns an unsubscribe fn. */
export function onFilterChange(cb) {
  listeners.add(cb);
  return () => listeners.delete(cb);
}

function notify() {
  save();
  for (const cb of listeners) cb();
}

/** Sets the exclusive highlight facet: 'ALL' | 'IMPORTANT'. */
export function setHighlight(value) {
  spec.highlight = value === 'IMPORTANT' ? 'IMPORTANT' : 'ALL';
  notify();
}

/**
 * Tri-state toggle for a boolean-pair facet ('price' | 'news'): clicking the
 * active value clears the facet to egal, clicking the other switches to it —
 * so the two values are mutually exclusive by construction.
 */
export function togglePair(facet, value) {
  spec[facet] = spec[facet] === value ? null : value;
  notify();
}

/** Clears every facet back to "show all". */
export function resetFilter() {
  spec = emptySpec();
  notify();
}

/** True when at least one facet is constraining the wire. */
export function isActive() {
  return spec.highlight !== 'ALL' || spec.price !== null || spec.news !== null;
}

function hasPrice(h) {
  // snapshotJson emits null when there is no priced snapshot, so its mere
  // presence means the line carries a resolved price.
  return !!h.snapshot;
}

function hasNews(h) {
  return !!(h.newsEnriched || (Array.isArray(h.newsRefs) && h.newsRefs.length));
}

/** The predicate the list engine calls per row. A record passes every active facet. */
export function matches(h) {
  if (spec.highlight === 'IMPORTANT' && h.highlight !== 'IMPORTANT') return false;
  if (spec.price === 'with' && !hasPrice(h)) return false;
  if (spec.price === 'without' && hasPrice(h)) return false;
  if (spec.news === 'with' && !hasNews(h)) return false;
  if (spec.news === 'without' && hasNews(h)) return false;
  return true;
}
