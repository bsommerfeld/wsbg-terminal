// Tiny DOM + formatting helpers shared by every debug panel.
// Everything is built with createElement — no innerHTML on bridge data.

export function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = String(text);
  return node;
}

export function svgEl(tag, attrs = {}) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  return node;
}

/** A titled card. Returns {card, body} so panels fill the body. */
export function card(title, subtitle) {
  const c = el('section', 'dbg-card');
  const head = el('header', 'dbg-card-head');
  head.appendChild(el('h3', 'dbg-card-title', title));
  if (subtitle) head.appendChild(el('span', 'dbg-card-sub', subtitle));
  c.appendChild(head);
  const body = el('div', 'dbg-card-body');
  c.appendChild(body);
  return { card: c, body, head };
}

/** label + value block. `tone` paints the value (ok|warn|bad|mute). */
export function stat(label, value, tone, hint) {
  const s = el('div', 'dbg-stat');
  s.appendChild(el('span', 'dbg-stat-label', label));
  const v = el('span', 'dbg-stat-value', value);
  if (tone) v.dataset.tone = tone;
  s.appendChild(v);
  if (hint) s.appendChild(el('span', 'dbg-stat-hint', hint));
  return s;
}

export function pill(text, tone) {
  const p = el('span', 'dbg-pill', text);
  if (tone) p.dataset.tone = tone;
  return p;
}

/** Rows of [label, node|string]; the workhorse for "just a number" content. */
export function statRow(...stats) {
  const row = el('div', 'dbg-stat-row');
  for (const s of stats) if (s) row.appendChild(s);
  return row;
}

export function table(headers, rows) {
  const t = el('table', 'dbg-table');
  const thead = el('thead');
  const hr = el('tr');
  for (const h of headers) hr.appendChild(el('th', null, h));
  thead.appendChild(hr);
  t.appendChild(thead);
  const tbody = el('tbody');
  for (const cells of rows) {
    const tr = el('tr');
    if (cells.tone) tr.dataset.tone = cells.tone;
    for (const c of cells.cells ?? cells) {
      const td = el('td');
      if (c instanceof Node) td.appendChild(c);
      else td.textContent = c == null ? '—' : String(c);
      tr.appendChild(td);
    }
    tbody.appendChild(tr);
  }
  t.appendChild(tbody);
  return t;
}

export function empty(text) {
  return el('p', 'dbg-empty', text);
}

/** The section-level error contract: one slot may fail, the answer still came. */
export function sectionError(message) {
  const box = el('div', 'dbg-error');
  box.appendChild(el('strong', null, 'section failed'));
  box.appendChild(el('span', null, String(message)));
  return box;
}

// ---- formatting ------------------------------------------------------

export function num(n) {
  if (n == null || Number.isNaN(n)) return '—';
  return Number(n).toLocaleString('en-US');
}

export function bytes(b) {
  if (b == null) return '—';
  const units = ['B', 'KB', 'MB', 'GB'];
  let v = Number(b), i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`;
}

/** Durations: sub-second stays exact, longer reads as a clock. */
export function ms(d) {
  if (d == null) return '—';
  const v = Number(d);
  if (v < 1000) return `${Math.round(v)} ms`;
  if (v < 60_000) return `${(v / 1000).toFixed(1)} s`;
  const s = Math.round(v / 1000);
  const m = Math.floor(s / 60), h = Math.floor(m / 60);
  if (h > 0) return `${h}h ${m % 60}m`;
  if (m >= 10) return `${m}m`;            // seconds are noise at this range
  return `${m}m ${s % 60}s`;
}

/** "12s ago" / "in 4m" against now; 0/null means never. */
export function since(atMs, now = Date.now()) {
  if (!atMs) return 'never';
  const d = now - Number(atMs);
  return d >= 0 ? `${ms(d)} ago` : `in ${ms(-d)}`;
}

export function clock(atMs) {
  if (!atMs) return '—';
  const d = new Date(Number(atMs));
  const p = n => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** `de.b…terminal.web.impl.net.HouseFetcher` -> `net.HouseFetcher`. */
export function shortLogger(name) {
  if (!name) return '';
  const parts = String(name).split('.');
  return parts.slice(-2).join('.');
}

export function pct(part, whole) {
  if (!whole) return 0;
  return Math.max(0, Math.min(1, Number(part) / Number(whole)));
}
