// Receives the trading calendar from the backend and answers
// `regionStatus(symbol)` for the footer countdown.
//
// The backend ships next-14-days windows per region; this module finds
// the active or next window for "now" without re-asking the server.

let calendar = [];  // [{symbol, zone, sessions: [{state, startUtcMs, endUtcMs}]}]
const listeners = [];

export function setMarketCalendar(payload) {
  calendar = payload || [];
  listeners.forEach(fn => { try { fn(); } catch (e) { console.warn(e); } });
}

export function onCalendarUpdate(fn) {
  listeners.push(fn);
}

export function regions() {
  return calendar.map(r => r.symbol);
}

export function regionStatus(symbol, nowMs = Date.now()) {
  const region = calendar.find(r => r.symbol === symbol);
  if (!region) return { state: 'closed', remainMs: 0, longClosure: true };

  const sessions = region.sessions;

  // Inside any session (pre / main / post): countdown to that
  // session's own end. Each phase has its own countdown so the
  // visible jump at pre→main and main→post is the signal that
  // the phase has switched.
  for (const s of sessions) {
    if (nowMs >= s.startUtcMs && nowMs < s.endUtcMs) {
      return { state: s.state, remainMs: s.endUtcMs - nowMs, longClosure: false };
    }
  }
  // Closed: countdown to the next session start of any kind —
  // pre-market if the region has one, otherwise main. The chip
  // stays red/closed until the first session actually begins.
  for (const s of sessions) {
    if (nowMs < s.startUtcMs) {
      return {
        state: 'closed',
        remainMs: s.startUtcMs - nowMs,
        longClosure: isLongClosure(sessions, region.zone, nowMs, s.startUtcMs),
      };
    }
  }
  return { state: 'closed', remainMs: 0, longClosure: true };
}

// True when the closure spans a whole non-trading day — i.e. today is
// itself a weekend/holiday, or the next open is the day after tomorrow
// or later. A normal overnight gap (opens today or tomorrow) is false,
// so it keeps its live countdown. This is calendar-day based, not a
// fixed number of hours, so a single mid-week holiday reads "FREI" for
// its entire duration rather than flipping to a countdown mid-afternoon.
function isLongClosure(sessions, zone, nowMs, nextStartMs) {
  const nowKey = localDateKey(nowMs, zone);
  const todayHasSession = sessions.some(s => localDateKey(s.startUtcMs, zone) === nowKey);
  if (!todayHasSession) return true;
  return dayDiff(nowKey, localDateKey(nextStartMs, zone)) >= 2;
}

// Calendar date in the exchange's own time zone, as a sortable
// YYYY-MM-DD key (en-CA renders ISO order).
function localDateKey(ms, zone) {
  return new Date(ms).toLocaleDateString('en-CA', { timeZone: zone });
}

// Whole calendar days between two YYYY-MM-DD keys. Both parse as UTC
// midnight, so the difference is exact and time-zone agnostic.
function dayDiff(keyA, keyB) {
  return Math.round((Date.parse(keyB) - Date.parse(keyA)) / 86400000);
}
