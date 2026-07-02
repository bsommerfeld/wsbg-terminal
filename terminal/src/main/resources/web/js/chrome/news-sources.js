// News-sources overlay — the provenance behind a headline row's "News" tag.
//
// A headline record can carry `newsRefs` (title/publisher/url/publishedAt of
// every external article its subject unit held at compose time). Clicking the
// row's News tag calls openNewsSources(h), which lists them: the article title
// as an external link (external-links.js routes the click to the OS browser)
// with publisher + publication time beneath. Shares the overlay skeleton +
// behaviour with the changelog (overlay.css / chrome/overlay.js).

import { attachOverlay } from './overlay.js';
import { currentLang } from '../i18n/i18n.js';

let overlayCtl = null;
let headlineEl = null;
let listEl = null;

export function initNewsSources() {
  const overlay = document.getElementById('news-sources-overlay');
  if (!overlay) return;
  overlayCtl = attachOverlay(overlay);
  headlineEl = overlay.querySelector('.news-src-headline');
  listEl = overlay.querySelector('.news-src-list');
}

/** Opens the overlay for one headline row (`h` = the socket headline JSON). */
export function openNewsSources(h) {
  if (!overlayCtl || !h || !Array.isArray(h.newsRefs) || h.newsRefs.length === 0) return;

  headlineEl.textContent = h.headline || '';

  // Newest first; items the source didn't timestamp sink to the end.
  const refs = h.newsRefs.slice().sort((a, b) => (b.publishedAt || 0) - (a.publishedAt || 0));
  listEl.replaceChildren(...refs.map(buildItem));

  overlayCtl.open();
}

function buildItem(ref) {
  const li = document.createElement('li');
  li.className = 'news-src-item';

  const a = document.createElement('a');
  a.className = 'news-src-link';
  a.href = ref.url;
  a.textContent = ref.title || ref.url;
  li.appendChild(a);

  const metaParts = [];
  if (ref.publisher) metaParts.push(ref.publisher);
  const when = fmtWhen(ref.publishedAt);
  if (when) metaParts.push(when);
  if (metaParts.length) {
    const meta = document.createElement('span');
    meta.className = 'news-src-meta';
    meta.textContent = metaParts.join(' · ');
    li.appendChild(meta);
  }
  return li;
}

// Same-day items show just the clock; older ones a locale-formatted date + time.
function fmtWhen(sec) {
  if (!sec) return '';
  const d = new Date(sec * 1000);
  const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  if (d.toDateString() === new Date().toDateString()) return time;
  const locale = currentLang() === 'de' ? 'de-DE' : 'en-US';
  return `${d.toLocaleDateString(locale, { day: '2-digit', month: '2-digit', year: 'numeric' })} ${time}`;
}
