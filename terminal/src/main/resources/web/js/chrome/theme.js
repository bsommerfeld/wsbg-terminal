// Theme state (dark ↔ light), persisted in localStorage.
//
// The toggle now lives inside the Settings view (settings.js drives the
// day/night switch + the "follow system" checkbox); this module owns the
// state and applies it. When "follow system" is on, the OS colour-scheme
// wins and the explicit choice is ignored until it's turned off again.

const STORAGE_KEY = 'wsbg.theme.v1';
const FOLLOW_KEY = 'wsbg.theme.follow-system.v1';
const mql = window.matchMedia('(prefers-color-scheme: dark)');

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
  if (on) apply(mql.matches ? 'dark' : 'light');
  else apply(read(STORAGE_KEY) === 'light' ? 'light' : 'dark');
}

export function initTheme() {
  if (isFollowingSystem()) apply(mql.matches ? 'dark' : 'light');
  else { const s = read(STORAGE_KEY); if (s === 'dark' || s === 'light') apply(s); }

  // React to OS colour-scheme changes while following the system.
  mql.addEventListener('change', () => {
    if (isFollowingSystem()) apply(mql.matches ? 'dark' : 'light');
  });
}
