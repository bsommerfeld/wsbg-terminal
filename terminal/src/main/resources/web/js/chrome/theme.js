// Single-click theme toggle (dark ↔ light), persisted in localStorage.
//
// Replaces the previous settings flyout which carried this toggle plus
// a density picker and a handful of feature switches. Those settings
// are now hard-on; only the theme is user-controllable.

const STORAGE_KEY = 'wsbg.theme.v1';

export function initTheme() {
  restore();

  const btn = document.getElementById('btn-theme');
  if (!btn) return;

  btn.addEventListener('click', () => {
    const current = document.documentElement.dataset.theme || 'dark';
    const next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem(STORAGE_KEY, next); } catch (_) {}
  });
}

function restore() {
  let stored;
  try { stored = localStorage.getItem(STORAGE_KEY); } catch (_) { stored = null; }
  if (stored === 'dark' || stored === 'light') {
    document.documentElement.setAttribute('data-theme', stored);
  }
}
