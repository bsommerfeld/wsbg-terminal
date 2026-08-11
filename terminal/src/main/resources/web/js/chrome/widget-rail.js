// Focus-mode tool rail: the floating buttons on the left edge of a
// fullscreened widget and their liquid-glass popups (widget-grid.css does the
// clip-path "pour out of the button" reveal; this module only toggles state).
//
// Generic behaviour here: one popup open at a time, outside click / Escape /
// view change closes. It runs over EVERY .rail-item — which includes the
// "Über dieses Widget" footnote in the bottom-left corner (.widget-about),
// deliberately built on the same mechanics and only dressed differently, so a
// widget whose only disclosure is documentation needs no rail at all.
// Widget-specific content:
//   - Reddit  filter (the shared facet panel from filter-popover.js) and the
//             Schlagzeilen settings that also live in the main settings view
//             (config-backed over the socket, synced via the settings snapshot
//             that settings.js re-broadcasts as `wsbg:settings`).
//   - about   static description + source link + KI notice (markup lives in
//             index.html, i18n-tagged).

import { mountFilterPanel } from './filter-popover.js';
import { activeFacets, onFilterChange } from '../widgets/headline-filter.js';

export function initWidgetRail(socket) {
  const items = [...document.querySelectorAll('.rail-item')];
  const withPop = items.filter(i => i.querySelector('.rail-pop'));

  const closeAll = except => {
    for (const i of withPop) {
      if (i === except) continue;
      i.classList.remove('open');
      i.querySelector('.rail-btn')?.setAttribute('aria-expanded', 'false');
    }
  };

  for (const item of withPop) {
    const btn = item.querySelector('.rail-btn');
    const pop = item.querySelector('.rail-pop');
    // The panel is the dialog: opening moves focus to the CONTAINER, never to
    // its first control — the Schlagzeilen settings panel starts with the
    // destructive "Daten löschen" button, and landing a keyboard user on that
    // is not a neutral place to arrive. From the container Tab walks in
    // normally. (Panels with an obvious entry point focus it themselves —
    // headline-search.js puts the caret in its input.)
    pop.setAttribute('tabindex', '-1');
    btn.addEventListener('click', e => {
      e.stopPropagation();
      const open = !item.classList.contains('open');
      closeAll(item);
      item.classList.toggle('open', open);
      btn.setAttribute('aria-expanded', String(open));
      // After the reveal has started, so the focus ring doesn't paint on a
      // still-clipped panel.
      if (open) setTimeout(() => { if (item.classList.contains('open')) pop.focus({ preventScroll: true }); }, 60);
    });
    // Clicks inside the popup stay inside (checkboxes, chips).
    pop.addEventListener('click', e => e.stopPropagation());
  }

  document.addEventListener('click', () => closeAll(null));
  document.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    const open = document.querySelector('.rail-item.open');
    if (!open) return;
    // Escape hands the focus BACK to the knob it came from — otherwise it
    // falls to <body> and the next Tab restarts at the top of the document.
    const btn = open.querySelector('.rail-btn');
    closeAll(null);
    btn?.focus({ preventScroll: true });
  });
  // Leaving focus view (grid button, Escape, overview) drops any open popup.
  window.addEventListener('wsbg:viewchange', () => closeAll(null));

  // ---- Reddit: the shared headline-filter panel + the facet-stroke readout
  // (same visual language as the dashboard funnel: an active facet lights
  // its own stroke amber). ----
  const filterBody = document.querySelector('.js-rail-filter');
  if (filterBody) {
    mountFilterPanel(filterBody);
    const filterBtn = document.querySelector('.js-rail-filter-btn');
    const syncLines = () => {
      if (!filterBtn) return;
      const on = activeFacets();
      filterBtn.querySelectorAll('.f-line').forEach(line =>
        line.classList.toggle('active', !!on[line.dataset.facet]));
    };
    onFilterChange(syncLines);
    syncLines();
  }

}
