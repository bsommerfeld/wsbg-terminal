// Bootstraps every subsystem. Wires the Socket -> renderers and sets up
// the chrome (titlebar / footer / settings).

import { Socket } from './bridge/socket.js';
import { applyPlatform } from './chrome/platform.js';
import { initTitlebar } from './chrome/titlebar.js';
import { initIntro } from './chrome/intro.js';
import { initTheme, setSystemAppearance } from './chrome/theme.js';
import { initIdle } from './chrome/idle.js';
import { initSettings } from './chrome/settings.js';
import { initChangelog } from './chrome/changelog.js';
import { initNewsSources } from './chrome/news-sources.js';
import { initAiNotice } from './chrome/ai-notice.js';
import { initAiStatus, applyAiStatus } from './chrome/ai-status.js';
import { initHeadlineFilter } from './chrome/filter-popover.js';
import { initWidgetNav } from './chrome/widget-nav.js';
import { initWidgetRail } from './chrome/widget-rail.js';
import { initFooter } from './chrome/footer.js';
import { initExternalLinks } from './chrome/external-links.js';
import { initKeyboardCopy } from './chrome/copy-fx.js';
import { renderHeadlines, appendHeadlines, initHeadlineScroll, initUnreadPortals } from './widgets/reddit.js';
import { initHeadlineSearch, onArchiveResults } from './widgets/headline-search.js';
import { renderFjNews } from './widgets/financial-juice.js';
import { renderEurUsd } from './widgets/eurusd.js';
import { renderEurUsdDetail } from './widgets/eurusd-detail.js';
import { renderFearGreed } from './widgets/fear-greed.js';
import { renderFearGreedDetail } from './widgets/fg-detail.js';
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
  // One payload, two views (like fear-greed): the FJ header badge AND the
  // EUR/USD detail widget (grid/focus) render from the same quote.
  { topic: 'eurusd',         relang: true, render: p => {
      renderEurUsd(document.getElementById('eurusd-badge'), p);
      renderEurUsdDetail(document.getElementById('eurusd-detail'), p);
    } },
  { topic: 'market-hours',                 render: p => setMarketCalendar(p) },
  // One payload, two views: the header badge's mini gauge AND the detail
  // widget (grid/focus) render from the same reading.
  { topic: 'fear-greed',     relang: true, render: p => {
      renderFearGreed(document.getElementById('fear-greed-badge'), p);
      renderFearGreedDetail(document.getElementById('fg-detail'), p);
    } },
  { topic: 'reddit-status',  relang: true, render: p => applyRedditStatus(p) },
  // AI endpoint health — the red titlebar alarm. relang: the overlay copy is
  // translated, so a live language switch must re-paint it from the cache.
  { topic: 'ai-status',      relang: true, render: p => applyAiStatus(p) },
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

// The wire ships in full on connect ('headlines') and then only grows
// ('headlines-add'), so the delta appends instead of replacing. It rides beside
// the WIDGETS manifest, not in it: that table maps a topic to the COMPLETE state
// of a widget, and a delta is by definition not that. `last.headlines` is kept
// whole all the same — a live language switch re-paints from it.
initHeadlineScroll(redditBody);
socket.on('headlines-add', payload => {
  const items = payload ?? [];
  if (!items.length) return;
  last['headlines'] = items.concat(last['headlines'] ?? []);
  appendHeadlines(redditBody, items);
});

socket.on('archive-results', payload => {
  if (!payload) return;
  onArchiveResults(payload); // search vocabulary + result sets (headline-search.js)
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

// One broken widget must never blank the whole terminal (live-observed
// 2026-07-13: a single ReferenceError in one widget's init aborted the module
// body — every later init never ran, completely empty UI). A failed init
// costs its widget, logs loudly, and the rest of the terminal keeps living.
function safeInit(name, fn) {
  try {
    fn();
  } catch (e) {
    console.error(`[init] ${name} failed — widget dead, terminal continues:`, e);
  }
}

// First: everything that loops (the unread portals) and the headline
// read-tracker ask this module whether anyone is at the desk.
safeInit('idle', () => initIdle());
safeInit('theme', () => initTheme());
safeInit('settings', () => initSettings(socket));
// Before the overlays/popovers: their document-level Escape handlers close
// their own layer, and widget-nav's (registered here, i.e. earlier) must see
// that layer still open to know the key was theirs — otherwise one Escape
// would close an overlay AND navigate out of the focus view.
safeInit('widget-nav', () => initWidgetNav());
safeInit('changelog', () => initChangelog(socket));
safeInit('news-sources', () => initNewsSources());
safeInit('ai-notice', () => initAiNotice());
safeInit('ai-status', () => initAiStatus());
safeInit('headline-filter', () => initHeadlineFilter());
safeInit('widget-rail', () => initWidgetRail(socket));
// AFTER the rail: the search button's own click handler must run after the
// rail's generic popup toggle (same-element listener order) to see the new state.
safeInit('headline-search', () => initHeadlineSearch(socket));
safeInit('unread-portals', () => initUnreadPortals());
safeInit('titlebar', () => initTitlebar(socket));
safeInit('footer', () => initFooter());
safeInit('external-links', () => initExternalLinks(socket));
safeInit('keyboard-copy', () => initKeyboardCopy());
// Last: the intro plate is already on screen from the markup, this only seeds
// its dust and arms the skip - and it must not sit in front of anything else
// that still needs to initialise.
safeInit('intro', () => initIntro(socket));

socket.connect();
