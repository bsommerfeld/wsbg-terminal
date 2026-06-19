// External links — anchors that leave the terminal page.
//
// The embedded Chromium runs off-screen and swallows target="_blank" popups:
// no popup window exists in OSR mode, and CEF-side popup interception proved
// unreliable live. So the page owns the behaviour instead: every click on an
// external http(s) anchor is cancelled and routed over the socket
// ({type:'open-external'}), where Java opens the OS default browser.
//
// Registered in the CAPTURE phase so the click is claimed before any other
// handler runs; other document-level listeners (e.g. the donate snooze in
// donate.js) still fire — preventDefault only cancels the navigation.

export function initExternalLinks(socket) {
  document.addEventListener('click', e => {
    const a = e.target.closest('a[href]');
    if (!a) return;
    const href = a.href || '';
    if (!/^https?:\/\//i.test(href) || href.startsWith('http://127.0.0.1')) return;
    e.preventDefault();
    socket.send('open-external', { url: href });
  }, true);
}
