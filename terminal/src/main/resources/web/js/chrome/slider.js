// 3-position state machine: slides only ever travel right → center → left.
//
// The markets strip is the default; the donate banner rotates in on a flat,
// slightly randomised 5–10 min cadence (see marketsWindow), shows for AD_MS,
// then the markets strip returns — no time gate, no per-session cap, no
// widening. Hovering the footer holds whatever slide is up.

const MIN_MARKETS_MS = 5 * 60 * 1000;    // shortest gap between two banners
const MAX_MARKETS_MS = 10 * 60 * 1000;   // longest gap between two banners
const AD_MS          = 30 * 1000;        // banner visible window

// How long a hover-hold survives without fresh pointer activity. Under OSR
// the browser's idea of "pointer is over the footer" can go stale forever
// (e.g. the window is dragged away under a stationary cursor — no mouseleave
// is ever delivered; proven live via DevTools: the slide cycle sat frozen
// until a synthetic mouseleave un-stuck it). So the hold is a decaying
// timestamp refreshed by real pointer events, never a latched boolean: a
// genuine reader keeps it alive by arriving (mouseenter/mousemove), a stuck
// hover dies after this window instead of suppressing the banner for good.
const HOVER_HOLD_MS   = 60 * 1000;

// Preset glyphs ("Schablonen"). The CSS class drives colour + animation
// (.heart beats, .star spins, .rocket lifts, .gem sparkles, .banana swings,
// .moon bobs, .skull holds still — it's dead). All styled in footer.css.
const ICONS = {
  heart: {
    cls: 'heart',
    svg: '<svg viewBox="0 0 24 24"><path d="M12 21s-7-4.35-9.5-9C.84 8.7 2.6 5 6 5c2 0 3.5 1.2 4 2.5C10.5 6.2 12 5 14 5c3.4 0 5.16 3.7 3.5 7-2.5 4.65-9.5 9-5.5 9z"/></svg>',
  },
  star: {
    cls: 'star',
    svg: '<svg viewBox="0 0 24 24"><path d="M12 2 L14 10 L22 12 L14 14 L12 22 L10 14 L2 12 L10 10 Z"/></svg>',
  },
  rocket: {
    cls: 'rocket',
    svg: '<svg viewBox="0 0 24 24"><path d="M12 2c2.8 1.8 4.2 5 4.2 8.4 0 .9-.1 1.8-.3 2.6l2.6 2.6-1.4 2.8-2.6-.9c-.7 1.2-1.6 2.4-2.5 3.5-.9-1.1-1.8-2.3-2.5-3.5l-2.6.9-1.4-2.8 2.6-2.6c-.2-.8-.3-1.7-.3-2.6C7.8 7 9.2 3.8 12 2z"/></svg>',
  },
  gem: {
    cls: 'gem',
    svg: '<svg viewBox="0 0 24 24"><path d="M6 3h12l4 6-10 12L2 9l4-6z"/></svg>',
  },
  banana: {
    cls: 'banana',
    svg: '<svg viewBox="0 0 24 24"><path d="M3 12c1.5 5.5 7.5 8.5 13 6.5 3-1.1 5-3.2 6-6-1.2 1.2-3 2.2-5 2.7C12 16.4 6.8 14.8 4.8 10.5L3 12z"/></svg>',
  },
  moon: {
    cls: 'moon',
    svg: '<svg viewBox="0 0 24 24"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>',
  },
  skull: {
    cls: 'skull',
    svg: '<svg viewBox="0 0 24 24"><path fill-rule="evenodd" d="M12 2a8 8 0 0 0-8 8c0 2.9 1.6 5 3.5 6.3V20h9v-3.7C18.4 15 20 12.9 20 10a8 8 0 0 0-8-8zM9 9a1.8 1.8 0 1 1 0 3.6A1.8 1.8 0 0 1 9 9zm6 0a1.8 1.8 0 1 1 0 3.6 1.8 1.8 0 0 1 0-3.6z"/></svg>',
  },
};

// Reciprocity stats from the donation-stats payload (TimeTracker's persisted
// bookkeeping). Lines containing {hours}/{opens} placeholders are kept out of
// the rotation until the corresponding stat has arrived.
let stats = { hours: null, opens: null };
export function setDonationStats(payload) {
  const hours = payload && payload.activeHours;
  const opens = payload && payload.openCount;
  stats = {
    hours: Number.isFinite(hours) && hours > 0 ? Math.round(hours) : null,
    opens: Number.isFinite(opens) && opens > 0 ? opens : null,
  };
}

const DONATE_LINK = { href: 'https://wsbg.app/donate', label: 'wsbg.app/donate' };

