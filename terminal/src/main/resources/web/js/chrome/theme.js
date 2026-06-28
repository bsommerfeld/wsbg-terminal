// Theme state (dark ↔ light), persisted in localStorage.
//
// The toggle now lives inside the Settings view (settings.js drives the
// day/night switch + the "follow system" checkbox); this module owns the
// state and applies it. When "follow system" is on, the OS colour-scheme
// wins and the explicit choice is ignored until it's turned off again.

const STORAGE_KEY = 'wsbg.theme.v1';
const FOLLOW_KEY = 'wsbg.theme.follow-system.v1';
const mql = window.matchMedia('(prefers-color-scheme: dark)');

// The host OS appearance, pushed from Java (OsAppearancePublisher). This is the
// AUTHORITATIVE signal while following the system: the browser runs off-screen
// (OSR), so `mql`/`prefers-color-scheme` can't see the real macOS theme and never
// fires on a scheduled (dusk) switch. We only fall back to `mql` until the first
// push arrives.
let osAppearance = null; // 'dark' | 'light' | null

function systemPrefersDark() {
  if (osAppearance) return osAppearance === 'dark';
  return mql.matches;
}

function read(key) {
  try { return localStorage.getItem(key); } catch (_) { return null; }
}
function write(key, value) {
  try { localStorage.setItem(key, value); } catch (_) {}
}

function apply(theme) {
  document.documentElement.setAttribute('data-theme', theme === 'light' ? 'light' : 'dark');
}

export function isFollowingSystem() {
  return read(FOLLOW_KEY) === '1';
}

export function currentTheme() {
  return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark';
}

/** Explicit user choice (only meaningful while NOT following the system). */
export function setTheme(theme) {
  write(STORAGE_KEY, theme);
  if (!isFollowingSystem()) apply(theme);
}

/** Toggle convenience for the day/night switch. */
export function toggleTheme() {
  setTheme(currentTheme() === 'dark' ? 'light' : 'dark');
}

/** Turn "follow system" on/off; applies the resulting theme immediately. */
export function setFollowSystem(on) {
  write(FOLLOW_KEY, on ? '1' : '0');
  if (on) apply(systemPrefersDark() ? 'dark' : 'light');
  else apply(read(STORAGE_KEY) === 'light' ? 'light' : 'dark');
}

/**
 * The host OS appearance, pushed from Java. Authoritative while following the
 * system (see osAppearance above); re-applies immediately if we're following.
 */
export function setSystemAppearance(mode) {
  osAppearance = (mode === 'dark' || mode === 'light') ? mode : null;
  if (isFollowingSystem()) apply(systemPrefersDark() ? 'dark' : 'light');
}

export function initTheme() {
  if (isFollowingSystem()) apply(systemPrefersDark() ? 'dark' : 'light');
  else { const s = read(STORAGE_KEY); if (s === 'dark' || s === 'light') apply(s); }

  // Secondary signal: react to in-browser colour-scheme changes while following
  // the system. Unreliable under OSR (hence the Java push), but harmless to keep.
  mql.addEventListener('change', () => {
    if (isFollowingSystem() && !osAppearance) apply(mql.matches ? 'dark' : 'light');
  });
}
