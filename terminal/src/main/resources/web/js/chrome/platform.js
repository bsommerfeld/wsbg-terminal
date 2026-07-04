// Platform detection → [data-platform] on <html>, which drives the title-bar
// split in CSS:
//   "mac"   — HTML titlebar over the native NSWindow traffic lights (title +
//             theme toggle); the OS draws the real lights top-left.
//   "win"   — custom flush titlebar; the native caption is stripped
//             (WindowsCustomChrome) so our HTML controls sit top-RIGHT and the
//             HTML traffic lights ARE the window buttons (wired to the window
//             command channel).
//   "other" — Linux: native OS title bar; the HTML titlebar is hidden and the
//             theme toggle moves to the footer.

export function applyPlatform() {
  const sig = (navigator.platform || '') + ' ' + (navigator.userAgent || '');
  const isMac = /mac|darwin/i.test(sig);
  const isWin = /win/i.test(sig);
  document.documentElement.dataset.platform = isMac ? 'mac' : (isWin ? 'win' : 'other');
}