// Each entry: { icon?: key of ICONS, text: string, link?: { href, label } }.
// {hours} / {opens} are filled from the reciprocity stats at render time.
const AD_MESSAGES = [
  {
    icon: 'heart',
    text: 'Zur Abwechslung mal Gewinne realisiert? Finanziere den nächsten Loss-Porn',
    link: DONATE_LINK,
  },
  {
    icon: 'heart',
    text: 'Dir hat das WSBG-Terminal geholfen? Hilf beim Verlusttopf ausgleichen',
    link: DONATE_LINK,
  },
  {
    icon: 'star',
    text: 'Alles verloren und trotzdem den Drang zu spenden? Ein Stern ist fast so viel Wert wie ein Euro',
    link: { href: 'https://wsbg.app', label: 'wsbg.app' },
  },
  {
    icon: 'gem',
    text: 'MSCI oder Terminal, was wählst du?',
    link: DONATE_LINK,
  },
];

let holdUntil = 0;     // hover-hold deadline (see HOVER_HOLD_MS)

// Random gap before the next banner, 5–10 min. A flat, slightly randomised
// cadence reads as organic without any widening/throttle machinery.
function marketsWindow() {
  return MIN_MARKETS_MS + Math.random() * (MAX_MARKETS_MS - MIN_MARKETS_MS);
}

// Lines whose placeholder stat hasn't arrived yet stay out of the pool.
function eligibleMessages() {
  return AD_MESSAGES.filter(m =>
    (!m.text.includes('{hours}') || stats.hours != null) &&
    (!m.text.includes('{opens}') || stats.opens != null));
}

let lastAd = null;
function pickAdMessage() {
  const pool = eligibleMessages();
  if (pool.length <= 1) return pool[0];
  let msg;
  do { msg = pool[Math.floor(Math.random() * pool.length)]; } while (msg === lastAd);
  lastAd = msg;
  return msg;
}

function renderAd(host, msg) {
  host.replaceChildren();

  if (msg.icon && ICONS[msg.icon]) {
    const icon = document.createElement('span');
    icon.className = ICONS[msg.icon].cls;
    icon.innerHTML = ICONS[msg.icon].svg;
    host.appendChild(icon);
  }

  const text = document.createElement('span');
  text.textContent = msg.text
    .replace('{hours}', stats.hours)
    .replace('{opens}', stats.opens);
  host.appendChild(text);

  if (msg.link) {
    const a = document.createElement('a');
    a.href = msg.link.href;
    a.target = '_blank';
    a.rel = 'noopener';
    a.textContent = msg.link.label;
    const arrow = document.createElement('span');
    arrow.className = 'arrow';
    arrow.textContent = '→';
    a.appendChild(arrow);
    host.appendChild(a);
  }
}

export function initSlideCycle() {
  const markets = document.getElementById('markets');
  const ad = document.getElementById('ad');
  const adInner = ad?.querySelector('.ad-inner');
  if (!markets || !ad || !adInner) return;

  // Seed the first ad so it isn't empty if anything ever short-circuits
  // the swap path before the first rotation.
  renderAd(adInner, pickAdMessage());

  // Pause-on-hover: pointer activity over the footer freezes whatever slide
  // is up, so a noticed banner doesn't slide away mid-read. The hold decays
  // (HOVER_HOLD_MS) instead of latching, because OSR can lose the mouseleave.
  const feed = document.getElementById('feed') || markets.parentElement;
  const refreshHold = () => { holdUntil = Date.now() + HOVER_HOLD_MS; };
  feed?.addEventListener('mouseenter', refreshHold);
  feed?.addEventListener('mousemove', refreshHold);
  feed?.addEventListener('mouseleave', () => { holdUntil = 0; });

  // Markets are visible from launch for the full first window — no short
  // preview swap, the banner first appears one markets gap after start.
  let visible = 'markets';
  let timer = setTimeout(swap, marketsWindow());

  function snap(el, pos) {
    el.classList.remove('animate');
    el.dataset.pos = pos;
    // Force reflow so the next class change actually animates.
    void el.offsetWidth;
  }
  function move(el, pos) {
    el.classList.add('animate');
    el.dataset.pos = pos;
  }
  // Tell the rest of the page (the donate heart) when the banner is on-screen,
  // so it can pulse as a hint while the banner runs.
  function fireAdVisibility(on) {
    window.dispatchEvent(new CustomEvent('wsbg:ad-visibility', { detail: { visible: on } }));
  }
  function swap() {
    // Hold the current slide while the hover-hold is alive; re-check shortly
    // instead of advancing.
    if (Date.now() < holdUntil) {
      clearTimeout(timer);
      timer = setTimeout(swap, 1000);
      return;
    }
    const incoming = visible === 'markets' ? ad : markets;
    const outgoing = visible === 'markets' ? markets : ad;
    // Rotate the ad copy right before it snaps off-screen-right, so
    // the new line is in place before it animates into view.
    if (incoming === ad) renderAd(adInner, pickAdMessage());
    snap(incoming, 'right');
    setTimeout(() => {
      move(outgoing, 'left');
      move(incoming, 'center');
    }, 20);

    if (incoming === ad) fireAdVisibility(true);
    else fireAdVisibility(false);

    visible = visible === 'markets' ? 'ad' : 'markets';
    clearTimeout(timer);
    timer = setTimeout(swap, visible === 'markets' ? marketsWindow() : AD_MS);
  }
}
