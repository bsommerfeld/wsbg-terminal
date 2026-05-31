// 3-position state machine: slides only ever travel right → center → left.
//
// Visible: 5 min for the markets strip, 30 s for the donate ad.
// First demo swap fires 15 s after load so the cycle is visible
// without waiting for the full 5-minute window.

const MARKETS_MS = 5 * 60 * 1000;
const AD_MS      = 30 * 1000;

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

  // Markets are visible from launch for the full 5-minute window —
  // no short preview swap, the ad first appears 5 min after start.
  let visible = 'markets';
  let timer = setTimeout(swap, MARKETS_MS);

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
  function swap() {
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
    visible = visible === 'markets' ? 'ad' : 'markets';
    clearTimeout(timer);
    timer = setTimeout(swap, visible === 'markets' ? MARKETS_MS : AD_MS);
  }
}
