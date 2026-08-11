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
// On top of the two layers sits the READ layer (optional — only active when the
// caller hands in a `readState`): every contiguous run of unread rows is
// outlined as one block, several blocks can stand open at once, a row turns
// read after dwelling on screen AND being actually readable (`canRead`), and
// `onUnread` reports which EDGE of the list
// still has unread beyond it so the caller can light its portal.
//
// Incremental keyed sync, NOT an innerHTML rebuild: existing row elements are
// kept (a row is immutable per key), new ones are created where they belong,
// vanished ones removed. A live push therefore costs a handful of node
// insertions instead of re-laying-out the whole list, the browser's remembered
// content-visibility row heights survive, and an in-flight scroll isn't
// interrupted by a layout storm.

import { isIdle, onIdleChange } from '../chrome/idle.js';

const PAGE = 50;
// Scroll-back rows kept in memory/DOM. Trimmed (oldest first) on a live push
// while the user is reading the top of the wire — never under their viewport.
const ARCHIVE_CAP = 300;

// Marks the row BELOW a quiet stretch of the wire (see markGaps).
const GAP_CLASS = 'time-gap';

// Unread run marking (see markUnread): every unread row, plus the first and
// last of each run so CSS can close the block's outline.
const UNREAD_CLASS = 'unread';
const UNREAD_HEAD = 'unread-head';
const UNREAD_TAIL = 'unread-tail';

// A row counts as read once it has held at least this much of itself on
// screen for this long — long enough that scrolling PAST a line doesn't
// consume it, short enough that reading down the wire keeps up.
const READ_RATIO = 0.6;
const READ_DWELL_MS = 900;
const DWELL_TICK_MS = 300;

