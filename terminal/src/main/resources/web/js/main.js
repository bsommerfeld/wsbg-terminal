// Bootstraps every subsystem. Wires the Socket -> renderers, sets up
// the chrome (titlebar / footer / settings), kicks the slide cycle.

import { Socket } from './bridge/socket.js';
import { applyPlatform } from './chrome/platform.js';
import { initTitlebar } from './chrome/titlebar.js';
import { initTheme, setSystemAppearance } from './chrome/theme.js';
import { initSettings } from './chrome/settings.js';
import { initChangelog } from './chrome/changelog.js';
import { initNewsSources } from './chrome/news-sources.js';
import { initAiNotice } from './chrome/ai-notice.js';
import { initHeadlineFilter } from './chrome/filter-popover.js';
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
import { t } from './i18n/i18n.js';

applyPlatform();

const wsPort = new URLSearchParams(location.search).get('ws');
const socket = new Socket(`ws://127.0.0.1:${wsPort}`);

const redditBody = document.querySelector('#widget-reddit [data-rows]');
const fjBody = document.querySelector('#widget-fj [data-rows]');

// Reddit scraper health — lives directly on the Reddit LIVE indicator
// (the standalone "RATE LIMITED" chip was dropped; "Defekt" does the
// job and the header slot belongs to the mood badge). Payload:
// { state: 'OK' | 'DEGRADED', degradedSinceMs }.
// TODO(oauth-login): when payload.suggestLogin is true (future flag,
// set server-side once degraded > N min), swap the static label for
// a clickable "Sign in to Reddit" CTA wired to the OAuth flow.
function applyRedditStatus(payload) {
  const live = document.querySelector('#widget-reddit .live');
  if (!live || !payload) return;
  const state = payload.state || 'OK';
  live.dataset.state = state;
  live.textContent = state === 'DEGRADED' ? t('common.degraded') : t('common.live');
}

// Declarative topic → widget manifest. One loop registers every `socket.on`,
// one loop re-paints the language-sensitive widgets from their cached payload on
// `wsbg:languagechange`. Adding a widget = one row here, no drift between the two
// concerns. `relang` marks widgets whose rendered strings are translated (so a
// live language switch must re-paint them). The topic literals are the socket
// contract shared with the Java publishers — do not rename.
const WIDGETS = [
  { topic: 'headlines',      relang: true, render: p => renderHeadlines(redditBody, p ?? []) },
  { topic: 'fj-news',        relang: true, render: p => renderFjNews(fjBody, p ?? []) },
  { topic: 'eurusd',                       render: p => renderEurUsd(document.getElementById('eurusd-badge'), p) },
  { topic: 'market-hours',                 render: p => setMarketCalendar(p) },
  { topic: 'fear-greed',     relang: true, render: p => renderFearGreed(document.getElementById('fear-greed-badge'), p) },
  { topic: 'reddit-status',  relang: true, render: p => applyRedditStatus(p) },
  { topic: 'donation-stats',               render: p => setDonationStats(p) },
  { topic: 'os-appearance',                render: p => { if (p) setSystemAppearance(p.mode); } },
];

// Last payload per topic, so a live language switch can re-paint the dynamic
// content (translated strings live inside these renderers) without waiting for
// the next server push. headlines/fj seed to [] so they always paint (matching
// the empty-state render); the rest stay unset until their first push.
const last = { 'headlines': [], 'fj-news': [] };

for (const w of WIDGETS) {
  socket.on(w.topic, payload => { last[w.topic] = payload; w.render(payload); });
}

// Scroll-back: load older archived headlines as the user scrolls past the live wire.
initHeadlineScroll(redditBody, socket);
socket.on('archive-results', payload => {
  if (payload && payload.command === 'page') appendArchivePage(payload.items);
});

// Live language switch: setLang() has already rewritten the static markup;
// re-render the language-sensitive widgets from their last payload so their
// translated strings update too. `last[topic] != null` guards the widgets whose
// re-paint is conditional on having received a (non-null) payload.
window.addEventListener('wsbg:languagechange', () => {
  for (const w of WIDGETS) {
    if (w.relang && last[w.topic] != null) w.render(last[w.topic]);
  }
});

initTheme();
initSettings(socket);
initChangelog(socket);
initNewsSources();
initAiNotice();
initHeadlineFilter();
initTitlebar(socket);
initFooter();
initDonate();
initExternalLinks(socket);
initKeyboardCopy();

socket.connect();
