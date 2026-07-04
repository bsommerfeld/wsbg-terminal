// Shared animation-restart helper.
//
// Restarts a CSS animation/transition that is (re)applied via a class, even when
// the same class fires back-to-back. The remove → forced-reflow → add order is
// load-bearing: reading `offsetWidth` flushes layout so the browser registers a
// style change on re-add instead of coalescing it away.
//
// When `ms` is given, the class is auto-removed after that many ms (a one-shot
// flash). Omit `ms` for a finite keyframe animation whose class stays applied.

export function restartAnimation(el, cls, ms) {
  el.classList.remove(cls);
  void el.offsetWidth;
  el.classList.add(cls);
  if (ms) setTimeout(() => el.classList.remove(cls), ms);
}
