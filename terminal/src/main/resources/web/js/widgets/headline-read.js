// Per-headline read state, persisted across restarts.
//
// Two parts, and the pairing is what keeps the store from growing forever:
//
//   baseline — the epoch second at which this terminal first started tracking.
//              Everything older counts as READ. That is the "alte Zeilen sind
//              gelesen" rule: switching the feature on must not paint three
//              days of archive as unread, and scrolling back into the archive
//              must not either.
//   keys     — the individual rows read since then (the row key, which already
//              carries its timestamp). Pruned by age and by count, so a wire
//              running for months does not turn localStorage into a landfill.
//
// localStorage rides the CEF profile, so both survive a restart.

const STORE_KEY = 'wsbg.headlines.read';
const KEEP_DAYS = 30;
const MAX_KEYS = 4000;

/**
 * Creates the store. `keyAge(key)` extracts the epoch second a row key stands
 * for — the store never learns the key FORMAT, the caller owns that.
 */
export function createReadState({ keyAge }) {
  let baseline = 0;
  let read = new Set();
  let saveTimer = null;

  load();
  // The debounced write must not lose the last rows to a closing window.
  window.addEventListener('pagehide', () => { if (saveTimer) save(); });

  function load() {
    let raw = null;
    try { raw = JSON.parse(localStorage.getItem(STORE_KEY) || 'null'); } catch (_) { /* corrupt → cold start */ }
    if (raw && typeof raw.baseline === 'number') {
      baseline = raw.baseline;
      read = new Set(Array.isArray(raw.keys) ? raw.keys : []);
      return;
    }
    // First ever start: draw the line under everything that already exists.
    baseline = Math.floor(Date.now() / 1000);
    save();
  }

  function save() {
    clearTimeout(saveTimer);
    saveTimer = null;
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify({ baseline, keys: [...read] }));
    } catch (_) { /* best effort — a full quota must not break the wire */ }
  }

  // Marking happens row by row while scrolling; one write per row would hit
  // the disk dozens of times a second for no gain.
  function saveSoon() {
    if (saveTimer) return;
    saveTimer = setTimeout(save, 1500);
  }

  function prune() {
    if (read.size <= MAX_KEYS) return;
    const cutoff = Math.floor(Date.now() / 1000) - KEEP_DAYS * 86400;
    const kept = [...read].filter(k => keyAge(k) >= cutoff);
    // Still over the cap after the age sweep → keep the newest, drop the tail.
    // Anything dropped is older than everything kept, i.e. deep in the archive
    // where the baseline rule would call it read anyway.
    if (kept.length > MAX_KEYS) {
      kept.sort((a, b) => keyAge(b) - keyAge(a));
      kept.length = MAX_KEYS;
    }
    read = new Set(kept);
  }

  return {
    /** A row is read when it predates the baseline or was explicitly seen. */
    isRead(key, createdAt) {
      return createdAt <= baseline || read.has(key);
    },
    /** Returns true when this actually changed something. */
    markRead(key, createdAt) {
      if (createdAt <= baseline || read.has(key)) return false;
      read.add(key);
      prune();
      saveSoon();
      return true;
    },
    /** Flushes a pending write (used on unload). */
    flush() {
      if (saveTimer) save();
    },
  };
}
