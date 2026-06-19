// Bootstraps every subsystem. Wires the Socket -> renderers, sets up
// the chrome (titlebar / footer / settings), kicks the slide cycle.

import { Socket } from './bridge/socket.js';
import { initTitlebar } from './chrome/titlebar.js';
import { initTheme } from './chrome/theme.js';
import { initFooter } from './chrome/footer.js';
import { setDonationAdEnabled, setDonationStats } from './chrome/slider.js';
import { initDonate, setSupporter } from './chrome/donate.js';
import { initExternalLinks } from './chrome/external-links.js';
import { initKeyboardCopy } from './chrome/copy-fx.js';
import { renderHeadlines } from './widgets/reddit.js';
import { renderFjNews } from './widgets/financial-juice.js';
import { renderEurUsd } from './widgets/eurusd.js';
import { renderMood } from './widgets/mood.js';
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

// Market-mood badge (Reddit header): 24h sentiment split from the
// published headlines. Renders only once mood.js is switched live.
socket.on('market-mood', payload => {
  renderMood(document.getElementById('mood-badge'), payload);
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

// Donation gate: the footer ad only joins the slide rotation once the backend
// TimeTracker reports enough cumulative active time (~12 h). Payload:
// { unlocked, supporter, activeHours, openCount } — supporter gilds the heart,
// the stats personalise the banner copy ({hours}/{opens} placeholders).
socket.on('donation-gate', payload => {
  setDonationAdEnabled(!!(payload && payload.unlocked));
  setDonationStats(payload);
  setSupporter(!!(payload && payload.supporter));
});

initTheme();
initTitlebar(socket);
initFooter();
initDonate(socket);
initExternalLinks(socket);
initKeyboardCopy();

socket.connect();
