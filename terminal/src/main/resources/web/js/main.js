// Bootstraps every subsystem. Wires the Socket -> renderers, sets up
// the chrome (titlebar / footer / settings), kicks the slide cycle.

import { Socket } from './bridge/socket.js';
import { initTitlebar } from './chrome/titlebar.js';
import { initResize } from './chrome/resize.js';
import { initTheme } from './chrome/theme.js';
import { initFooter } from './chrome/footer.js';
import { initKeyboardCopy } from './chrome/copy-fx.js';
import { renderHeadlines } from './widgets/reddit.js';
import { renderFjNews } from './widgets/financial-juice.js';
import { renderEurUsd } from './widgets/eurusd.js';
import { setMarketCalendar } from './markets/state.js';

// macOS keeps its native NSWindow chrome (traffic lights + drag region)
// because JCEF reparenting requires a standard NSWindow. The CSS below
// uses [data-platform] to hide the HTML fake lights on macOS and to
// reserve space for the native ones; on Windows/Linux the frame is
// undecorated and the HTML lights are the only chrome.
const isMac = /mac|darwin/i.test(navigator.platform || navigator.userAgent || '');
document.documentElement.dataset.platform = isMac ? 'mac' : 'other';

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
initResize(socket);
initFooter();
initKeyboardCopy();

socket.connect();
