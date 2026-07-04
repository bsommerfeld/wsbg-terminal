// Front-end i18n layer.
//
// The whole UI used to be hardcoded German; this module makes every visible
// string key-resolved so the "Anzeigesprache" setting takes effect LIVE (no
// restart). German is the source language, English the translation; an unknown
// key falls back to the German string, then to the key itself.
//
// Two halves:
//   1. Static markup — elements carry `data-i18n` (textContent) or
//      `data-i18n-<attr>` (title / aria-label / placeholder). applyStatic()
//      rewrites them in place; setLang() calls it on every change.
//   2. Dynamic strings — JS renderers call t('key') at render time. On a
//      language change we fire `wsbg:languagechange` so those renderers can
//      re-paint from their cached payload (main.js / footer.js listen).
//
// The dictionary DATA lives in dict.js; this module is the resolution engine.

import { DICT } from './dict.js';

const SUPPORTED = ['de', 'en'];
const DEFAULT_LANG = 'de';
let lang = DEFAULT_LANG;

/** The currently active language code ('de' | 'en'). */
export function currentLang() {
  return lang;
}

/**
 * Translates a key. Falls back to the German string, then to `fallback`, then
 * to the raw key — so a missing translation degrades gracefully instead of
 * hard-failing (the whole UI must keep rendering).
 */
export function t(key, fallback) {
  const table = DICT[lang] || DICT[DEFAULT_LANG];
  if (key in table) return table[key];
  if (key in DICT[DEFAULT_LANG]) return DICT[DEFAULT_LANG][key];
  return fallback != null ? fallback : key;
}

const ATTRS = ['title', 'aria-label', 'placeholder'];

/** Rewrites every [data-i18n] / [data-i18n-<attr>] element under `root`. */
export function applyStatic(root = document) {
  root.querySelectorAll('[data-i18n]').forEach(el => {
    el.textContent = t(el.getAttribute('data-i18n'));
  });
  for (const attr of ATTRS) {
    root.querySelectorAll(`[data-i18n-${attr}]`).forEach(el => {
      el.setAttribute(attr, t(el.getAttribute(`data-i18n-${attr}`)));
    });
  }
}

/**
 * Switches the active language, rewrites all static markup, and broadcasts
 * `wsbg:languagechange` so dynamic renderers can re-paint. A no-op (bar a
 * re-apply) when the language is unchanged or unsupported.
 */
export function setLang(next) {
  if (!SUPPORTED.includes(next)) return;
  const changed = next !== lang;
  lang = next;
  document.documentElement.lang = lang;
  document.documentElement.dataset.lang = lang;
  applyStatic();
  if (changed) {
    window.dispatchEvent(new CustomEvent('wsbg:languagechange', { detail: { lang } }));
  }
}
