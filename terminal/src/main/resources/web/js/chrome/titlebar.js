// Wires the title-bar window-control buttons (minimize / maximize / close).
//
// Everything else — dragging the window, edge-resize, Aero Snap and
// double-click-to-maximize — is handled NATIVELY by Windows. The native window
// proc (WindowsCustomChrome) reports the title-bar strip as HTCAPTION and the
// top edge as HTTOP via WM_NCHITTEST, and the single same-thread GLCanvas (OSR)
// forwards those hits through with HTTRANSPARENT. So there is no JS drag/resize
// emulation here anymore.
//
// macOS keeps its own native NSWindow caption (the HTML lights are hidden);
// Linux uses the native OS title bar (the whole HTML bar is hidden).

import { t } from '../i18n/i18n.js';

export function initTitlebar(socket) {
  document.querySelectorAll('.light').forEach(b => {
    b.addEventListener('click', e => {
      e.stopPropagation();
      socket.send('window', { command: b.dataset.window });
    });
  });
}

/**
 * The centred title is CONTEXT, not a constant:
 *   dashboard + grid overview → the app brand ("WSBG · Terminal"),
 *   focus view               → the focused widget's mark + name,
 *   settings view            → "Einstellungen".
 *
 * State is read straight off `.main` ([data-view], .settings-open, .focused)
 * instead of being tracked here, so every path that changes it — the gear, the
 * grid button closing the settings, Escape, a restored focus view — lands on
 * one render with no ordering assumptions between the listeners.
 *
 * The mark is a clone of the widget's GRID-CARD icon — the symbol the user just
 * clicked to open it, which for Fear & Greed is not the same drawing as the
 * widget-head glyph. The name carries the widget's own `data-i18n` key, so a
 * language switch re-translates it through applyStatic() like any other static
 * markup.
 */
export function initTitlebarTitle() {
  const main = document.querySelector('.main');
  const bar = document.querySelector('.tb-title');
  if (!main || !bar) return;

  const mark = bar.querySelector('.tb-mark');
  const brand = bar.querySelector('.tb-brand');
  const name = bar.querySelector('.tb-name');
  if (!mark || !brand || !name) return;

  /** Clears the mark back to an empty, hidden box. */
  function clearMark() {
    mark.hidden = true;
    mark.replaceChildren();
  }

  /**
   * Clones `node` with every id inside it renamed, and re-points the paint
   * attributes that referenced them. The Fear & Greed card icon carries a
   * <linearGradient id="…"> its own arc paints with: a plain clone would put a
   * second element with that id in the document, and `url(#id)` resolves to the
   * FIRST one — the copy would silently take over the original's paint.
   */
  function cloneUnique(node, suffix) {
    const copy = node.cloneNode(true);
    const renamed = new Map();
    for (const el of copy.querySelectorAll('[id]')) {
      renamed.set(el.id, `${el.id}-${suffix}`);
      el.id = `${el.id}-${suffix}`;
    }
    if (!renamed.size) return copy;
    for (const el of copy.querySelectorAll('*')) {
      for (const attr of ['fill', 'stroke', 'filter', 'clip-path', 'mask']) {
        const ref = /^url\(#(.+)\)$/.exec((el.getAttribute(attr) || '').trim());
        if (ref && renamed.has(ref[1])) el.setAttribute(attr, `url(#${renamed.get(ref[1])})`);
      }
    }
    return copy;
  }

  /** Shows `text` as the title; `key` (may be null) keeps it translatable. */
  function setName(key, text) {
    brand.hidden = true;
    if (key) name.setAttribute('data-i18n', key);
    else name.removeAttribute('data-i18n');
    name.textContent = key ? t(key, text) : text;
    name.hidden = false;
  }

  function showBrand() {
    clearMark();
    name.hidden = true;
    name.textContent = '';
    name.removeAttribute('data-i18n');
    brand.hidden = false;
  }

  function showWidget(widget) {
    const ident = widget.querySelector('.widget-head .ident');
    const src = ident && ident.querySelector('.ident-name');
    setName(src && src.getAttribute('data-i18n'), src ? src.textContent : '');

    clearMark();
    // The card's icon (the one child of .grid-hit-tag that is decoration, not
    // the name pill) — either a background-image span or an inline SVG.
    const icon = widget.querySelector('.grid-hit-tag > [aria-hidden="true"]');
    if (!icon) return;
    mark.appendChild(cloneUnique(icon, 'tb'));
    mark.hidden = false;
  }

  function render() {
    if (main.classList.contains('settings-open')) {
      clearMark();
      setName('settings.title', 'Einstellungen');
      return;
    }
    const focused = main.dataset.view === 'focus'
      ? main.querySelector(':scope > .widget.focused')
      : null;
    if (focused) showWidget(focused);
    else showBrand();
  }

  render();
  window.addEventListener('wsbg:viewchange', render);
  window.addEventListener('wsbg:settingsopen', render);
  window.addEventListener('wsbg:settingsclosed', render);
}
