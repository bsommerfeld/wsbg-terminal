// External links — anchors that leave the terminal page.
//
// The embedded Chromium runs off-screen and swallows target="_blank" popups:
// no popup window exists in OSR mode, and CEF-side popup interception proved
// unreliable live. So the page owns the behaviour instead: every click on an
// external http(s) anchor is cancelled and routed over the socket
// ({type:'open-external'}), where Java opens the OS default browser.
//
// Registered in the CAPTURE phase so the click is claimed before any other
// handler runs; other document-level listeners still fire — preventDefault
// only cancels the navigation.
//
// The MIDDLE button needs its own listener: Chromium fires `auxclick` (not
// `click`) for non-primary buttons, so a click-only handler let the middle
// click through to Chromium's "open in a new tab" default. There is no tab in
// OSR — the new-tab navigation lands in the MAIN frame and the terminal page
// navigates away to the target site. Same treatment, second event name.

export function initExternalLinks(socket) {
  const route = e => {
    const a = e.target.closest('a[href]');
    if (!a) return;
    const href = a.href || '';
    if (!/^https?:\/\//i.test(href) || href.startsWith('http://127.0.0.1')) return;
    e.preventDefault();
    socket.send('open-external', { url: href });
  };
  document.addEventListener('click', route, true);
  // button 1 = middle. Right-click (2) keeps the context menu, the mouse's
  // back/forward buttons (3/4) never open a link.
  document.addEventListener('auxclick', e => { if (e.button === 1) route(e); }, true);
}
