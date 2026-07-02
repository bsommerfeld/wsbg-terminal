// Front-end i18n layer.
//
// The whole UI used to be hardcoded German; this module makes every visible
// string key-resolved so the "Anzeigesprache" setting takes effect LIVE (no
// restart). German is the source language, English the translation; an unknown
// key falls back to the German string, then to the key itself.
//
// Two halves:
//   1. Static markup — elements carry `data-i18n` (textContent) or
//      `data-i18n-<attr>` (title / aria-label / placeholder). applyStatic()
//      rewrites them in place; setLang() calls it on every change.
//   2. Dynamic strings — JS renderers call t('key') at render time. On a
//      language change we fire `wsbg:languagechange` so those renderers can
//      re-paint from their cached payload (main.js / footer.js listen).

const DICT = {
  de: {
    // --- shared ---
    'common.live': 'Live',
    'common.degraded': 'Defekt',
    'common.donate': 'WSBG unterstützen',

    // --- titlebar ---
    'titlebar.update.title': 'Update verfügbar – jetzt installieren',
    'titlebar.update.aria': 'Update installieren',
    'titlebar.settings.title': 'Einstellungen',
    'titlebar.settings.aria': 'Einstellungen öffnen',

    // --- widget headers ---
    'widget.reddit.title': 'Schlagzeilen',
    'widget.reddit.open': 'r/wallstreetbetsGER öffnen',
    'widget.fj.open': 'Financial Juice öffnen',

    // --- settings ---
    'settings.title': 'Einstellungen',
    'settings.back': 'Zurück',
    'settings.more.aria': 'Mehr Infos',
    'settings.appearance.title': 'Aussehen',
    'settings.appearance.mode.name': 'Erscheinungsbild',
    'settings.appearance.mode.hint': 'Heller oder dunkler Modus.',
    'settings.appearance.mode.aria': 'Hell/Dunkel umschalten',
    'settings.appearance.system.name': 'Aus Systemeinstellungen übernehmen',
    'settings.appearance.system.hint': 'Folgt automatisch dem hellen/dunklen Modus deines Systems.',
    'settings.headlines.title': 'Schlagzeilen',
    'settings.headlines.images.name': 'Bilder mitanalysieren',
    'settings.headlines.images.hint': 'Wertet Bilder aus Threads und Kommentaren aus und lässt sie in Schlagzeilen einfließen.',
    'settings.headlines.images.more': 'Aus = schnellere, reine Text-Schlagzeilen ohne Bildanalyse.',
    'settings.headlines.redund.name': 'Wiederholungen filtern',
    'settings.headlines.redund.hint': 'Unterdrückt wiederholte Stories ohne echte Neuigkeit.',
    'settings.headlines.redund.more': 'Aus = strikte 1:1-Spiegelung: jedes Signal schreibt eine Zeile, auch doppelt. Die erste Zeile eines Subjekts kommt immer.',
    // --- changelog overlay ---
    'changelog.title': 'Was hat sich geändert?',
    'changelog.close': 'Schließen',
    'changelog.versions': 'Frühere Versionen',

    'settings.language.title': 'Sprache',
    'settings.language.name': 'Anzeigesprache',
    'settings.language.hint': 'Wird sofort übernommen.',
    'settings.updates.title': 'Updates',
    'settings.updates.auto.name': 'Automatisch aktualisieren',
    'settings.updates.auto.hint': 'Aktualisiert beim Start automatisch.',
    'settings.updates.auto.more': 'Wenn aus, meldet ein Hinweis in der Titelleiste neue Versionen.',
    'settings.data.title': 'Daten',
    'settings.data.logs.name': 'Zu den Logs',
    'settings.data.logs.hint': 'Öffnet den wsbg-terminal Ordner im Dateimanager — dort liegen unter anderem die Logs.',
    'settings.data.logs.btn': 'Ordner öffnen',
    'settings.data.clear.name': 'Daten löschen',
    'settings.data.clear.hint': 'Setzt das Terminal komplett zurück - Threads, Cluster, Schlagzeilen und Archiv.',
    'settings.data.clear.more': 'Danach füllt sich die Wire vom nächsten Scan neu. Höchstens alle 10 Minuten.',
    'settings.data.clear.btn': 'Daten löschen',
    'settings.data.clear.confirm': 'Wirklich löschen?',
    'settings.data.clear.done': 'Gelöscht',
    'settings.data.uninstall.name': 'Deinstallieren',
    'settings.data.uninstall.hint': 'Entfernt das Terminal komplett vom Rechner - App, KI-Modelle, Cache und alle Daten.',
    'settings.data.uninstall.more': 'Auch das Schlagzeilen-Archiv geht verloren. Unter Windows startet die Deinstallation des Systems, auf dem Mac räumt sich die App selbst weg, unter Linux fragt das System einmal nach dem Passwort.',
    'settings.data.uninstall.btn': 'Deinstallieren',
    'settings.data.uninstall.confirm': 'Wirklich alles entfernen?',
    'settings.data.uninstall.working': 'Wird deinstalliert…',

    // --- reddit rows ---
    'reddit.empty': 'Köche kochen noch',
    'reddit.thread.open.title': 'Thread öffnen',
    'reddit.thread.open.aria': 'Thread im Browser öffnen',
    'reddit.news.tag': 'News',
    'reddit.news.title': 'Mit externen Nachrichten angereichert',
    'reddit.news.sources.title': 'Nachrichtenquellen',
    'reddit.news.sources.open': 'Mit externen Nachrichten angereichert - Quellen anzeigen',
    'overlay.close': 'Schließen',
    'quote.points': 'Pkt',
    'quote.day': 'Tag',
    'quote.source': 'Kurs:',
    'quote.stale': 'außerhalb der Handelszeit — letzter Kurs',

    // --- financial juice ---
    'fj.waiting': 'Warte auf Financial Juice…',

    // --- fear & greed ---
    'fg.EXTREME_FEAR': 'Extreme Angst',
    'fg.FEAR': 'Angst',
    'fg.NEUTRAL': 'Neutral',
    'fg.GREED': 'Gier',
    'fg.EXTREME_GREED': 'Extreme Gier',

    // --- footer / market chips ---
    'footer.closed': 'FREI',
    'region.ASIEN': 'ASIEN',
    'region.AUSTRALIEN': 'AUSTRALIEN',

    // --- footer donate banner ---
    'ad.gains': 'Zur Abwechslung mal Gewinne realisiert? Finanziere den nächsten Loss-Porn',
    'ad.helped': 'Dir hat das WSBG-Terminal geholfen? Hilf beim Verlusttopf ausgleichen',
    'ad.star': 'Alles verloren und trotzdem den Drang zu spenden? Ein Stern ist fast so viel Wert wie ein Euro',
    'ad.etf': 'ETF oder Terminal, was wählst du?',
    'ad.bug': 'Du hast einen Fehler gefunden? Erstelle ein Issue!',
    'ad.feature': 'Dir fehlt das eine entscheidende Feature? Erstelle ein Issue!',
  },
  en: {
    // --- shared ---
    'common.live': 'Live',
    'common.degraded': 'Down',
    'common.donate': 'Support WSBG',

    // --- titlebar ---
    'titlebar.update.title': 'Update available – install now',
    'titlebar.update.aria': 'Install update',
    'titlebar.settings.title': 'Settings',
    'titlebar.settings.aria': 'Open settings',

    // --- widget headers ---
    'widget.reddit.title': 'Headlines',
    'widget.reddit.open': 'Open r/wallstreetbetsGER',
    'widget.fj.open': 'Open Financial Juice',

    // --- settings ---
    'settings.title': 'Settings',
    'settings.back': 'Back',
    'settings.more.aria': 'More info',
    'settings.appearance.title': 'Appearance',
    'settings.appearance.mode.name': 'Theme',
    'settings.appearance.mode.hint': 'Light or dark mode.',
    'settings.appearance.mode.aria': 'Toggle light/dark',
    'settings.appearance.system.name': 'Use system setting',
    'settings.appearance.system.hint': "Automatically follows your system's light/dark mode.",
    'settings.headlines.title': 'Headlines',
    'settings.headlines.images.name': 'Analyze images too',
    'settings.headlines.images.hint': 'Analyzes images from threads and comments and feeds them into headlines.',
    'settings.headlines.images.more': 'Off = faster, text-only headlines without image analysis.',
    'settings.headlines.redund.name': 'Filter repeats',
    'settings.headlines.redund.hint': 'Suppresses repeated stories that carry no real news.',
    'settings.headlines.redund.more': "Off = strict 1:1 mirror: every signal writes a line, even a duplicate. A subject's first line always comes through.",
    // --- changelog overlay ---
    'changelog.title': "What's changed?",
    'changelog.close': 'Close',
    'changelog.versions': 'Previous versions',

    'settings.language.title': 'Language',
    'settings.language.name': 'Display language',
    'settings.language.hint': 'Applied immediately.',
    'settings.updates.title': 'Updates',
    'settings.updates.auto.name': 'Update automatically',
    'settings.updates.auto.hint': 'Updates automatically on start.',
    'settings.updates.auto.more': 'When off, a hint in the title bar announces new versions.',
    'settings.data.title': 'Data',
    'settings.data.logs.name': 'Open logs',
    'settings.data.logs.hint': 'Opens the wsbg-terminal folder in your file manager — the logs live there, among other things.',
    'settings.data.logs.btn': 'Open folder',
    'settings.data.clear.name': 'Clear data',
    'settings.data.clear.hint': 'Fully resets the terminal - threads, clusters, headlines and archive.',
    'settings.data.clear.more': 'The wire then refills from the next scan. At most once every 10 minutes.',
    'settings.data.clear.btn': 'Clear data',
    'settings.data.clear.confirm': 'Really delete?',
    'settings.data.clear.done': 'Cleared',
    'settings.data.uninstall.name': 'Uninstall',
    'settings.data.uninstall.hint': 'Removes the terminal from this machine entirely - app, AI models, cache and all data.',
    'settings.data.uninstall.more': 'The headline archive is lost too. On Windows the system uninstall opens, on macOS the app removes itself, on Linux the system asks for your password once.',
    'settings.data.uninstall.btn': 'Uninstall',
    'settings.data.uninstall.confirm': 'Really remove everything?',
    'settings.data.uninstall.working': 'Uninstalling…',

    // --- reddit rows ---
    'reddit.empty': 'The cooks are still cooking',
    'reddit.thread.open.title': 'Open thread',
    'reddit.thread.open.aria': 'Open thread in browser',
    'reddit.news.tag': 'News',
    'reddit.news.title': 'Enriched with external news',
    'reddit.news.sources.title': 'News sources',
    'reddit.news.sources.open': 'Enriched with external news - show sources',
    'overlay.close': 'Close',
    'quote.points': 'pts',
    'quote.day': 'Day',
    'quote.source': 'Price:',
    'quote.stale': 'outside trading hours — last price',

    // --- financial juice ---
    'fj.waiting': 'Waiting for Financial Juice…',

    // --- fear & greed ---
    'fg.EXTREME_FEAR': 'Extreme Fear',
    'fg.FEAR': 'Fear',
    'fg.NEUTRAL': 'Neutral',
    'fg.GREED': 'Greed',
    'fg.EXTREME_GREED': 'Extreme Greed',

    // --- footer / market chips ---
    'footer.closed': 'OFF',
    'region.ASIEN': 'ASIA',
    'region.AUSTRALIEN': 'AUSTRALIA',

    // --- footer donate banner ---
    'ad.gains': 'Booked a gain for once? Fund the next loss-porn',
    'ad.helped': 'The WSBG Terminal helped you? Help balance the loss pot',
    'ad.star': 'Lost it all and still feel like donating? A star is worth almost as much as a euro',
    'ad.etf': 'ETF or Terminal — which do you pick?',
    'ad.bug': 'Found a bug? Open an issue!',
    'ad.feature': 'Missing that one crucial feature? Open an issue!',
  },
};

