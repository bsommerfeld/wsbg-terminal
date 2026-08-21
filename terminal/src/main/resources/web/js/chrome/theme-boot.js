// Applies the stored theme BEFORE the first paint.
//
// index.html ships with data-theme="dark" in the markup, and chrome/theme.js
// corrects it on init - but init happens at the end of a 40-module import
// graph, long after the CSS (which is all in <head>, so it blocks) has let the
// browser paint. A light-theme user therefore gets a frame or more of the dark
// app. That used to be invisible: the intro plate covered the whole window for
// the first ~2.9s and was dark in every theme, so the flash happened behind it.
// Since the intro has a light variant, it is the FIRST thing on screen - and a
// dark room that flips to a bright one mid-drop is the one moment of the
// startup nobody can miss.
//
// Hence: a classic, blocking script in <head>. No imports, no await, runs
// before the body is parsed. It duplicates three key names from
// chrome/theme.js and nothing else - keep them in sync there.
(function () {
  try {
    var ls = window.localStorage;
    var theme;
    if (ls.getItem('wsbg.theme.follow-system.v1') === '1') {
      // The OS appearance is pushed from Java over the socket and cannot be
      // here yet, so we use the last one that arrived. `prefers-color-scheme`
      // is only the first-run fallback - under OSR it never sees the real
      // macOS theme (see chrome/theme.js).
      theme = ls.getItem('wsbg.theme.os.v1');
      if (theme !== 'dark' && theme !== 'light') {
        theme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
      }
    } else {
      theme = ls.getItem('wsbg.theme.v1') === 'light' ? 'light' : 'dark';
    }
    document.documentElement.setAttribute('data-theme', theme);
  } catch (_) {
    // No storage, no theme - the markup's data-theme="dark" stands.
  }
})();
