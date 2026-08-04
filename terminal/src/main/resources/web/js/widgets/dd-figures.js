// Shared KI-DD figure markup — the finished report page (deepdive.js) and the
// live workshop mirror (deepdive-live.js) render the SAME figure cards and
// cross-reference links, so the preview never drifts from the print.

import { escapeHtml } from '../format/escape.js';

/** One server-rendered figure as a card. The SVG comes from our own Java
    builder — trusted markup; captions escaped. */
/** The document column's width — the canvas today's figures are drawn on. */
const COLUMN = 720;

/**
 * A figure's own canvas width, read off its viewBox. Archived reports carry
 * FROZEN SVGs from whatever builder wrote them, so a report from before the
 * canvas changed holds narrower pictures that must not be blown up to today's
 * column: enlarging them magnifies their labels AND their old collisions
 * without repairing anything. They render at the size they were set for.
 */
function canvasWidth(svg) {
  const m = /viewBox="0 0 (\d+(?:\.\d+)?) /.exec(svg || '');
  return m ? parseFloat(m[1]) : COLUMN;
}

export function figureHtml(fig, id) {
  // Plate first, caption BENEATH it — the figure convention of set documents,
  // and the same order DeepDivePdfExporter writes, so the report on screen and
  // the printed page stay one document.
  const w = canvasWidth(fig.svg);
  const legacy = w < COLUMN;
  const cls = legacy ? 'dd-figure dd-figure-legacy' : 'dd-figure';
  const style = legacy ? ` style="--fig-natural:${Math.round(w * 1.2)}px"` : '';
  return `<figure class="${cls}"${style}${id ? ` data-figid="${id}"` : ''}>
    ${fig.svg || ''}
    <figcaption>
      ${id ? `<span class="dd-figure-id">Abb.&nbsp;${id}</span>` : ''}
      <span class="dd-figure-title">${escapeHtml(fig.title || '')}</span>
      ${fig.note ? `<span class="dd-figure-note">${escapeHtml(fig.note)}</span>` : ''}
    </figcaption>
  </figure>`;
}

/** Prose pointers like "Abbildung A3" / "Figure A3" become jump links to the
    figure card carrying that ID badge (paper-style cross-references). */
export function linkFigureRefs(html) {
  return html.replace(/\b(Abbildung|Figure)\s+(A\d+)\b/g,
    (m0, word, id) => `<a href="#" class="dd-figref" data-fig="${id}">${word}&nbsp;${id}</a>`);
}
