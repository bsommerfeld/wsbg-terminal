// "Was hat sich geändert" overlay — release notes after a fresh update.
//
// The backend (ChangelogBridge) pushes a `changelog` payload once when the
// installed version is ahead of the last one whose notes were seen:
// { show, current, releases:[{tag, name, publishedAt, body}] }. The overlay
// lays a flat panel over ~3/4 of the app; the header breadcrumb switches
// between releases (the selected one reads gold and a touch larger), the body
// renders the release Markdown. Closing (X / Escape / backdrop) reports
// `seen` so the overlay stays away until the next update.

import { renderMarkdown } from '../format/markdown.js';

// Release bodies carry build badges + an update hint above the actual notes
// and a download-CTA footer below them; both are release-page chrome, not
// content — inside the running app the installer links are pointless. Cut
// everything up to and including the notes heading (the overlay header owns
// the title) and everything from the download heading on. Both markers are
// pinned to the release workflow's templates (release.yml).
const NOTES_HEADING = /^#\s*Was hat sich geändert\??\s*$/mi;
const DOWNLOAD_HEADING = /^##\s*Holt es euch in den Käfig\s*$/mi;

function notesOf(body) {
  let md = body;
  const start = NOTES_HEADING.exec(md);
  if (start) md = md.slice(start.index + start[0].length);
  const end = DOWNLOAD_HEADING.exec(md);
  if (end) md = md.slice(0, end.index);
  // The workflow separates the footer with a rule — as the new last line it
  // would render a dangling <hr>, so it goes too.
  return md.replace(/\n\s*(-{3,}|\*{3,}|_{3,})\s*$/, '\n');
}

export function initChangelog(socket) {
  const overlay = document.getElementById('changelog-overlay');
  if (!overlay) return;
  const crumbs = overlay.querySelector('.changelog-crumbs');
  const body = overlay.querySelector('.changelog-body');
  const scroll = overlay.querySelector('.changelog-scroll');
  const closeBtn = overlay.querySelector('.changelog-close');

  let releases = [];
  let selected = null;
  let closeTimer = null;

  function renderCrumbs() {
    // Oldest → newest, so the freshest release always sits on the right.
    crumbs.replaceChildren(...releases.slice().reverse().map(r => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'crumb' + (r.tag === selected ? ' current' : '');
      b.textContent = r.tag;
      if (r.name) b.title = r.name;
      b.addEventListener('click', () => select(r.tag));
      return b;
    }));
  }

  function select(tag) {
    if (tag === selected) return;
    selected = tag;
    renderCrumbs();
    const rel = releases.find(r => r.tag === tag);
    body.innerHTML = rel ? renderMarkdown(notesOf(rel.body)) : '';
    scroll.scrollTop = 0;
  }

  function close() {
    if (overlay.hidden || overlay.classList.contains('closing')) return;
    socket.send('changelog', { command: 'seen' });
    // Clap-shut exit: .closing plays the CSS fade/shrink, then the overlay
    // actually hides. Timeout instead of transitionend — robust even if the
    // transition is skipped (e.g. reduced painting).
    overlay.classList.add('closing');
    closeTimer = setTimeout(() => {
      overlay.hidden = true;
      overlay.classList.remove('closing');
    }, 240);
  }

  closeBtn.addEventListener('click', close);
  overlay.addEventListener('click', e => { if (e.target === overlay) close(); });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !overlay.hidden) close();
  });

  socket.on('changelog', payload => {
    if (!payload || !payload.show || !Array.isArray(payload.releases) || !payload.releases.length) return;
    releases = payload.releases;
    selected = null;
    const cur = releases.some(r => r.tag === payload.current) ? payload.current : releases[0].tag;
    select(cur);
    // A push mid-close must not be swallowed by the pending hide.
    clearTimeout(closeTimer);
    overlay.classList.remove('closing');
    overlay.hidden = false;
  });
}
