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
//   - range       price band: null (egal) | { min, max }           (see below)
// A record must satisfy EVERY constrained facet (AND across facets). The
// mutually-exclusive pairs are one facet each, so "with" and "without" can never
// both be active — the contradiction the user wanted ruled out falls straight
// out of the model. (Sentiment/asset-class facets were dropped: sentiment has no
// UI on the rows yet, and asset class was too coarse to be useful.)
//
// The range facet is the only one with a continuous value. Its two bounds are
// each OPEN-ENDED at one side: `min: 0` means "no lower bound" and `max: null`
// means "no upper bound", so a band can cover the whole market upward while
// still resolving the 0,004 € pennystock at the bottom. Both open at once is
// not a constraint — that state is stored as `null`, the facet simply off.
// A band is also a statement ABOUT a price, so it implies "mit Preis": a line
// without a resolved snapshot can never satisfy it (see matches()).

const STORE_KEY = 'wsbg.headlineFilter';

function emptySpec() {
  return { highlight: 'ALL', price: null, news: null, range: null };
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
      range: sanitizeRange(p.range),
    };
  } catch {
    return emptySpec();
  }
}

/**
 * Normalises a price band into the canonical shape, or null when it doesn't
 * constrain anything. Every degenerate case collapses to "facet off" rather
 * than to a band that hides the whole wire: no bounds at all, an inverted band,
 * or garbage from an older/hand-edited payload.
 */
function sanitizeRange(r) {
  if (!r || typeof r !== 'object') return null;
  const rawMin = Number(r.min);
  const rawMax = r.max == null ? null : Number(r.max);
  const min = Number.isFinite(rawMin) && rawMin > 0 ? rawMin : 0;
  const max = rawMax != null && Number.isFinite(rawMax) && rawMax > 0 ? rawMax : null;
  if (min === 0 && max === null) return null;   // both ends open = no constraint
  if (max !== null && max <= min) return null;  // an inverted band would hide everything
  return { min, max };
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
  // "ohne Preis" and a price band are the same contradiction the pair itself
  // rules out, one facet further out — so picking one drops the other instead
  // of leaving a filter that can never match a single line.
  if (facet === 'price' && spec.price === 'without') spec.range = null;
  notify();
}

/**
 * Sets the price band. `min` 0 = no lower bound, `max` null = no upper bound;
 * both open clears the facet (see sanitizeRange).
 */
export function setRange(min, max) {
  spec.range = sanitizeRange({ min, max });
  if (spec.range && spec.price === 'without') spec.price = null;
  notify();
}

/** Clears every facet back to "show all". */
export function resetFilter() {
  spec = emptySpec();
  notify();
}

/** True when at least one facet is constraining the wire. */
export function isActive() {
  const f = activeFacets();
  return f.highlight || f.price || f.news;
}

/**
 * Which facets are currently constraining — the readout both funnel icons
 * (header + rail) light their strokes from, so the two anchors can never drift
 * apart on what "active" means. The band lives on the price stroke: it IS a
 * price constraint.
 */
export function activeFacets() {
  return {
    highlight: spec.highlight !== 'ALL',
    price: spec.price !== null || spec.range !== null,
    news: spec.news !== null,
  };
}

function hasPrice(h) {
  // snapshotJson emits null when there is no priced snapshot, so its mere
  // presence means the line carries a resolved price.
  return !!h.snapshot;
}

/** The resolved price of a line, or null when it carries no priced snapshot. */
export function priceOf(h) {
  const p = h && h.snapshot ? h.snapshot.price : null;
  return typeof p === 'number' && isFinite(p) && p > 0 ? p : null;
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
  if (spec.range) {
    const p = priceOf(h);
    if (p === null) return false;                                  // a band implies "mit Preis"
    if (p < spec.range.min) return false;
    if (spec.range.max !== null && p > spec.range.max) return false;
  }
  return true;
}