export function createHeadlineList({ identity, renderRow, renderEmpty, filterFn, renderNoMatches,
                                     renderSearchHead, renderSearchEmpty, gapSeconds,
                                     readState, canRead, onUnread }) {
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

  // ---- unread state (only live when the caller passed a readState) ----
  /** The currently unread row elements, top-down — the direction probe's input. */
  let unreadEls = [];
  /** Last render's byKey map, so marking a row read can re-paint the runs alone. */
  let lastByKey = null;
  let observer = null;
  /** key -> { el, createdAt, ms } for the rows currently on screen and unread. */
  const dwelling = new Map();
  let dwellTimer = null;
  let probeQueued = false;

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
      // The portals answer "which way lies unread I cannot see" — that answer
      // changes with every scroll, not only with every render.
      probeDirection();
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
      clearUnread();
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
    markGaps(byKey, els);
    lastByKey = byKey;
    markUnread(byKey, els);
    seenKeys.set(host, liveKeys);
  }

  // A quiet night on the wire should LOOK like one: wherever two neighbouring
  // rows sit further apart in time than `gapSeconds`, the lower (older) one gets a
  // thin rule above it, so a reader scrolling back sees where the stream paused
  // instead of reading an unbroken column. Recomputed on every render — the
  // break belongs to the PAIR, and a live push or an archive page changes who
  // neighbours whom.
  function markGaps(byKey, els) {
    if (!gapSeconds) return;
    let prevAt = null;
    for (const [key, h] of byKey) {
      const el = els.get(key);
      if (!el) continue;
      const at = h.createdAt;
      const broken = prevAt !== null && Math.abs(prevAt - at) >= gapSeconds;
      el.classList.toggle(GAP_CLASS, broken);
      prevAt = at;
    }
  }

  // Unread runs. Read state is per ROW, so what the eye needs is not a single
  // waterline but every CONTIGUOUS stretch of unread rows: come back after an
  // hour and the fresh block sits on top; read that but leave yesterday's tail
  // open and the next block sits in the middle of the column. Each run is
  // outlined as one block (head + tail close it).
  // Recomputed on every render and after every row that turns read —
  // the run belongs to the NEIGHBOURHOOD, not to the row.
  function markUnread(byKey, els) {
    if (!readState) return;
    const rows = [];
    for (const [key, h] of byKey) {
      const el = els.get(key);
      if (el) rows.push({ key, el, createdAt: h.createdAt, unread: !readState.isRead(key, h.createdAt) });
    }
    unreadEls = [];
    rows.forEach((r, i) => {
      const head = r.unread && !(rows[i - 1] && rows[i - 1].unread);
      const tail = r.unread && !(rows[i + 1] && rows[i + 1].unread);
      r.el.classList.toggle(UNREAD_CLASS, r.unread);
      r.el.classList.toggle(UNREAD_HEAD, head);
      r.el.classList.toggle(UNREAD_TAIL, tail);
      if (r.unread) unreadEls.push(r);
    });
    observeUnread();
    probeDirection();
  }

  // No rows on screen (empty state, filter blank, search view): nothing to
  // track and nothing for the portals to point at.
  function clearUnread() {
    if (!readState) return;
    unreadEls = [];
    lastByKey = null;
    dwelling.clear();
    stopDwell();
    if (observer) observer.disconnect();
    if (onUnread) onUnread({ above: false, below: false });
  }

  // The read-tracker watches only what is still unread — a row that is done
  // costs nothing more. Re-armed after every run recompute.
  function observeUnread() {
    if (!hostRef) return;
    if (!observer) {
      observer = new IntersectionObserver(onIntersect, { root: hostRef, threshold: [0, READ_RATIO] });
      // Time spent away from the desk is not reading time: freeze the clock
      // while idle, and start it over for whatever is still on screen on wake.
      onIdleChange(idle => {
        if (idle) { stopDwell(); for (const d of dwelling.values()) d.ms = 0; }
        else startDwell();
      });
    }
    observer.disconnect();
    // Keep the running clocks of rows that are STILL unread — a recompute
    // fires whenever any row turns read, and wiping the map there would
    // restart every other visible row's dwell from zero, forever.
    const live = new Set(unreadEls.map(r => r.key));
    for (const key of [...dwelling.keys()]) if (!live.has(key)) dwelling.delete(key);
    for (const r of unreadEls) observer.observe(r.el);
    if (dwelling.size) startDwell(); else stopDwell();
  }

  function onIntersect(entries) {
    for (const e of entries) {
      const key = keyOfEl(e.target);
      if (!key) continue;
      if (e.isIntersecting && e.intersectionRatio >= READ_RATIO) {
        if (!dwelling.has(key)) dwelling.set(key, { el: e.target, ms: 0 });
      } else {
        dwelling.delete(key);
      }
    }
    if (dwelling.size) startDwell(); else stopDwell();
    probeDirection();
  }

  function keyOfEl(el) {
    const r = unreadEls.find(x => x.el === el);
    return r ? r.key : null;
  }

  function startDwell() {
    if (dwellTimer || isIdle() || dwelling.size === 0) return;
    dwellTimer = setInterval(tickDwell, DWELL_TICK_MS);
  }

  function stopDwell() {
    clearInterval(dwellTimer);
    dwellTimer = null;
  }

  function tickDwell() {
    if (isIdle()) { stopDwell(); return; }
    // The gate is asked here, every tick, instead of being wired to a
    // view-change event: a row can stop being READABLE for reasons this module
    // has no business knowing (the widget shrank to a thumbnail in the
    // overview, another widget went fullscreen, an overlay covers it). Polling
    // the caller's predicate catches all of them, and catches the way back
    // without anyone having to remember to fire a signal.
    if (canRead && !canRead()) {
      for (const d of dwelling.values()) d.ms = 0;
      return;
    }
    let changed = false;
    for (const [key, d] of dwelling) {
      d.ms += DWELL_TICK_MS;
      if (d.ms < READ_DWELL_MS) continue;
      const row = unreadEls.find(x => x.key === key);
      if (row && readState.markRead(key, row.createdAt)) changed = true;
      dwelling.delete(key);
    }
    if (dwelling.size === 0) stopDwell();
    // One re-paint for the whole tick: the runs shrink (or split) around
    // whatever just turned read.
    if (changed && lastByKey && hostRef) markUnread(lastByKey, rowEls.get(hostRef) || new Map());
  }

  // Which way lies unread the reader cannot see? Both edges are answered
  // independently — a block above AND a block below is a normal state, and
  // each portal is lit on its own.
  function probeDirection() {
    if (!onUnread || !hostRef) return;
    if (probeQueued) return;
    probeQueued = true;
    requestAnimationFrame(() => {
      probeQueued = false;
      if (!hostRef) return;
      if (search) { onUnread({ above: false, below: false }); return; }
      const box = hostRef.getBoundingClientRect();
      let above = false, below = false;
      for (const r of unreadEls) {
        const rect = r.el.getBoundingClientRect();
        if (rect.bottom <= box.top + 2) above = true;
        else if (rect.top >= box.bottom - 2) below = true;
        if (above && below) break;
      }
      onUnread({ above, below });
    });
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
    // The search view paints freehand: no read tracking, no portals — it is
    // an explicit lookup, not the wire the reader is working through.
    clearUnread();
    host.innerHTML = '';
    host.appendChild(renderSearchHead(search.query, search.total, search.items.length, clearSearch));
    if (search.items.length === 0) {
      host.appendChild(renderSearchEmpty());
    } else {
      for (const h of search.items) host.appendChild(renderRow(h, false));
    }
    host.scrollTop = 0;
  }

  /** Every loaded record (live wire + scroll-back archive), UNfiltered. */
  function allItems() {
    return liveItems.concat(archiveItems);
  }

  return { render, initScroll, appendArchivePage, rerender, showSearch, clearSearch, allItems };
}
