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

export function createHeadlineList({ identity, renderRow, renderEmpty }) {
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

  function render(host, items) {
    if (!host) return;
    hostRef = host;
    liveItems = items || [];
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
      if (loadingMore || exhausted) return;
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
    renderCombined();
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
    for (const h of [...liveItems, ...archiveItems]) byKey.set(identity(h), h);

    const prev = seenKeys.get(host) || new Set();
    const isFirstRender = prev.size === 0;
    const liveKeys = new Set(liveItems.map(identity));

    let els = rowEls.get(host);
    if (!els) { els = new Map(); rowEls.set(host, els); }
    if (els.size === 0) host.innerHTML = ''; // clear the empty-state placeholder

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

  return { render, initScroll, appendArchivePage };
}
