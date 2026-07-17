// Estimated reading time for a markdown report — the news-article
// "X Min. Lesezeit" convention, computed client-side from the report text.
// Markdown syntax is stripped first so link targets, table scaffolding and
// figure anchors don't inflate the word count; the speed is calibrated for
// German long-form prose.

import { t } from '../i18n/i18n.js';

const WORDS_PER_MINUTE = 200;

export function readingMinutes(md) {
  if (!md) return 0;
  let body = String(md);
  // The source register at the tail is reference material, not reading matter.
  const cut = Math.max(body.lastIndexOf('\n## Quellen\n'), body.lastIndexOf('\n## Sources\n'));
  if (cut >= 0) body = body.slice(0, cut);
  const text = body
      .replace(/```[\s\S]*?```/g, ' ')          // code fences
      .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')    // images
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')  // links → keep the label
      .replace(/<[^>]+>/g, ' ')                 // inline HTML
      .replace(/^[|:\-+\s]+$/gm, ' ')           // table rule rows
      .replace(/[|#>*_`~]/g, ' ');              // leftover markdown syntax
  const words = text.split(/\s+/).filter(Boolean).length;
  return minutesFromWords(words);
}

// For list payloads that carry only a server-side word count, not the text.
export function minutesFromWords(words) {
  return words > 0 ? Math.max(1, Math.round(words / WORDS_PER_MINUTE)) : 0;
}

// Ready-to-append meta fragments ("7 Min. Lesezeit"); '' when there is no
// text, so callers can .filter(Boolean) them away.
export function readingTimeLabel(md) {
  return label(readingMinutes(md));
}

export function readingTimeLabelFromWords(words) {
  return label(minutesFromWords(words));
}

function label(mins) {
  return mins ? `${mins} ${t('read.minutes')}` : '';
}
