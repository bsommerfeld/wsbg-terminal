// 3-position state machine: slides only ever travel right → center → left.
//
// The markets strip is the default; the donate banner is the gated, rotated-in
// slide. Once unlocked (12 h gate, see main.js), the banner is shown sparingly:
// capped to AD_CAP impressions per session and with a markets gap that *widens*
// each time, so a terminal left open all day thins the banner out instead of
// nagging every 5 minutes. Hovering the footer holds whatever slide is up.

const BASE_MARKETS_MS = 5 * 60 * 1000;   // markets gap before the first banner
const MAX_MARKETS_MS  = 40 * 60 * 1000;  // ceiling for the widening gap
const AD_MS           = 30 * 1000;       // banner visible window
const AD_CAP          = 4;               // max banner impressions per session
const WIDEN_FACTOR    = 2;               // each successive markets gap doubles

// Preset glyphs. The CSS class drives any animation (.heart beats, .star spins).
const ICONS = {
  heart: {
    cls: 'heart',
    svg: '<svg viewBox="0 0 24 24"><path d="M12 21s-7-4.35-9.5-9C.84 8.7 2.6 5 6 5c2 0 3.5 1.2 4 2.5C10.5 6.2 12 5 14 5c3.4 0 5.16 3.7 3.5 7-2.5 4.65-9.5 9-5.5 9z"/></svg>',
  },
  star: {
    cls: 'star',
    svg: '<svg viewBox="0 0 24 24"><path d="M12 2 L14 10 L22 12 L14 14 L12 22 L10 14 L2 12 L10 10 Z"/></svg>',
  },
};

// Each entry: { icon?: 'heart'|'star', text: string, link?: { href, label } }
const AD_MESSAGES = [
  {
    icon: 'heart',
    text: 'Zur Abwechslung mal Gewinne realisiert? Finanziere den nächsten Loss-Porn',
    link: { href: 'https://wsbg.app/donate', label: 'wsbg.app/donate' },
  },
  {
    icon: 'heart',
    text: 'Dir hat das WSBG-Terminal geholfen? Hilf beim Verlusttopf ausgleichen',
    link: { href: 'https://wsbg.app/donate', label: 'wsbg.app/donate' },
  },
  {
    icon: 'star',
    text: 'Alles verloren und trotzdem den Drang zu spenden? Ein Stern ist fast so viel Wert wie ein Euro',
    link: { href: 'https://wsbg.app', label: 'wsbg.app' },
  },
];

// Donation gate: the ad slide stays out of the rotation until the backend
// (TimeTracker) reports enough cumulative active time (~12 h) AND no snooze is
// active. Defaults to false so a fresh user never sees the donation banner
// before the gate message arrives. Flipped by setDonationAdEnabled() from the
// 'donation-gate' socket handler in main.js.
let adEnabled = false;
let hovered = false;   // pointer over the footer → hold the current slide
let adShown = 0;       // banner impressions this session (frequency cap)
export function setDonationAdEnabled(on) { adEnabled = !!on; }

// Markets gap before the next banner, widening with each impression so the
// banner thins out over a long session instead of reappearing every 5 minutes.
function marketsWindow() {
  return Math.min(MAX_MARKETS_MS, BASE_MARKETS_MS * Math.pow(WIDEN_FACTOR, adShown));
}

let lastAdIndex = -1;
function pickAdMessage() {
  if (AD_MESSAGES.length <= 1) return AD_MESSAGES[0];
  let i;
  do { i = Math.floor(Math.random() * AD_MESSAGES.length); } while (i === lastAdIndex);
  lastAdIndex = i;
  return AD_MESSAGES[i];
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
  text.textContent = msg.text;
  host.appendChild(text);

  if (msg.link) {
    const a = document.createElement('a');
    a.href = msg.link.href;
    a.target = '_blank';
    a.rel = 'noopener';
    a.dataset.donate = '';   // engaging the banner link snoozes the nudge layer
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

  // Pause-on-hover: holding the pointer over the footer freezes whatever
  // slide is up, so a noticed banner doesn't slide away mid-read.
  const feed = document.getElementById('feed') || markets.parentElement;
  feed?.addEventListener('mouseenter', () => { hovered = true; });
  feed?.addEventListener('mouseleave', () => { hovered = false; });

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
    // Hold the current slide while the user is hovering the footer; re-check
    // shortly instead of advancing.
    if (hovered) {
      clearTimeout(timer);
      timer = setTimeout(swap, 1000);
      return;
    }
    // Donation gate + frequency cap: while locked, or once this session's
    // banner quota is spent, keep the markets strip up and re-arm with the
    // (widening) markets window.
    if (visible === 'markets' && (!adEnabled || adShown >= AD_CAP)) {
      clearTimeout(timer);
      timer = setTimeout(swap, marketsWindow());
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

    if (incoming === ad) { adShown++; fireAdVisibility(true); }
    else { fireAdVisibility(false); }

    visible = visible === 'markets' ? 'ad' : 'markets';
    clearTimeout(timer);
    timer = setTimeout(swap, visible === 'markets' ? marketsWindow() : AD_MS);
  }
}
