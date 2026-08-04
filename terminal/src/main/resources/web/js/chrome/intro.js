// Startup intro — the anvil drop.
//
// The choreography itself is pure CSS (css/intro.css owns the timeline in its
// --t-… and --d-… custom properties). This module does three things and
// nothing else:
//
//   1. seeds the dust — each puff gets its own vector/size/opacity as custom
//      properties, so the smoke isn't a symmetric CSS clone of itself,
//   2. tears the plate out of the DOM once the fade is over,
//   3. lets the user skip it (click / any key) into a short fade.
//
// The plate is markup, not JS-injected, so it is on screen with the first
// paint — no flash of a half-built app before the logo lands. If this module
// never runs the CSS fade still ends on visibility:hidden, so a broken script
// can't lock the app behind the intro.

// A burst is many small, heavily blurred, half-transparent puffs — the cloud
// comes from them OVERLAPPING. Few big ones read as balloons, not as dust.
//
// They leave on a RING around the landing point, the way sand jumps away
// from a ball dropped into it: every direction at once, including toward the
// viewer. The ring is flattened vertically (FLATTEN) because we are looking
// at that surface from a shallow angle, so sideways travel reads far bigger
// on screen than travel toward or away from us.
const PUFF_COUNT = 16;
const FLATTEN = 0.42;
const PUFF_DUR = 1050;

// Deterministic pseudo-random (LCG): the burst looks scattered but is
// identical on every launch — no Math.random drift between two runs, which
// also keeps screenshots comparable.
function rng(seed) {
  let s = seed >>> 0;
  return () => (s = (Math.imul(s, 1664525) + 1013904223) >>> 0) / 4294967296;
}

function seedSmoke(host, delayVar, seed) {
  const rand = rng(seed);
  for (let i = 0; i < PUFF_COUNT; i++) {
    const rx = rand(), ry = rand(), rw = rand(), ro = rand();
    // Evenly around the ring, jittered — an even fan with no seam, but
    // never a clean circle of beads.
    const angle = (i / PUFF_COUNT + (rx - 0.5) * 0.06) * Math.PI * 2;
    const reach = 90 + ry * 260;        // % of the puff's own box
    const w = 10 + rw * 15;             // % of the stage
    const puff = document.createElement('span');
    puff.className = 'intro-puff';
    puff.style.setProperty('--dx', `${(Math.cos(angle) * reach).toFixed(1)}%`);
    // …plus a little lift: dust rises as it spreads.
    puff.style.setProperty('--dy', `${(Math.sin(angle) * reach * FLATTEN - 26).toFixed(1)}%`);
    puff.style.setProperty('--s', (1.6 + rw * 1.1).toFixed(2));
    puff.style.setProperty('--o', (0.17 + ro * 0.17).toFixed(3));
    puff.style.setProperty('--pd', `${PUFF_DUR}ms`);
    // The burst rides the impact moment from the CSS timeline; only the
    // per-puff stagger is added here.
    puff.style.setProperty('--od', `calc(var(${delayVar}) + ${Math.round(rx * 110)}ms)`);
    puff.style.width = `${w.toFixed(2)}%`;
    puff.style.marginLeft = `${(-w / 2).toFixed(2)}%`;
    host.appendChild(puff);
  }
}

/** Reads a millisecond custom property off the plate ('320ms' → 320). */
function ms(styles, prop) {
  const raw = styles.getPropertyValue(prop).trim();
  if (raw.endsWith('ms')) return parseFloat(raw);
  if (raw.endsWith('s')) return parseFloat(raw) * 1000;
  return parseFloat(raw) || 0;
}

// Tells the backend the stage is clear. Everything heavy — the model server,
// the scrapers, the editorial loops — waits for this, so the intro animates
// against an idle machine instead of a boot. Sent exactly once, on whichever
// end comes first (played out or skipped); the backend opens the gate on a
// timer anyway if this never arrives, so a broken intro delays the app, it
// never bricks it.
// The socket is connected right after this module initialises, so by the time
// the intro plays out it is long open — but the no-plate path reports
// immediately, possibly before the handshake. A short retry covers that;
// beyond it the backend's own timer takes over.
const READY_RETRY_MS = 200;
const READY_TRIES = 15;

function reportReady(socket, tries = READY_TRIES) {
  if (!socket) return;
  try {
    if (socket.send('boot-ready', {})) return;
  } catch (e) {
    console.error('[intro] boot-ready failed — backend falls back to its timer:', e);
    return;
  }
  if (tries > 0) setTimeout(() => reportReady(socket, tries - 1), READY_RETRY_MS);
}

export function initIntro(socket) {
  const plate = document.getElementById('intro');
  // No plate means no intro to wait for — release the backend right away
  // rather than making it sit out the failsafe.
  if (!plate) {
    reportReady(socket);
    return;
  }

  const smokeHands = plate.querySelector('.intro-smoke[data-burst="hands"]');
  const smokeDiamond = plate.querySelector('.intro-smoke[data-burst="diamond"]');
  if (smokeHands) seedSmoke(smokeHands, '--t-hands-impact', 0x5150);
  if (smokeDiamond) seedSmoke(smokeDiamond, '--t-diamond-impact', 0xd1a3);

  const styles = getComputedStyle(plate);
  const total = ms(styles, '--t-out') + ms(styles, '--d-out');
  const skipDur = ms(styles, '--d-skip');

  let done = false;
  const teardown = () => {
    if (done) return;
    done = true;
    window.removeEventListener('keydown', onKey, true);
    plate.remove();
    reportReady(socket);
  };

  const skip = () => {
    if (done || plate.classList.contains('is-skipping')) return;
    plate.classList.add('is-skipping');
    setTimeout(teardown, skipDur + 40);
  };

  const onKey = () => skip();

  plate.addEventListener('click', skip);
  window.addEventListener('keydown', onKey, true);

  // The plate leaves on its own even if nothing is clicked. A little slack
  // past the fade so the last frame is on screen before the node goes.
  setTimeout(teardown, total + 60);
}
