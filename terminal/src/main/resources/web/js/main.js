// Bootstraps every subsystem. Wires the Socket -> renderers, sets up
// the chrome (titlebar / footer / settings), kicks the slide cycle.

import { Socket } from './bridge/socket.js';
import { initTitlebar } from './chrome/titlebar.js';
import { initTheme, setSystemAppearance } from './chrome/theme.js';
import { initSettings } from './chrome/settings.js';
import { initFooter } from './chrome/footer.js';
import { setDonationStats } from './chrome/slider.js';
import { initDonate } from './chrome/donate.js';
import { initExternalLinks } from './chrome/external-links.js';
import { initKeyboardCopy } from './chrome/copy-fx.js';
import { renderHeadlines, initHeadlineScroll, appendArchivePage } from './widgets/reddit.js';
import { renderFjNews } from './widgets/financial-juice.js';
import { renderEurUsd } from './widgets/eurusd.js';
import { renderFearGreed } from './widgets/fear-greed.js';
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

const redditBody = document.querySelector('#widget-reddit [data-rows]');
socket.on('headlines', payload => {
  renderHeadlines(redditBody, payload);
});
// Scroll-back: load older archived headlines as the user scrolls past the live wire.
initHeadlineScroll(redditBody, socket);
socket.on('archive-results', payload => {
  if (payload && payload.command === 'page') appendArchivePage(payload.items);
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

// Fear & Greed gauge (Reddit header): whole-market sentiment.
socket.on('fear-greed', payload => {
  renderFearGreed(document.getElementById('fear-greed-badge'), payload);
});

// Reddit scraper health — lives directly on the Reddit LIVE indicator
// (the standalone "RATE LIMITED" chip was dropped; "Defekt" does the
// job and the header slot belongs to the mood badge). Payload:
// { state: 'OK' | 'DEGRADED', degradedSinceMs }.
// TODO(oauth-login): when payload.suggestLogin is true (future flag,
// set server-side once degraded > N min), swap the static label for
// a clickable "Sign in to Reddit" CTA wired to the OAuth flow.
socket.on('reddit-status', payload => {
  const live = document.querySelector('#widget-reddit .live');
  if (!live || !payload) return;
  const state = payload.state || 'OK';
  live.dataset.state = state;
  live.textContent = state === 'DEGRADED' ? 'Defekt' : 'Live';
});

// Donation stats: the footer banner runs unconditionally now; this payload only
// carries the reciprocity figures the copy personalises with. Payload:
// { activeHours, openCount } → the {hours}/{opens} placeholders.
socket.on('donation-stats', payload => {
  setDonationStats(payload);
});

// Host OS dark/light appearance, detected on the Java side. Authoritative for
// "follow system" because the OSR browser can't read the real macOS theme itself.
socket.on('os-appearance', payload => {
  if (payload) setSystemAppearance(payload.mode);
});

initTheme();
initSettings(socket);
initTitlebar(socket);
initFooter();
initDonate();
initExternalLinks(socket);
initKeyboardCopy();

socket.connect();
