// Keyed incremental list engine + archive scroll-back paging.
//
// The mechanics behind the headline widget, kept free of row-content concerns:
// the caller injects `identity(item)` (the per-row key), `renderRow(item, isNew)`
// (builds and returns the row element) and `renderEmpty(host)` (the empty state).
//
// The list is two stacked layers, newest at the top:
//   liveItems    — the live 24h wire (pushed, replaces wholesale)
//   archiveItems — older headlines pulled from the permanent archive as the user
//                  scrolls DOWN past the wire (prepended below, never flash).
// Both are kept so a live push re-renders without dropping the scroll-back rows.
//
// Incremental keyed sync, NOT an innerHTML rebuild: existing row elements are
// kept (a row is immutable per key), new ones are created where they belong,
// vanished ones removed. A live push therefore costs a handful of node
// insertions instead of re-laying-out the whole list, the browser's remembered
// content-visibility row heights survive, and an in-flight scroll isn't
// interrupted by a layout storm.

const PAGE = 50;
// Scroll-back rows kept in memory/DOM. Trimmed (oldest first) on a live push
// while the user is reading the top of the wire — never under their viewport.
const ARCHIVE_CAP = 300;

export function createHeadlineList({ identity, renderRow, renderEmpty, filterFn, renderNoMatches,
                                     renderSearchHead, renderSearchEmpty }) {
  // The scan filter (headline-filter.js). Applied at DISPLAY time only: the
  // liveItems/archiveItems arrays stay complete, so the scroll-back paging cursor
  // and a filter toggle-off both work without any re-fetch.
  const passes = filterFn || (() => true);
  const noMatches = renderNoMatches || renderEmpty;
  // Per-host caches. seenKeys: last render's live keys (new-row diffing). rowEls:
  // key -> row element, so a re-render only creates/removes/moves elements.
  const seenKeys = new WeakMap();
  const rowEls = new WeakMap();

  let liveItems = [];
  let archiveItems = [];
  let hostRef = null;
  let socketRef = null;
  let loadingMore = false;
  let exhausted = false;     // archive returned a short/empty page → nothing older left
  // Search mode: an explicit archive lookup replaces the wire view until the
  // banner is closed. The wire arrays keep updating underneath (a live push
  // only stores), so closing the search restores the current state instantly.
  // Search results deliberately bypass the scan filter — they are what the
  // user asked for, not the wire's rolling display.
  let search = null;         // { query, total, items } | null

  function render(host, items) {
    if (!host) return;
    hostRef = host;
    liveItems = items || [];
    if (search) return;      // store only — the search view owns the host
    if (liveItems.length === 0 && archiveItems.length === 0) {
      renderEmpty(host);
      seenKeys.set(host, new Set());
      rowEls.set(host, new Map());
      return;
    }
    if (archiveItems.length > ARCHIVE_CAP && host.scrollTop < host.clientHeight) {
      archiveItems = archiveItems.slice(0, ARCHIVE_CAP);
      exhausted = false; // scrolling back down re-pages the trimmed rows
    }
    renderCombined();
  }

  /** Wires the scroll-to-bottom → load-older-archive behaviour (call once). */
  function initScroll(host, socket) {
    if (!host) return;
    hostRef = host;
    socketRef = socket;
    host.addEventListener('scroll', () => {
      if (search || loadingMore || exhausted) return;
      // within ~1.5 rows of the bottom → fetch the next older page
      if (host.scrollTop + host.clientHeight >= host.scrollHeight - 120) loadMore();
    }, { passive: true });
  }

  /** Appends an older archive page (from the `archive-results` page command). */
  function appendArchivePage(items) {
    loadingMore = false;
    if (!items || items.length === 0) { exhausted = true; return; }
    const known = new Set([...liveItems, ...archiveItems].map(identity));
    const fresh = items.filter(h => !known.has(identity(h)));
    if (fresh.length === 0) { exhausted = true; return; }
    if (items.length < PAGE) exhausted = true; // last page
    archiveItems = archiveItems.concat(fresh);
    if (!search) renderCombined(); // a late page must not clobber an open search view
  }

  function loadMore() {
    if (!socketRef) return;
    const all = [...liveItems, ...archiveItems];
    if (all.length === 0) return;
    const oldest = all.reduce((m, h) => Math.min(m, h.createdAt), Infinity);
    loadingMore = true;
    socketRef.send('archive', { command: 'page', before: oldest, limit: PAGE, requestId: 'scrollback' });
  }

  // Renders live (top) + archive (below) as one list, de-duped by row key.
  // Only genuinely-new LIVE rows flash; archive rows never do.
  function renderCombined() {
    const host = hostRef;
    if (!host) return;
    const byKey = new Map();
    for (const h of [...liveItems, ...archiveItems]) if (passes(h)) byKey.set(identity(h), h);

    const prev = seenKeys.get(host) || new Set();
    const isFirstRender = prev.size === 0;
    const liveKeys = new Set(liveItems.map(identity));

    let els = rowEls.get(host);
    if (!els) { els = new Map(); rowEls.set(host, els); }

    // Nothing to show. Two distinct reasons, two distinct visuals:
    //   - data exists but the filter hid all of it → the "no matches" filler;
    //   - no data at all (still cold, or the wire is empty) → the cook state.
    // Both must render HERE (not only in render()), because a filter toggle
    // re-renders through this path — without it, toggling a filter on an empty
    // wire would blank the cook GIF instead of keeping it until the first line.
    if (byKey.size === 0) {
      for (const [, el] of els) el.remove();
      els.clear();
      if (liveItems.length || archiveItems.length) noMatches(host);
      else renderEmpty(host);
      seenKeys.set(host, new Set());
      return;
    }

    if (els.size === 0) host.innerHTML = ''; // clear the empty-state / no-match placeholder

    for (const [key, el] of els) {
      if (!byKey.has(key)) { el.remove(); els.delete(key); }
    }

    // One ordered walk: reuse the element under the cursor when it matches,
    // otherwise insert (a new node, or an existing one moved) before it.
    let cursor = host.firstElementChild;
    for (const [key, h] of byKey) {
      const existing = els.get(key);
      if (existing) {
        if (existing === cursor) {
          cursor = cursor.nextElementSibling;
        } else {
          host.insertBefore(existing, cursor);
        }
      } else {
        const el = renderRow(h, !isFirstRender && liveKeys.has(key) && !prev.has(key));
        els.set(key, el);
        host.insertBefore(el, cursor);
      }
    }
    seenKeys.set(host, liveKeys);
  }

  // Re-applies the current filter to the loaded data (called when the spec
  // changes). No re-fetch — just a keyed re-sync over the intact arrays.
  // While a search is showing, keep showing it (the filter doesn't apply there).
  function rerender() {
    if (!hostRef) return;
    if (search) renderSearch();
    else renderCombined();
  }

  /** Enters (or replaces) the search-result view. */
  function showSearch(query, total, items) {
    search = { query, total, items: items || [] };
    renderSearch();
  }

  /** Leaves the search view and restores the live wire + scroll-back state. */
  function clearSearch() {
    if (!search) return;
    search = null;
    const host = hostRef;
    if (!host) return;
    // The search view painted freehand — drop the keyed caches so the wire
    // re-syncs from scratch (and without replaying the gold flash).
    const els = rowEls.get(host);
    if (els) els.clear();
    seenKeys.set(host, new Set());
    host.innerHTML = '';
    renderCombined();
    host.scrollTop = 0;
  }

  // Search results are a static snapshot — no keyed sync, no flash: a plain
  // banner (query + hit count + close) followed by the result rows.
  function renderSearch() {
    const host = hostRef;
    if (!host || !search) return;
    const els = rowEls.get(host);
    if (els) els.clear();
    seenKeys.set(host, new Set());
    host.innerHTML = '';
    host.appendChild(renderSearchHead(search.query, search.total, search.items.length, clearSearch));
    if (search.items.length === 0) {
      host.appendChild(renderSearchEmpty());
    } else {
      for (const h of search.items) host.appendChild(renderRow(h, false));
    }
    host.scrollTop = 0;
  }

  return { render, initScroll, appendArchivePage, rerender, showSearch, clearSearch };
}
