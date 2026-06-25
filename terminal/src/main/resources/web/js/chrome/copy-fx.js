// Keyboard copy of the current text selection. (The on-brand green-checkmark
// confirmation animation was removed — it read as dated. Copy is now silent.)
//
// JCEF runs windowed Chromium, which on macOS only recognises the native Cmd+C
// copy chord — Ctrl+C does nothing there. Windows/Linux users (and German muscle
// memory on mac) expect Ctrl+C to work. So we handle the chord ourselves: on
// Ctrl+C OR Cmd+C, if there's a non-empty selection outside an editable field,
// copy it and preventDefault so the native handler doesn't also fire.
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
  });
}

async function copyToClipboard(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text);
  }
  // Fallback for environments without the async Clipboard API: stage a hidden
  // textarea, select, execCommand('copy'). JCEF supports the modern API so this
  // is essentially dead code, kept for safety.
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try { document.execCommand('copy'); }
  finally { ta.remove(); }
}
