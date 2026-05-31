// Keyboard copy of the current text selection, with an on-brand
// confirmation checkmark.
//
// The confirmation DOM lives in a single fixed-position fx-layer overlay
// with pointer-events: none, so it never interferes with normal
// interaction. The checkmark removes itself on animationend.

const SVG_NS = 'http://www.w3.org/2000/svg';

// JCEF runs windowed Chromium, which on macOS only recognises the native
// Cmd+C copy chord — STRG+C (Ctrl+C) does nothing there. Windows/Linux
// users (and German muscle memory on mac) expect Ctrl+C to work. So we
// handle the chord ourselves: on Ctrl+C OR Cmd+C, if there's a non-empty
// selection outside an editable field, copy it and preventDefault so the
// native handler (where it exists) doesn't also fire and double-copy.
export function initKeyboardCopy() {
  document.addEventListener('keydown', event => {
    const isCopyChord =
      (event.key === 'c' || event.key === 'C') && (event.ctrlKey || event.metaKey);
    if (!isCopyChord || event.altKey) return;

    // Editable fields own their own copy semantics — leave them alone.
    const active = document.activeElement;
    if (active && (active.tagName === 'INPUT'
        || active.tagName === 'TEXTAREA'
        || active.isContentEditable)) {
      return;
    }

    const selection = window.getSelection();
    const text = selection ? selection.toString() : '';
    if (!text.trim()) return;

    event.preventDefault();
    copyToClipboard(text).catch(err => {
      console.warn('Clipboard write failed:', err);
    });
    confirmSelectionCopy(selection);
  });
}

// On-brand confirmation: draw a green checkmark at the end of the
// selection, so a keyboard copy reads as deliberately acknowledged
// rather than silent.
function confirmSelectionCopy(selection) {
  if (!selection || selection.rangeCount === 0) return;
  const range = selection.getRangeAt(selection.rangeCount - 1);
  const rects = range.getClientRects();
  const last = rects[rects.length - 1];
  if (!last) return;
  drawTick(ensureFxLayer(), last.right, last.top + last.height / 2);
}

async function copyToClipboard(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text);
  }
  // Fallback for environments without the async Clipboard API: stage
  // a hidden textarea, select, execCommand('copy'). JCEF supports the
  // modern API so this is essentially dead code, kept for safety.
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try { document.execCommand('copy'); }
  finally { ta.remove(); }
}

function drawTick(layer, cx, cy) {
  const size = 22;
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', 'copy-tick');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width',  size);
  svg.setAttribute('height', size);
  svg.style.left = `${cx - size / 2}px`;
  svg.style.top  = `${cy - size / 2}px`;
  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('d', 'M5 12 L10 17 L19 7');
  svg.appendChild(path);
  svg.addEventListener('animationend', e => {
    // The svg has two animations (fade + draw). Wait for the longer
    // (fade), which is on the svg itself, before removing.
    if (e.target === svg) svg.remove();
  });
  layer.appendChild(svg);
}

function ensureFxLayer() {
  let layer = document.getElementById('fx-layer');
  if (!layer) {
    layer = document.createElement('div');
    layer.id = 'fx-layer';
    document.body.appendChild(layer);
  }
  return layer;
}
