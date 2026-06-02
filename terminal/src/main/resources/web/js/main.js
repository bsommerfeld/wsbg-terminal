// Bootstraps every subsystem. Wires the Socket -> renderers, sets up
// the chrome (titlebar / footer / settings), kicks the slide cycle.

import { Socket } from './bridge/socket.js';
import { initTitlebar } from './chrome/titlebar.js';
import { initTheme } from './chrome/theme.js';
import { initFooter } from './chrome/footer.js';
import { initKeyboardCopy } from './chrome/copy-fx.js';
import { renderHeadlines } from './widgets/reddit.js';
import { renderFjNews } from './widgets/financial-juice.js';
import { renderEurUsd } from './widgets/eurusd.js';
import { setMarketCalendar } from './markets/state.js';

// [data-platform] drives the title-bar split in CSS:
//   "mac"   — HTML titlebar over the native NSWindow traffic lights (title +
//             theme toggle); the OS draws the real lights top-left.
//   "win"   — custom flush titlebar; the native caption is stripped
//             (WindowsCustomChrome) so our HTML controls sit top-RIGHT and the
//             HTML traffic lights ARE the window buttons (wired to the window
//             command channel).
//   "other" — Linux: native OS title bar; the HTML titlebar is hidden and the
//             theme toggle moves to the footer.
const sig = (navigator.platform || '') + ' ' + (navigator.userAgent || '');
const isMac = /mac|darwin/i.test(sig);
const isWin = /win/i.test(sig);
document.documentElement.dataset.platform = isMac ? 'mac' : (isWin ? 'win' : 'other');

const wsPort = new URLSearchParams(location.search).get('ws');
const socket = new Socket(`ws://127.0.0.1:${wsPort}`);

socket.on('headlines', payload => {
  renderHeadlines(document.querySelector('#widget-reddit [data-rows]'), payload);
});

socket.on('fj-news', payload => {
  renderFjNews(document.querySelector('#widget-fj [data-rows]'), payload);
});

socket.on('eurusd', payload => {
  renderEurUsd(document.getElementById('eurusd-badge'), payload);
});

socket.on('market-hours', payload => {
  setMarketCalendar(payload);
});

// Reddit scraper health — toggles the status label next to the LIVE
// indicator. Payload: { state: 'OK' | 'DEGRADED', degradedSinceMs }.
// TODO(oauth-login): when payload.suggestLogin is true (future flag,
// set server-side once degraded > N min), swap the static label for
// a clickable "Sign in to Reddit" CTA wired to the OAuth flow.
socket.on('reddit-status', payload => {
  const el = document.getElementById('reddit-status');
  if (!el || !payload) return;
  const state = payload.state || 'OK';
  el.dataset.state = state;
  // The LIVE indicator can't claim liveness while the pull is dead, so
  // swap its label to "Defekt" alongside the CSS dot/colour change.
  const live = el.parentElement && el.parentElement.querySelector('.live');
  if (live) live.textContent = state === 'DEGRADED' ? 'Defekt' : 'Live';
});

initTheme();
initTitlebar(socket);
initFooter();
initKeyboardCopy();

socket.connect();
