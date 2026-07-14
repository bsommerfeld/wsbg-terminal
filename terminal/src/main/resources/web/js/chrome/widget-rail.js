// Focus-mode tool rail: the floating buttons on the left edge of a
// fullscreened widget and their liquid-glass popups (widget-grid.css does the
// clip-path "pour out of the button" reveal; this module only toggles state).
//
// Generic behaviour here: one popup open at a time, outside click / Escape /
// view change closes. Widget-specific content:
//   - Reddit  filter (the shared facet panel from filter-popover.js) and the
//             Schlagzeilen settings that also live in the main settings view
//             (config-backed over the socket, synced via the settings snapshot
//             that settings.js re-broadcasts as `wsbg:settings`).
//   - FJ/F&G  static info popups (markup lives in index.html, i18n-tagged).

import { mountFilterPanel } from './filter-popover.js';
import { getSpec, onFilterChange } from '../widgets/headline-filter.js';

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
    btn.addEventListener('click', e => {
      e.stopPropagation();
      const open = !item.classList.contains('open');
      closeAll(item);
      item.classList.toggle('open', open);
      btn.setAttribute('aria-expanded', String(open));
    });
    // Clicks inside the popup stay inside (checkboxes, chips).
    item.querySelector('.rail-pop').addEventListener('click', e => e.stopPropagation());
  }

  document.addEventListener('click', () => closeAll(null));
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && document.querySelector('.rail-item.open')) closeAll(null);
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
      const s = getSpec();
      const on = {
        highlight: s.highlight !== 'ALL',
        price: s.price !== null,
        news: s.news !== null,
      };
      filterBtn.querySelectorAll('.f-line').forEach(line =>
        line.classList.toggle('active', !!on[line.dataset.facet]));
    };
    onFilterChange(syncLines);
    syncLines();
  }

}
