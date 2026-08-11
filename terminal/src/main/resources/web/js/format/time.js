// Compact time formatters used by both widgets and the footer countdown.

import { t, currentLang } from '../i18n/i18n.js';

// Below this age a line says how long ago it was ("vor 3 Std.") and keeps
// counting; from here on the relative wording stops being informative and the
// calendar date takes over for good.
const DATE_AFTER_SEC = 7 * 24 * 60 * 60;

const LOCALES = { de: 'de-DE', en: 'en-US' };

export function fmtClock(epochSec) {
  if (!epochSec) return '--:--';
  const d = new Date(epochSec * 1000);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * The second line under a row's clock: the age of the line while that is still
 * a useful reading ("vor 12 Min.", ticking on its own), the calendar date once
 * it is a week or older. Returns '' when there is no timestamp at all.
 */
export function fmtStamp(epochSec, nowSec = Date.now() / 1000) {
  if (!epochSec) return '';
  const age = Math.max(0, Math.floor(nowSec - epochSec));
  if (age >= DATE_AFTER_SEC) return fmtDate(epochSec);
  if (age < 60) return ago('second', Math.max(1, age));
  if (age < 3600) return ago('minute', Math.floor(age / 60));
  if (age < 86400) return ago('hour', Math.floor(age / 3600));
  return ago('day', Math.floor(age / 86400));
}

// Singular and plural are separate keys — German and English disagree on which
// unit even takes a short form, so no key may be built by suffixing an 's'.
function ago(unit, n) {
  return t(`row.ago.${unit}${n === 1 ? '' : 's'}`).replace('{n}', String(n));
}

function fmtDate(epochSec) {
  const locale = LOCALES[currentLang()] || LOCALES.de;
  return new Intl.DateTimeFormat(locale, {
    day: '2-digit', month: '2-digit', year: 'numeric',
  }).format(new Date(epochSec * 1000));
}

export function fmtDuration(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
  if (m > 0) return `${m}m ${String(sec).padStart(2, '0')}s`;
  return `${sec}s`;
}
