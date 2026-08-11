// The AFK gate for the terminal's few looping animations.
//
// Under software OSR every animation frame forces a paint whose damage unions
// with everything else moving on screen — a loop nobody is watching is pure
// cost. This module answers one question for the whole UI: is anyone there?
//
// "Nobody there" = the window is hidden/blurred, OR five minutes have passed
// without a single pointer, key, wheel or scroll event. It is published as
// `data-idle` on <html>, so CSS can park a keyframe with one
// `animation-play-state: paused` rule, and as `onIdleChange` for the JS that
// needs to stop counting (the headline read-tracker must not mark rows read
// while the user is away from the desk).

const IDLE_MS = 5 * 60 * 1000;
// Pointer moves fire dozens of times a second; re-arming the timer on every
// one of them is wasted work when the timeout is measured in minutes.
const THROTTLE_MS = 2000;

const EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'scroll', 'touchstart'];

let idle = false;
let timer = null;
let lastPoke = 0;
const listeners = new Set();

function publish(next) {
  if (next === idle) return;
  idle = next;
  document.documentElement.toggleAttribute('data-idle', idle);
  for (const fn of listeners) {
    try { fn(idle); } catch (e) { console.error('[idle] listener failed:', e); }
  }
}

function arm() {
  clearTimeout(timer);
  timer = setTimeout(() => publish(true), IDLE_MS);
}

/** Someone is here: wake up and restart the five minutes. */
function poke() {
  // A hidden window can still receive events (a scroll settling out); staying
  // idle while in the background is the whole point.
  if (document.hidden) return;
  const now = performance.now();
  if (idle) publish(false);
  else if (now - lastPoke < THROTTLE_MS) return;
  lastPoke = now;
  arm();
}

export function initIdle() {
  for (const type of EVENTS) {
    // Capture + passive: the wire's own scroll handlers must not be able to
    // swallow the signal, and none of this may ever block a gesture.
    window.addEventListener(type, poke, { capture: true, passive: true });
  }
  window.addEventListener('focus', poke);
  window.addEventListener('blur', () => publish(true));
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) publish(true); else poke();
  });
  if (document.hidden) publish(true); else arm();
}

/** True while the window is in the background or the desk has been quiet. */
export function isIdle() {
  return idle;
}

/** Subscribes to the awake ⇄ idle switch. Returns the unsubscribe. */
export function onIdleChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