const SUPPORTED = ['de', 'en'];
const DEFAULT_LANG = 'de';
let lang = DEFAULT_LANG;

/** The currently active language code ('de' | 'en'). */
export function currentLang() {
  return lang;
}

/**
 * Translates a key. Falls back to the German string, then to `fallback`, then
 * to the raw key — so a missing translation degrades gracefully instead of
 * hard-failing (the whole UI must keep rendering).
 */
export function t(key, fallback) {
  const table = DICT[lang] || DICT[DEFAULT_LANG];
  if (key in table) return table[key];
  if (key in DICT[DEFAULT_LANG]) return DICT[DEFAULT_LANG][key];
  return fallback != null ? fallback : key;
}

const ATTRS = ['title', 'aria-label', 'placeholder'];

/** Rewrites every [data-i18n] / [data-i18n-<attr>] element under `root`. */
export function applyStatic(root = document) {
  root.querySelectorAll('[data-i18n]').forEach(el => {
    el.textContent = t(el.getAttribute('data-i18n'));
  });
  for (const attr of ATTRS) {
    root.querySelectorAll(`[data-i18n-${attr}]`).forEach(el => {
      el.setAttribute(attr, t(el.getAttribute(`data-i18n-${attr}`)));
    });
  }
}

/**
 * Switches the active language, rewrites all static markup, and broadcasts
 * `wsbg:languagechange` so dynamic renderers can re-paint. A no-op (bar a
 * re-apply) when the language is unchanged or unsupported.
 */
export function setLang(next) {
  if (!SUPPORTED.includes(next)) return;
  const changed = next !== lang;
  lang = next;
  document.documentElement.lang = lang;
  document.documentElement.dataset.lang = lang;
  applyStatic();
  if (changed) {
    window.dispatchEvent(new CustomEvent('wsbg:languagechange', { detail: { lang } }));
  }
}
