// Shared KI-DD figure markup — the finished report page (deepdive.js) and the
// live workshop mirror (deepdive-live.js) render the SAME figure cards and
// cross-reference links, so the preview never drifts from the print.

import { escapeHtml } from '../format/escape.js';

/** One server-rendered figure as a card. The SVG comes from our own Java
    builder — trusted markup; captions escaped. */
export function figureHtml(fig, id) {
  return `<figure class="dd-figure" ${id ? `data-figid="${id}"` : ''}>
    <figcaption>
      ${id ? `<span class="dd-figure-id">${id}</span>` : ''}
      <span class="dd-figure-title">${escapeHtml(fig.title || '')}</span>
      <span class="dd-figure-rule"></span>
      ${fig.note ? `<span class="dd-figure-note">${escapeHtml(fig.note)}</span>` : ''}
    </figcaption>
    ${fig.svg || ''}
  </figure>`;
}

/** Prose pointers like "Abbildung A3" / "Figure A3" become jump links to the
    figure card carrying that ID badge (paper-style cross-references). */
export function linkFigureRefs(html) {
  return html.replace(/\b(Abbildung|Figure)\s+(A\d+)\b/g,
    (m0, word, id) => `<a href="#" class="dd-figref" data-fig="${id}">${word}&nbsp;${id}</a>`);
}
