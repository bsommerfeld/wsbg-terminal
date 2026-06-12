// Market-mood badge (Reddit widget header): the room's 24h sentiment split
// from the server's `market-mood` broadcasts — "54% BEARISH" implies 46%
// bullish; a dead 50/50 renders as NEUTRAL. The badge re-renders on every
// broadcast (client open, each published headline, 60s safety poll), so it
// is live by construction; the CSS colour transition makes a camp flip
// glide instead of snap — and the percent ROLLS to its new value over the
// same window (odometer feel), so number and colour settle together.
//
// Kill switch: set false to take the badge off the page without touching
// markup/CSS/socket wiring.
const MOOD_BADGE_LIVE = true;

// Roll duration — keep in sync with the .mood colour transition in
// widgets.css (600ms), so the count settles exactly when the colour does.
const ROLL_MS = 600;

// Single-badge module state: the percent currently SHOWN (which, mid-roll,
// is not the last target — a burst of updates must roll on from what the
// user actually sees, not jump), and the in-flight rAF handle.
let shownPct = null;
let rollFrame = 0;

export function renderMood(host, m) {
  if (!host || !MOOD_BADGE_LIVE) return;

  // No directional headline yet (or an empty wire): nothing honest to
  // show — keep/return the badge to hidden rather than faking a reading.
  if (!m || !m.dominant) {
    cancelAnimationFrame(rollFrame);
    host.hidden = true;
    shownPct = null;
    return;
  }

  // Server "MIXED" (exact tie) → NEUTRAL on the badge, neutral colour.
  const mood = m.dominant === 'MIXED' ? 'NEUTRAL' : m.dominant;
  host.dataset.mood = mood;
  host.title =
    `Marktstimmung aus den Headlines der letzten 24h — ` +
    `${m.bullish} bullish · ${m.bearish} bearish` +
    (m.total > m.directional ? ` (+${m.total - m.directional} neutral)` : '');

  cancelAnimationFrame(rollFrame);

  if (mood === 'NEUTRAL') {
    host.textContent = 'NEUTRAL';
    shownPct = null;
  } else if (host.hidden || shownPct === null || shownPct === m.percent) {
    // First appearance (or no change): no theatre, just the value.
    host.textContent = `${m.percent}% ${mood}`;
    shownPct = m.percent;
  } else {
    rollTo(host, shownPct, m.percent, mood);
  }
  host.hidden = false;
}

/** Rolls the displayed percent from → to over ROLL_MS (ease-out cubic), label riding along. */
function rollTo(host, from, to, label) {
  // t0 is taken from the FIRST rAF timestamp, not performance.now(): both
  // must come from the same clock or progress can run backwards (rAF
  // timestamps and performance.now() diverge under virtual/throttled time).
  let t0 = null;
  const step = now => {
    if (t0 === null) t0 = now;
    const t = Math.min(1, (now - t0) / ROLL_MS);
    const eased = 1 - Math.pow(1 - t, 3);
    shownPct = Math.round(from + (to - from) * eased);
    host.textContent = `${shownPct}% ${label}`;
    if (t < 1) rollFrame = requestAnimationFrame(step);
  };
  rollFrame = requestAnimationFrame(step);
}
