// The source-phase idle scene ("die Poststelle") — the KI-DD live box while
// the desk is still COLLECTING and TRIAGING sources: instead of a growing
// list, a small 2.5D room where every collected source lands as a paper
// card in the inbox, a little clerk fetches an armful, reads it at his desk
// and PRE-SORTS it there onto two piles — what stays, what goes. Only with
// empty hands does he run a pile over: the keepers into the shelf as folder
// spines, the struck ones into the bin. Between jobs he idles — stretches,
// wanders, sips his coffee — an Animal-Crossing-ish waiting screen.
//
// The room has DEPTH: everything sits at (x, z) on a ground plane, z = 0 at
// the front edge, z = 1 at the foot of the back wall. `proj()` is the one
// projection — the far side converges toward a vanishing point and shrinks,
// so the inbox stands back at the wall while the desk sits up front and the
// clerk walks diagonally between them, growing and shrinking as he goes.
// Furniture is drawn as real quads (top faces, side panels); paper and the
// clerk are BILLBOARDS (upright sprites) — a skewed publisher line would be
// unreadable, and the source name is the whole point of a card. Everything
// paints back-to-front (painter's algorithm over z).
//
// Honesty: the scene is a DEPICTION, never the truth of record. Verdicts
// arrive faster than a walking figure can act them out, so the clerk works
// off a backlog: with a queue he carries several cards at once and speeds
// up, and past a hard backlog the overflow flies to its tray on its own.
// The exact numbers stay where they were: the count chip above the canvas.
//
// Paint discipline (software OSR renderer): ONE rAF loop, capped at 30 fps
// while anything moves and dropped to 12 fps when the room only breathes.
// It stops dead on unmount, on `document.hidden`, and the moment the report
// stage takes over. Nothing here animates when the box is closed.

import { t } from '../i18n/i18n.js';

/* ---- world geometry: a fixed-height stage scaled to the host box; the
   world WIDTH follows the box, so the room stretches sideways ---- */

const WORLD_H = 240;
const FLOOR_FRONT = 228;    // the ground line at z = 0 (front edge)
const HORIZON = 166;        // the ground line at z = 1 (foot of the wall)
const PERSP = 0.28;         // how hard the far side converges + shrinks
const MIN_WORLD_W = 420;

const CARD_W = 30;
const CARD_H = 21;
const SHEET_H = 3.2;        // a sheet seen edge-on, in the inbox pile
const KEPT_PER_BOARD = 9;  // folder spines that fit on one shelf board

const TRAY_H = 46;          // heights above the ground plane
const DESK_H = 40;

/** The clerk's skin: a fixed warm tone — a token would flip with the theme. */
const SKIN = 'oklch(0.82 0.10 68)';
/** Drop shadows are dark in BOTH themes — a token would invert in dark. */
const SHADOW = 'rgba(0,0,0,0.30)';

const WALK_SPEED = 96;      // world units per second, one card in hand
const MAX_CARRY = 4;        // a backlog is fetched in one armful
const DESK_PILE_MAX = 5;    // a desk pile this tall is worth a trip
const HAUL_MAX = 6;         // sheets he can carry away in one go
const RUSH_QUEUE = 6;       // from here on the clerk hurries
const FLY_QUEUE = 22;       // from here on the overflow files itself

/**
 * Mounts the scene into `host` (a plain div). Returns the handle the live
 * view drives: sources are announced with `add`, verdicts with `verdict`.
 */
export function createSourceScene(host) {
  const canvas = document.createElement('canvas');
  canvas.className = 'dd-scene-canvas';
  host.appendChild(canvas);
  const ctx = canvas.getContext('2d');

  let palette = readPalette();
  let cssW = 0, cssH = 0, scale = 1;
  let running = false, rafId = 0, lastTs = 0;
  let destroyed = false;

  /* ---- the room's contents ---- */

  const cards = [];              // every card object, any state
  const inbox = [];              // cards waiting in the tray (oldest first)
  const kept = [];               // filed on the shelf
  const deskKeep = [];           // pre-sorted on his desk: these stay
  const deskOut = [];            // pre-sorted on his desk: these go
  const flying = [];             // mid-air (a toss or an overflow flight)
  const puffs = [];              // one-shot particles
  const byRef = new Map();

  const clerk = {
    x: 0, z: 0.45, dir: 1, away: false,
    state: 'idle', timer: 0, phase: 0,
    carry: [],                   // cards in the armful (unread, or a haul)
    reading: null,               // the card held up at the desk
    hauling: null,               // 'keep' | 'out' while a pile is being run
    runs: [],                    // the delivery runs still owed
    blink: 0, blinkIn: 2.4,
    breathe: 0,
    idleAct: null, idleTimer: 1.2,
    verdictPop: null,            // {kind:'ok'|'out', life}
  };

  let layout = computeLayout(MIN_WORLD_W);

  /* ---- host observation: size, theme, visibility ---- */

  const ro = new ResizeObserver(() => resize());
  ro.observe(host);

  const themeObs = new MutationObserver(() => { palette = readPalette(); wake(); });
  themeObs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

  const onVisibility = () => { if (document.hidden) stop(); else start(); };
  document.addEventListener('visibilitychange', onVisibility);

  resize();
  start();

  /* ---- public handle ---- */

  return {
    /** A collected source appears: its card drops into the inbox tray. */
    add(src, animate) {
      if (destroyed || byRef.has(src.ref)) return;
      const card = makeCard(src);
      byRef.set(src.ref, card);
      cards.push(card);
      if (animate !== false) {
        card.drop = 1;                       // falls in from above
        card.dropDelay = Math.min(inbox.length * 0.06, 0.9);
      }
      inbox.push(card);
      relayoutInbox();
      wake();
    },
    /** The judge's verdict on a source ('ok' | 'out'). */
    verdict(src) {
      if (destroyed) return;
      const card = byRef.get(src.ref);
      if (!card) return;
      card.verdict = src.state === 'ok' ? 'ok' : src.state === 'out' ? 'out' : null;
      wake();
    },
    /** Seeds a rebuilt scene (language switch, re-mount) from known state. */
    seed(list) {
      for (const s of list) {
        if (s.state === 'pending') this.add(s, false);
        else fileInstantly(makeCardFiled(s));
      }
      wake();
    },
    /**
     * Stops the room for good. The canvas stays where it is — frozen on its
     * last frame, so the board can fade out with the room still in it; every
     * caller replaces that DOM right after.
     */
    destroy() {
      destroyed = true;
      stop();
      ro.disconnect();
      themeObs.disconnect();
      document.removeEventListener('visibilitychange', onVisibility);
    },
  };

  /* ---- the projection: (x, z, height) -> screen point + depth scale ---- */

  function proj(x, z, h = 0) {
    const tz = z * PERSP;
    const s = 1 - tz;
    return {
      x: x + (layout.vpX - x) * tz,
      y: FLOOR_FRONT + (HORIZON - FLOOR_FRONT) * z - h * s,
      s,
    };
  }

  /** A horizontal quad (a table top, a shelf board) at height `h`. */
  function quad(x0, x1, z0, z1, h) {
    const a = proj(x0, z0, h), b = proj(x1, z0, h);
    const c = proj(x1, z1, h), d = proj(x0, z1, h);
    ctx.beginPath();
    ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y);
    ctx.lineTo(c.x, c.y); ctx.lineTo(d.x, d.y);
    ctx.closePath();
  }

  /** A vertical quad hanging off the front edge (a table's apron). */
  function frontFace(x0, x1, z, hTop, hBottom) {
    const a = proj(x0, z, hTop), b = proj(x1, z, hTop);
    const c = proj(x1, z, hBottom), d = proj(x0, z, hBottom);
    ctx.beginPath();
    ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y);
    ctx.lineTo(c.x, c.y); ctx.lineTo(d.x, d.y);
    ctx.closePath();
  }

  function leg(x, z, h) {
    const top = proj(x, z, h), foot = proj(x, z, 0);
    ctx.beginPath();
    ctx.moveTo(top.x, top.y); ctx.lineTo(foot.x, foot.y);
    ctx.stroke();
  }

  /** A flat ellipse on the ground — shadows, the bin's footprint, the rug. */
  function groundEllipse(x, z, r, h = 0) {
    const p = proj(x, z, h);
    ctx.beginPath();
    ctx.ellipse(p.x, p.y, r * p.s, r * p.s * 0.3, 0, 0, Math.PI * 2);
  }

  /** A soft contact shadow — a hard ellipse reads as a hole in the floor. */
  function contactShadow(x, z, r) {
    const p = proj(x, z, 0);
    const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, r * p.s);
    g.addColorStop(0, 'rgba(0,0,0,0.22)');
    g.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.save();
    ctx.translate(p.x, p.y); ctx.scale(1, 0.3); ctx.translate(-p.x, -p.y);
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(p.x, p.y, r * p.s, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }

  /* ---- cards ---- */

  function makeCard(src) {
    // The display line is "title · publisher" — the publisher is the tail,
    // and it is what makes a card recognisable as a real source.
    const line = (src.title || '').trim();
    const cut = line.lastIndexOf(' · ');
    const pub = cut > 0 ? line.slice(cut + 3).trim() : '';
    const title = cut > 0 ? line.slice(0, cut).trim() : line;
    return {
      ref: src.ref,
      pub: pub || title.slice(0, 18),
      title,
      hue: hashHue(pub || title),
      x: 0, z: 0.5, h: 0, rot: 0, top: false,
      state: 'inbox', verdict: null,
      drop: 0, dropDelay: 0, fly: null,
    };
  }

  function makeCardFiled(src) {
    const c = makeCard(src);
    c.verdict = src.state === 'ok' ? 'ok' : 'out';
    byRef.set(c.ref, c);
    cards.push(c);
    return c;
  }

  /** Seeded state: already-judged cards simply stand in their tray. */
  function fileInstantly(card) {
    if (card.verdict === 'ok') { card.state = 'kept'; kept.push(card); relayoutKept(); }
    else { card.state = 'gone'; }
  }

  /* Piles: only the newest sheet of the inbox shows its face, the rest are
     paper edges — a pile of paper, not a tower of cards. */

  function relayoutInbox() {
    inbox.forEach((c, i) => {
      c.x = layout.inbox.x + ((hashHue(c.ref) % 5) - 2) * 0.6;
      c.z = layout.inbox.z;
      c.h = TRAY_H + Math.min(i, 7) * SHEET_H;
      c.rot = ((hashHue(c.ref) % 7) - 3) * 0.005;
      c.top = i === inbox.length - 1;
    });
  }

  /**
   * Filed sources stand UPRIGHT on the shelf like folders: flat sheets shrink
   * to invisible slivers at shelf depth, a row of coloured spines reads at a
   * glance — and it fills up as the desk keeps sources.
   */
  function relayoutKept() {
    kept.forEach((c, i) => {
      const board = Math.floor(i / KEPT_PER_BOARD) % 2;
      const slot = i % KEPT_PER_BOARD;
      c.x = layout.shelf.x - 14 + slot * 5.4;
      c.z = layout.shelf.z;
      c.h = board === 0 ? layout.shelf.h1 : layout.shelf.h2;
      c.rot = ((hashHue(c.ref) % 5) - 2) * 0.012;
      c.top = false;
    });
  }

  /** One filed source: a folder spine standing on its board. */
  function drawSpine(c) {
    const p = proj(c.x, c.z, c.h);
    const w = 4.4 * p.s, h = 17 * p.s;
    ctx.save();
    ctx.translate(p.x, p.y);
    ctx.rotate(c.rot || 0);
    ctx.fillStyle = `oklch(0.7 0.08 ${c.hue} / 0.85)`;
    ctx.beginPath(); roundRect(-w / 2, -h, w, h, 1); ctx.fill();
    ctx.fillStyle = paperFill();
    ctx.fillRect(-w / 2 + 0.4, -h + 2.5 * p.s, w - 0.8, 1.4 * p.s);
    ctx.strokeStyle = paperEdge(0.7);
    ctx.beginPath(); roundRect(-w / 2, -h, w, h, 1); ctx.stroke();
    ctx.restore();
  }

  /** The two piles on his desk: what stays (left) and what goes (right). */
  function relayoutDesk() {
    const place = (pile, x) => pile.forEach((c, i) => {
      c.x = x + ((hashHue(c.ref) % 5) - 2) * 0.5;
      c.z = layout.desk.z + 0.02;
      c.h = DESK_H + i * SHEET_H;
      c.rot = ((hashHue(c.ref) % 7) - 3) * 0.006;
      c.top = i === pile.length - 1;
    });
    place(deskKeep, layout.desk.x - 18);
    place(deskOut, layout.desk.x + 16);
  }

  /** The read sheet leaves his hand and lands on its pile. */
  function layOnDesk(card, kind) {
    const pile = kind === 'out' ? deskOut : deskKeep;
    card.state = 'toss';
    card.fly = {
      t: 0, dur: 0.32,
      x0: clerk.x + clerk.dir * 16, z0: clerk.z, h0: 44,
      x1: (kind === 'out' ? layout.desk.x + 16 : layout.desk.x - 18),
      z1: layout.desk.z + 0.02,
      h1: DESK_H + pile.length * SHEET_H,
      dest: kind === 'out' ? 'deskOut' : 'deskKeep',
      arc: 14,
    };
    flying.push(card);
  }

  /* ---- the clerk's little life ---- */

  function step(dt) {
    clerk.breathe += dt;
    clerk.blinkIn -= dt;
    if (clerk.blinkIn <= 0) { clerk.blink = 0.13; clerk.blinkIn = 2 + Math.random() * 4; }
    if (clerk.blink > 0) clerk.blink -= dt;
    if (clerk.verdictPop) {
      clerk.verdictPop.life -= dt;
      if (clerk.verdictPop.life <= 0) clerk.verdictPop = null;
    }

    stepCards(dt);
    stepPuffs(dt);

    const waiting = inbox.filter(c => c.drop <= 0).length;
    const rush = waiting >= RUSH_QUEUE ? 1.75 : 1;

    // Hard backlog: the overflow files itself, so the room never pretends
    // there are fewer sources than the desk actually pulled in.
    if (waiting > FLY_QUEUE) {
      const c = inbox.find(x => x.drop <= 0 && x.verdict);
      if (c) launchFlight(c);
    }

    switch (clerk.state) {
      case 'idle': idleStep(dt); break;

      case 'toInbox':
        if (walk(dt, layout.inbox.x + 26, layout.inbox.z - 0.16, rush)) {
          clerk.state = 'pick'; clerk.timer = 0.26;
        }
        break;

      case 'pick':
        clerk.timer -= dt;
        if (clerk.timer <= 0) {
          const take = waiting >= RUSH_QUEUE ? MAX_CARRY : 1;
          for (let i = 0; i < take; i++) {
            // Off the TOP of the pile — the sheet that is lying face up.
            const c = [...inbox].reverse().find(x => x.drop <= 0 && x.state === 'inbox');
            if (!c) break;
            c.state = 'carry';
            inbox.splice(inbox.indexOf(c), 1);
            clerk.carry.push(c);
          }
          relayoutInbox();
          if (!clerk.carry.length) { clerk.state = 'idle'; clerk.idleTimer = 0.6; break; }
          clerk.state = 'toDesk';
        }
        break;

      case 'toDesk':
        // He works the desk from BEHIND, so the desk stays in view.
        if (walk(dt, layout.desk.x - 8, layout.desk.z + 0.2, rush)) {
          clerk.reading = clerk.carry[0] || null;
          clerk.state = 'read';
          clerk.timer = waiting >= RUSH_QUEUE ? 0.75 : 1.5;
        }
        break;

      case 'read': {
        clerk.timer -= dt;
        const card = clerk.reading;
        if (!card) { clerk.state = 'sorted'; break; }
        // The verdict may still be out — he keeps reading (that IS the
        // truth: the judge has not spoken yet), but never longer than a
        // patience window, after which he files it as "still standing".
        const patience = clerk.timer <= -6;
        if (clerk.timer <= 0 && (card.verdict || patience)) {
          const out = card.verdict === 'out';
          clerk.verdictPop = { kind: out ? 'out' : 'ok', life: 0.9 };
          // Sorted, not carried away: the read sheet goes onto one of the
          // two piles ON HIS DESK. Walking one sheet at a time across the
          // room is what a machine would do, not a clerk.
          clerk.carry = clerk.carry.filter(c => c !== card);
          clerk.reading = null;
          layOnDesk(card, out ? 'out' : 'keep');
          if (clerk.carry.length) {
            clerk.reading = clerk.carry[0];
            clerk.timer = waiting >= RUSH_QUEUE ? 0.7 : 1.3;
          } else {
            clerk.state = 'sorted';
          }
        }
        break;
      }

      /* -- hands empty: either fetch the next armful, or run the desk piles
            over to the shelf and the bin -- */

      case 'sorted': {
        clerk.runs = [];
        const full = deskKeep.length >= DESK_PILE_MAX || deskOut.length >= DESK_PILE_MAX;
        // Deliver once a pile is worth a trip — or once nothing is waiting
        // and the desk would otherwise keep its piles for good.
        if (full || waiting === 0) {
          if (deskKeep.length) clerk.runs.push('keep');
          if (deskOut.length) clerk.runs.push('out');
        }
        clerk.state = clerk.runs.length ? 'toPile' : 'idle';
        if (!clerk.runs.length) clerk.idleTimer = 0.35;
        break;
      }

      case 'toPile': {
        // Back to his own desk to pick a pile up.
        const pile = clerk.runs[0] === 'out' ? deskOut : deskKeep;
        const px = clerk.runs[0] === 'out' ? layout.desk.x + 16 : layout.desk.x - 18;
        if (!pile.length) { clerk.runs.shift(); clerk.state = clerk.runs.length ? 'toPile' : 'idle'; break; }
        if (walk(dt, px, layout.desk.z + 0.2, rush)) { clerk.state = 'grab'; clerk.timer = 0.28; }
        break;
      }

      case 'grab': {
        clerk.timer -= dt;
        if (clerk.timer > 0) break;
        const kind = clerk.runs[0];
        const pile = kind === 'out' ? deskOut : deskKeep;
        clerk.hauling = kind;
        // An armful at a time — a tall pile takes two trips, like it would.
        for (let i = 0; i < HAUL_MAX && pile.length; i++) {
          const c = pile.pop();
          c.state = 'carry';
          clerk.carry.push(c);
        }
        relayoutDesk();
        clerk.state = clerk.carry.length ? 'toTray' : 'sorted';
        break;
      }

      case 'toTray': {
        const out = clerk.hauling === 'out';
        const tx = out ? layout.bin.x - 36 : layout.shelf.x - 48;
        const tz = out ? layout.bin.z - 0.14 : layout.shelf.z - 0.2;
        if (walk(dt, tx, tz, rush)) { clerk.state = 'file'; clerk.timer = 0.2; }
        break;
      }

      case 'file': {
        clerk.timer -= dt;
        if (clerk.timer > 0) break;
        const card = clerk.carry.shift();
        if (card) {
          if (clerk.hauling === 'out') tossToBin(card);
          else { card.state = 'kept'; kept.push(card); relayoutKept(); sparkle(card); }
        }
        if (clerk.carry.length) {
          clerk.timer = 0.18;                    // one sheet after the other
        } else {
          // Pile emptied? On to the next run, otherwise back for the rest.
          const pile = clerk.hauling === 'out' ? deskOut : deskKeep;
          if (!pile.length) clerk.runs.shift();
          clerk.hauling = null;
          clerk.state = clerk.runs.length ? 'toPile' : 'idle';
          clerk.idleTimer = 0.3;
        }
        break;
      }
    }

    // Work always wins over idling.
    if (clerk.state === 'idle' && inbox.some(c => c.drop <= 0)) {
      clerk.idleAct = null;
      clerk.state = 'toInbox';
    }
  }

  function idleStep(dt) {
    clerk.idleTimer -= dt;
    const act = clerk.idleAct;
    if (act) {
      act.life -= dt;
      if (act.kind === 'wander') {
        if (walk(dt, act.x, act.z, 0.55)) {
          clerk.idleAct = null; clerk.idleTimer = 1.4 + Math.random() * 2.4;
        }
        return;
      }
      if (act.life <= 0) { clerk.idleAct = null; clerk.idleTimer = 1.6 + Math.random() * 2.6; }
      return;
    }
    if (clerk.idleTimer > 0) return;
    const roll = Math.random();
    if (roll < 0.4) {
      clerk.idleAct = {
        kind: 'wander', life: 8,
        x: layout.roam.a + Math.random() * (layout.roam.b - layout.roam.a),
        z: 0.2 + Math.random() * 0.55,
      };
    } else if (roll < 0.65) {
      clerk.idleAct = { kind: 'stretch', life: 1.5 };
    } else if (roll < 0.85) {
      clerk.idleAct = { kind: 'look', life: 1.8 };
      clerk.dir = Math.random() < 0.5 ? -1 : 1;
    } else {
      clerk.idleAct = { kind: 'sip', life: 2.2 };
    }
  }

  /**
   * Walks toward (x, z) across the ground plane; true once there. Depth
   * costs more ground than width, so a step "into the room" is weighted —
   * otherwise he would shoot backwards.
   */
  function walk(dt, x, z, mult) {
    const dx = x - clerk.x;
    const dz = (z - clerk.z) * 110;              // depth in comparable units
    const dist = Math.hypot(dx, dz);
    if (dist < 2.5) { clerk.x = x; clerk.z = z; clerk.away = false; return true; }
    const move = Math.min(dist, WALK_SPEED * (mult || 1) * dt);
    clerk.x += (dx / dist) * move;
    clerk.z += (dz / dist) * move / 110;
    if (Math.abs(dx) > 4) clerk.dir = dx > 0 ? 1 : -1;
    // Heading into the room: we see his back — a face pasted on the back of
    // a head is the classic tell that a scene is fake.
    clerk.away = dz > Math.abs(dx) * 0.9;
    clerk.phase += (move / 13) * (mult >= 1.5 ? 1.25 : 1);
    return false;
  }

  function tossToBin(card) {
    card.state = 'toss';
    card.fly = {
      t: 0, dur: 0.5,
      x0: clerk.x + clerk.dir * 14, z0: clerk.z, h0: 40,
      x1: layout.bin.x, z1: layout.bin.z, h1: 20,
    };
    flying.push(card);
  }

  /** Overflow: a card files itself straight from the tray. */
  function launchFlight(card) {
    inbox.splice(inbox.indexOf(card), 1);
    relayoutInbox();
    const toBin = card.verdict === 'out';
    card.state = 'toss';
    card.fly = {
      t: 0, dur: 0.72,
      x0: card.x, z0: card.z, h0: card.h,
      x1: toBin ? layout.bin.x : layout.shelf.x,
      z1: toBin ? layout.bin.z : layout.shelf.z,
      h1: toBin ? 20 : layout.shelf.h1 + 8,
      keep: !toBin,
    };
    flying.push(card);
  }

  function stepCards(dt) {
    for (const c of cards) {
      if (c.drop > 0) {
        if (c.dropDelay > 0) { c.dropDelay -= dt; continue; }
        c.drop = Math.max(0, c.drop - dt / 0.42);
      }
      if (!c.fly) continue;
      c.fly.t += dt;
      if (c.fly.t < c.fly.dur) continue;
      const f = c.fly;
      c.fly = null;
      flying.splice(flying.indexOf(c), 1);
      if (f.dest === 'deskKeep' || f.dest === 'deskOut') {
        (f.dest === 'deskOut' ? deskOut : deskKeep).push(c);
        c.state = 'desk';
        relayoutDesk();
      } else if (f.keep) {
        c.state = 'kept'; kept.push(c); relayoutKept(); sparkle(c);
      } else {
        c.state = 'gone';
        puff(layout.bin.x, layout.bin.z, 24);
      }
    }
  }

  function puff(x, z, h) {
    const p = proj(x, z, h);
    for (let i = 0; i < 7; i++) {
      puffs.push({
        x: p.x, y: p.y, life: 0.5 + Math.random() * 0.3, max: 0.8,
        vx: (Math.random() - 0.5) * 40, vy: -20 - Math.random() * 30,
        r: (1.5 + Math.random() * 2) * p.s, color: palette.mute,
      });
    }
  }

  function sparkle(card) {
    const p = proj(card.x, card.z, card.h + 6);
    for (let i = 0; i < 6; i++) {
      puffs.push({
        x: p.x + (Math.random() - 0.5) * 16, y: p.y, life: 0.5, max: 0.5,
        vx: (Math.random() - 0.5) * 24, vy: -26 - Math.random() * 20,
        r: 1.2 * p.s, color: palette.green,
      });
    }
  }

  function stepPuffs(dt) {
    for (let i = puffs.length - 1; i >= 0; i--) {
      const p = puffs[i];
      p.life -= dt;
      if (p.life <= 0) { puffs.splice(i, 1); continue; }
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      p.vy += 60 * dt;
    }
  }

  /* ---- the frame loop ---- */

  function busy() {
    return clerk.state !== 'idle' || clerk.idleAct || puffs.length ||
      cards.some(c => c.fly || c.drop > 0);
  }

  function wake() { start(); }

  function start() {
    if (running || destroyed || document.hidden) return;
    running = true;
    lastTs = 0;
    rafId = requestAnimationFrame(frame);
  }

  function stop() {
    running = false;
    if (rafId) cancelAnimationFrame(rafId);
    rafId = 0;
  }

  function frame(ts) {
    if (!running) return;
    rafId = requestAnimationFrame(frame);
    if (!lastTs) { lastTs = ts; return; }
    // Frame budget: the software renderer pays for every repaint, so a room
    // that only breathes runs at a third of the working framerate.
    const interval = busy() ? 1 / 31 : 1 / 12;
    if ((ts - lastTs) / 1000 < interval) return;
    const dt = Math.min((ts - lastTs) / 1000, 0.06);
    lastTs = ts;
    step(dt);
    draw();
  }

  /* ---- sizing ---- */

  function resize() {
    const w = host.clientWidth, h = host.clientHeight;
    if (!w || !h) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    cssW = w; cssH = h;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    scale = h / WORLD_H;
    layout = computeLayout(Math.max(MIN_WORLD_W, w / scale));
    if (!clerk.x) clerk.x = layout.desk.x;
    relayoutInbox();
    relayoutKept();
    relayoutDesk();
    wake();
  }

  function computeLayout(W) {
    // The projection pulls the far side toward the vanishing point, so the
    // deep stations are placed WIDER than they should look — the inbox and
    // the shelf drift inwards on their own.
    const inboxX = Math.max(56, W * 0.11);
    const binX = W * 0.9;
    const shelfX = W * 0.79;
    const deskX = Math.max(inboxX + 110, Math.min(W * 0.44, shelfX - 90));
    return {
      W,
      vpX: W * 0.5,
      inbox: { x: inboxX, z: 0.92 },
      desk: { x: deskX, z: 0.1 },
      shelf: { x: shelfX, z: 0.84, h1: 28, h2: 62 },
      bin: { x: binX, z: 0.34 },
      // The plant stands in the FRONT-left corner: a foreground prop is the
      // classic depth anchor — it is nearest, so it is biggest.
      plant: { x: Math.max(26, W * 0.05), z: 0.06 },
      roam: { a: inboxX + 10, b: shelfX - 30 },
    };
  }

  /* ---- drawing ---- */

  function draw() {
    ctx.save();
    ctx.clearRect(0, 0, cssW, cssH);
    ctx.scale(scale, scale);
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';

    drawRoom();

    // Painter's algorithm: everything on the floor, far side first.
    const items = [
      { z: layout.shelf.z, fn: drawShelf },
      { z: layout.plant.z, fn: drawPlant },
      { z: layout.inbox.z, fn: drawInbox },
      { z: layout.bin.z, fn: drawBin },
      { z: layout.desk.z, fn: drawDesk },
      { z: clerk.z, fn: drawClerk },
    ];
    for (const c of flying) items.push({ z: flyPos(c).z, fn: () => drawFlying(c) });
    items.sort((a, b) => b.z - a.z);
    for (const it of items) it.fn();

    drawPuffs();
    ctx.restore();
  }

  function drawRoom() {
    const W = layout.W;
    // Wall: a whisper of a gradient, warm at the top — the room only has to
    // feel inhabited, it must never compete with the report.
    const g = ctx.createLinearGradient(0, 0, 0, HORIZON);
    g.addColorStop(0, withAlpha(palette.bg1, 0.95));
    g.addColorStop(1, withAlpha(palette.bg, 0.3));
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, W, HORIZON);

    drawPinboard(layout.desk.x - 10, 40);
    drawClock(layout.shelf.x + 30, 38);

    // Floor + baseboard.
    ctx.fillStyle = withAlpha(palette.bg1, 0.5);
    ctx.fillRect(0, HORIZON, W, WORLD_H - HORIZON);
    ctx.fillStyle = withAlpha(palette.line, 0.5);
    ctx.fillRect(0, HORIZON - 4, W, 4);
    ctx.strokeStyle = withAlpha(palette.line, 1);
    ctx.beginPath(); ctx.moveTo(0, HORIZON + .5); ctx.lineTo(W, HORIZON + .5); ctx.stroke();

    // Floor seams RUN INTO THE ROOM and converge on the vanishing point —
    // the cheapest, strongest depth cue the scene has.
    ctx.strokeStyle = withAlpha(palette.line, 0.3);
    ctx.beginPath();
    for (let i = 0; i <= 6; i++) {
      const px = (W / 6) * i;
      const near = proj(px, 0), far = proj(px, 1);
      ctx.moveTo(near.x, near.y); ctx.lineTo(far.x, far.y);
    }
    ctx.stroke();
  }

  /** A corkboard with pinned scraps — the desk's own memory wall. */
  function drawPinboard(x, y) {
    const w = 86, h = 52;
    ctx.fillStyle = withAlpha(palette.amber, 0.07);
    ctx.strokeStyle = withAlpha(palette.line, 1);
    ctx.beginPath(); roundRect(x - w / 2, y, w, h, 4); ctx.fill(); ctx.stroke();
    const notes = [[-26, 9, 20, 14], [0, 7, 22, 17], [24, 12, 18, 13], [-14, 30, 24, 12], [16, 32, 20, 11]];
    notes.forEach(([nx, ny, nw, nh], i) => {
      ctx.save();
      ctx.translate(x + nx, y + ny);
      ctx.rotate(((i % 3) - 1) * 0.05);
      ctx.fillStyle = withAlpha(i % 2 ? palette.txt2 : palette.mute, 0.16);
      ctx.beginPath(); roundRect(-nw / 2, 0, nw, nh, 1.5); ctx.fill();
      ctx.restore();
    });
  }

  /** The wall clock — the only thing that always moves, and it moves slowly. */
  function drawClock(cx, cy) {
    const r = 11;
    ctx.fillStyle = withAlpha(palette.bg1, 0.9);
    ctx.strokeStyle = withAlpha(palette.mute, 0.65);
    ctx.lineWidth = 1.2;
    ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    const tsec = clerk.breathe;
    ctx.strokeStyle = withAlpha(palette.txt2, 0.8);
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx + Math.cos(tsec * 0.2 - Math.PI / 2) * (r - 6),
      cy + Math.sin(tsec * 0.2 - Math.PI / 2) * (r - 6));
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx + Math.cos(tsec * 0.7 - Math.PI / 2) * (r - 3.5),
      cy + Math.sin(tsec * 0.7 - Math.PI / 2) * (r - 3.5));
    ctx.stroke();
    ctx.lineWidth = 1;
  }

  function drawPlant() {
    const { x, z } = layout.plant;
    const potH = 18;
    contactShadow(x, z, 14);
    ctx.fillStyle = withAlpha(palette.amber, 0.24);
    frontFace(x - 9, x + 9, z, potH, 0); ctx.fill();
    ctx.fillStyle = withAlpha(palette.amber, 0.34);
    quad(x - 9, x + 9, z - 0.03, z + 0.03, potH); ctx.fill();
    ctx.strokeStyle = withAlpha(palette.green, 0.6);
    ctx.lineWidth = 2 * proj(x, z).s;
    const sway = Math.sin(clerk.breathe * 0.7) * 1.6;
    for (const [dx, h] of [[-5, 20], [0, 27], [5, 22]]) {
      const base = proj(x + dx, z, potH);
      const tip = proj(x + dx * 1.7 + sway, z, potH + h);
      const mid = proj(x + dx + sway, z, potH + h * 0.6);
      ctx.beginPath();
      ctx.moveTo(base.x, base.y);
      ctx.quadraticCurveTo(mid.x, mid.y, tip.x, tip.y);
      ctx.stroke();
    }
    ctx.lineWidth = 1;
  }

  /** The inbox: a side table against the wall, a wire tray, the paper pile. */
  function drawInbox() {
    const { x, z } = layout.inbox;
    contactShadow(x, z, 30);

    ctx.strokeStyle = withAlpha(palette.mute2, 0.5);
    ctx.lineWidth = 2.4 * proj(x, z).s;
    leg(x - 20, z - 0.05, TRAY_H - 4); leg(x + 20, z - 0.05, TRAY_H - 4);
    leg(x - 17, z + 0.05, TRAY_H - 4); leg(x + 17, z + 0.05, TRAY_H - 4);
    ctx.lineWidth = 1;

    ctx.fillStyle = withAlpha(palette.mute2, 0.22);
    frontFace(x - 24, x + 24, z - 0.06, TRAY_H, TRAY_H - 4); ctx.fill();
    ctx.fillStyle = withAlpha(palette.mute2, 0.4);
    quad(x - 24, x + 24, z - 0.06, z + 0.06, TRAY_H); ctx.fill();

    for (const c of inbox) drawInboxCard(c);

    // The tray's front rail, drawn AFTER the paper so the pile sits in it.
    ctx.strokeStyle = withAlpha(palette.mute, 0.55);
    const railL = proj(x - 22, z - 0.06, TRAY_H + 6), railR = proj(x + 22, z - 0.06, TRAY_H + 6);
    const footL = proj(x - 22, z - 0.06, TRAY_H), footR = proj(x + 22, z - 0.06, TRAY_H);
    ctx.beginPath();
    ctx.moveTo(railL.x, railL.y); ctx.lineTo(footL.x, footL.y);
    ctx.lineTo(footR.x, footR.y); ctx.lineTo(railR.x, railR.y);
    ctx.stroke();

    label(t('dd.live.scene.inbox'), x, z, palette.mute);
  }

  /** The shelf: back panel, two boards, the kept sheets stacked on them. */
  function drawShelf() {
    const { x, z, h1, h2 } = layout.shelf;
    const l = x - 26, r = x + 34;
    ctx.fillStyle = withAlpha(palette.bg1, 0.7);
    frontFace(l, r, z, h2 + 8, 0); ctx.fill();
    ctx.fillStyle = withAlpha(palette.mute2, 0.2);
    frontFace(l, l + 4, z - 0.05, h2 + 8, 0); ctx.fill();
    frontFace(r - 4, r, z - 0.05, h2 + 8, 0); ctx.fill();

    for (const h of [h1, h2]) {
      ctx.fillStyle = withAlpha(palette.mute2, 0.6);
      quad(l, r, z - 0.05, z + 0.05, h); ctx.fill();
      ctx.fillStyle = withAlpha(palette.mute2, 0.25);
      frontFace(l, r, z - 0.05, h, h - 3); ctx.fill();
    }
    // The green board edge says WHY this shelf exists — it keeps.
    ctx.fillStyle = withAlpha(palette.green, 0.4);
    frontFace(l, r, z - 0.05, h1, h1 - 1.2); ctx.fill();

    for (const c of kept.slice(-2 * KEPT_PER_BOARD)) drawSpine(c);

    if (kept.length > 2 * KEPT_PER_BOARD) {
      const p = proj(r + 4, z, h1 + 6);
      ctx.font = `${7 * p.s}px "JetBrains Mono", monospace`;
      ctx.textAlign = 'left';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = withAlpha(palette.green, 0.85);
      ctx.fillText(`+${kept.length - 2 * KEPT_PER_BOARD}`, p.x, p.y);
    }
    label(t('dd.live.scene.keep'), x + 4, z, palette.green);
  }

  /** The bin: a round basket — its rim ellipse carries the whole 2.5D read. */
  function drawBin() {
    const { x, z } = layout.bin;
    const rimH = 22, rTop = 13, rBot = 10;
    contactShadow(x, z, rBot + 6);

    const top = proj(x, z, rimH), foot = proj(x, z, 0);
    ctx.fillStyle = withAlpha(palette.red, 0.08);
    ctx.strokeStyle = withAlpha(palette.red, 0.5);
    ctx.beginPath();
    ctx.moveTo(top.x - rTop * top.s, top.y);
    ctx.lineTo(foot.x - rBot * foot.s, foot.y);
    ctx.ellipse(foot.x, foot.y, rBot * foot.s, rBot * foot.s * 0.34, 0, Math.PI, 0, true);
    ctx.lineTo(top.x + rTop * top.s, top.y);
    ctx.closePath();
    ctx.fill(); ctx.stroke();
    ctx.beginPath();
    ctx.ellipse(top.x, top.y, rTop * top.s, rTop * top.s * 0.34, 0, 0, Math.PI * 2);
    ctx.stroke();

    // Crumpled paper peeking over the rim once the bin has seen work.
    if (kept.length || cards.some(c => c.state === 'gone')) {
      ctx.fillStyle = withAlpha(palette.mute, 0.32);
      ctx.beginPath();
      ctx.arc(top.x - 3 * top.s, top.y - 2 * top.s, 3.2 * top.s, 0, Math.PI * 2);
      ctx.arc(top.x + 4 * top.s, top.y - 1 * top.s, 2.6 * top.s, 0, Math.PI * 2);
      ctx.fill();
    }
    label(t('dd.live.scene.out'), x, z, palette.red);
  }

  /** The desk up front: top face, apron, legs, lamp and its pool of light. */
  function drawDesk() {
    const { x, z } = layout.desk;
    const s = proj(x, z).s;
    contactShadow(x, z, 42);

    // The warm pool of lamplight on the desk top — the cosiest thing here.
    const lampPt = proj(x + 24, z, DESK_H);
    const lamp = ctx.createRadialGradient(lampPt.x, lampPt.y, 2, lampPt.x, lampPt.y, 34 * s);
    lamp.addColorStop(0, withAlpha(palette.amber, 0.13));
    lamp.addColorStop(1, withAlpha(palette.amber, 0));
    ctx.fillStyle = lamp;
    ctx.fillRect(lampPt.x - 40 * s, lampPt.y - 44 * s, 80 * s, 64 * s);

    ctx.strokeStyle = withAlpha(palette.mute2, 0.5);
    ctx.lineWidth = 2.6 * s;
    leg(x - 30, z - 0.07, DESK_H - 4); leg(x + 30, z - 0.07, DESK_H - 4);
    leg(x - 26, z + 0.07, DESK_H - 4); leg(x + 26, z + 0.07, DESK_H - 4);
    ctx.lineWidth = 1;

    ctx.fillStyle = withAlpha(palette.mute2, 0.22);
    frontFace(x - 34, x + 34, z - 0.08, DESK_H, DESK_H - 4); ctx.fill();
    ctx.fillStyle = withAlpha(palette.mute2, 0.42);
    quad(x - 34, x + 34, z - 0.08, z + 0.08, DESK_H); ctx.fill();

    // Lamp: arm + shade, standing on the far edge of the top.
    const armFoot = proj(x + 30, z + 0.05, DESK_H);
    const armTop = proj(x + 30, z + 0.05, DESK_H + 22);
    const shade = proj(x + 22, z + 0.05, DESK_H + 24);
    ctx.strokeStyle = withAlpha(palette.mute2, 0.65);
    ctx.lineWidth = 1.6 * s;
    ctx.beginPath();
    ctx.moveTo(armFoot.x, armFoot.y); ctx.lineTo(armTop.x, armTop.y); ctx.lineTo(shade.x, shade.y);
    ctx.stroke();
    ctx.lineWidth = 1;
    ctx.fillStyle = withAlpha(palette.amber, 0.5);
    ctx.beginPath();
    ctx.moveTo(shade.x - 7 * s, shade.y + 8 * s);
    ctx.lineTo(shade.x + 7 * s, shade.y + 8 * s);
    ctx.lineTo(shade.x + 4 * s, shade.y);
    ctx.lineTo(shade.x - 4 * s, shade.y);
    ctx.closePath(); ctx.fill();

    // The mug, always on the desk.
    const mug = proj(x - 34, z + 0.02, DESK_H);
    ctx.fillStyle = withAlpha(palette.amber, 0.5);
    ctx.beginPath(); roundRect(mug.x - 3.5 * s, mug.y - 9 * s, 7 * s, 9 * s, 2 * s); ctx.fill();

    // The two sorting piles, each on its coloured marker: what stays (left),
    // what goes (right). They are what he actually works into — one trip per
    // pile instead of one trip per sheet.
    for (const [px, pile, color] of [[x - 18, deskKeep, palette.green], [x + 16, deskOut, palette.red]]) {
      ctx.fillStyle = withAlpha(color, pile.length ? 0.32 : 0.14);
      quad(px - 15, px + 15, z + 0.005, z + 0.035, DESK_H + 0.4); ctx.fill();
      for (const c of pile) drawSheet(c, color);
    }
  }

  /* ---- paper: billboards placed by the projection ---- */

  function drawInboxCard(c) {
    const fall = c.drop > 0 ? easeOutBack(1 - c.drop) : 1;
    const lift = (1 - fall) * 80;
    const alpha = c.drop > 0 && c.dropDelay > 0 ? 0 : 1;
    // Only the newest sheet shows its face — and it LIES ON the pile: its
    // bottom edge rests on the pile's top line.
    if (c.top || c.drop > 0) drawCard(c, c.x, c.z, c.h + lift, c.rot, alpha, false, true);
    else drawSheet(c);
  }

  /**
   * A sheet seen edge-on — the pile's building block. `tint` colours the
   * visible edge (the desk piles carry their verdict that way); without it
   * the sheet shows its source hue.
   */
  function drawSheet(c, tint) {
    const p = proj(c.x, c.z, c.h);
    // Hand-stacked sheets never align perfectly; a hair of width jitter and
    // a dark seam underneath keep the pile from fusing into one grey block.
    const w = (CARD_W - (hashHue(c.ref) % 3)) * p.s;
    const h = SHEET_H * p.s;
    ctx.save();
    ctx.translate(p.x, p.y);
    ctx.rotate(c.rot || 0);
    ctx.fillStyle = withAlpha(palette.bg, 0.55);
    ctx.fillRect(-w / 2, 0.2, w, 1 * p.s);
    ctx.fillStyle = paperFill();
    ctx.strokeStyle = paperEdge(0.9);
    ctx.beginPath(); roundRect(-w / 2, -h, w, h + 0.6, 1); ctx.fill(); ctx.stroke();
    ctx.fillStyle = tint ? withAlpha(tint, 0.55) : `oklch(0.72 0.06 ${c.hue} / 0.32)`;
    ctx.fillRect(-w / 2 + 1, -h + 0.6 * p.s, w - 2, 1.2 * p.s);
    ctx.restore();
  }

  function flyPos(c) {
    const f = c.fly;
    const k = Math.min(1, f.t / f.dur);
    return {
      x: f.x0 + (f.x1 - f.x0) * k,
      z: f.z0 + (f.z1 - f.z0) * k,
      h: f.h0 + (f.h1 - f.h0) * k + Math.sin(k * Math.PI) * (f.arc || 34),
      k,
    };
  }

  function drawFlying(c) {
    const p = flyPos(c);
    drawCard(c, p.x, p.z, p.h, p.k * 2.4, 1 - p.k * 0.35, false, false);
  }

  /**
   * One paper card, face up, standing at (x, z) with its BOTTOM edge at
   * height h. Big enough (in hand, at the desk) it carries the real source:
   * the publisher line and the title as paper lines. The verdict is a small
   * corner tab — never a neon outline.
   */
  function drawCard(c, x, z, h, rot, alpha, big, withText) {
    if (alpha <= 0) return;
    const p = proj(x, z, h);
    drawCardAt(c, p.x, p.y, p.s * (big ? 1.22 : 1), rot, alpha, big, withText);
  }

  /** The billboard itself, in screen space (px, py = bottom centre). */
  function drawCardAt(c, px, py, s, rot, alpha, big, withText) {
    const w = CARD_W * s, h = CARD_H * s;
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.translate(px, py - h / 2);
    ctx.rotate(rot);
    ctx.fillStyle = SHADOW;
    ctx.beginPath(); roundRect(-w / 2 + 1.2, -h / 2 + 1.8, w, h, 2.5); ctx.fill();
    ctx.fillStyle = paperFill();
    ctx.strokeStyle = paperEdge(1);
    ctx.beginPath(); roundRect(-w / 2, -h / 2, w, h, 2.5); ctx.fill(); ctx.stroke();
    // Publisher band (a per-source hue so a pile reads as many sources).
    ctx.fillStyle = `oklch(0.72 0.07 ${c.hue} / 0.42)`;
    ctx.beginPath(); roundRect(-w / 2, -h / 2, w, 3.5 * s, 2.5); ctx.fill();

    if (withText || big) {
      const inner = w - (c.verdict ? 13 : 7) * s;
      ctx.fillStyle = withAlpha(palette.txt2, 0.95);
      ctx.font = `${(big ? 7 : 5.5) * s}px "JetBrains Mono", monospace`;
      ctx.textAlign = 'left';
      ctx.textBaseline = 'top';
      ctx.fillText(clip(c.pub.toUpperCase(), inner, ctx), -w / 2 + 3.5 * s, -h / 2 + 5.5 * s);
      ctx.fillStyle = withAlpha(palette.mute, 0.7);
      const lines = big ? 3 : 2;
      for (let i = 0; i < lines; i++) {
        const lw = inner * (i === lines - 1 ? 0.55 : 0.9);
        ctx.fillRect(-w / 2 + 3.5 * s, -h / 2 + (big ? 15 : 12) * s + i * 3.6 * s, lw, 1.3 * s);
      }
    }
    if (c.verdict) {
      ctx.fillStyle = withAlpha(c.verdict === 'ok' ? palette.green : palette.red, 0.9);
      ctx.beginPath(); ctx.arc(w / 2 - 3.5 * s, -h / 2 + 7 * s, 1.9 * s, 0, Math.PI * 2); ctx.fill();
    }
    ctx.restore();
  }

  /* ---- the clerk: a billboard sprite, drawn in local units with his feet
     at the origin and everything scaled by his depth ---- */

  function drawClerk() {
    const foot = proj(clerk.x, clerk.z, 0);
    const s = foot.s;

    contactShadow(clerk.x, clerk.z, 16);

    ctx.save();
    ctx.translate(foot.x, foot.y);
    ctx.scale(s, s);

    const walking = clerk.state.startsWith('to') || (clerk.idleAct && clerk.idleAct.kind === 'wander');
    const bob = walking ? Math.abs(Math.sin(clerk.phase)) * 2.2 : Math.sin(clerk.breathe * 2) * 0.7;
    const act = clerk.idleAct ? clerk.idleAct.kind : null;
    const dir = clerk.dir;
    const back = clerk.away && walking;
    const hipY = -26 - bob;

    // Legs.
    ctx.strokeStyle = withAlpha(palette.blueDeep, 0.9);
    ctx.lineWidth = 4.5;
    const swing = walking ? Math.sin(clerk.phase) * 6 : 0;
    ctx.beginPath();
    ctx.moveTo(-3, hipY); ctx.lineTo(-3 + swing, 0);
    ctx.moveTo(3, hipY); ctx.lineTo(3 - swing, 0);
    ctx.stroke();

    // Body.
    ctx.fillStyle = withAlpha(palette.blue, 0.85);
    ctx.beginPath(); roundRect(-9, hipY - 22, 18, 24, 6); ctx.fill();

    // Arms: carrying in front, stretched up, or swinging.
    ctx.strokeStyle = withAlpha(palette.blue, 0.95);
    ctx.lineWidth = 3.6;
    const shoulder = hipY - 18;
    if (clerk.carry.length || act === 'sip') {
      const hx = dir * 12;
      ctx.beginPath();
      ctx.moveTo(-6, shoulder); ctx.lineTo(hx, shoulder + 6);
      ctx.moveTo(6, shoulder); ctx.lineTo(hx, shoulder + 6);
      ctx.stroke();
    } else if (act === 'stretch') {
      const up = Math.sin(Math.min(1, (1.5 - clerk.idleAct.life) * 3) * Math.PI) * 14;
      ctx.beginPath();
      ctx.moveTo(-6, shoulder); ctx.lineTo(-12, shoulder - up);
      ctx.moveTo(6, shoulder); ctx.lineTo(12, shoulder - up);
      ctx.stroke();
    } else {
      const a = walking ? Math.sin(clerk.phase) * 5 : Math.sin(clerk.breathe * 1.6) * 1.5;
      ctx.beginPath();
      ctx.moveTo(-6, shoulder); ctx.lineTo(-8 - a, shoulder + 12);
      ctx.moveTo(6, shoulder); ctx.lineTo(8 + a, shoulder + 12);
      ctx.stroke();
    }
    ctx.lineWidth = 1;

    // Head — no face when he is walking away from us.
    const headY = hipY - 30;
    const tilt = act === 'look' ? Math.sin(clerk.breathe * 2.2) * 2 : 0;
    ctx.fillStyle = SKIN;
    ctx.beginPath(); ctx.arc(tilt, headY, 9.5, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = withAlpha(palette.txt, 0.55);
    ctx.lineWidth = 2;
    if (back) {
      // The back of the head: hair covers it, and no eyes.
      ctx.fillStyle = withAlpha(palette.txt, 0.45);
      ctx.beginPath();
      ctx.arc(tilt, headY - 1.5, 9, Math.PI * 0.92, Math.PI * 0.08);
      ctx.closePath(); ctx.fill();
    } else {
      ctx.beginPath();
      ctx.moveTo(tilt - 1, headY - 9.5);
      ctx.quadraticCurveTo(tilt + 1.5, headY - 13, tilt + 4, headY - 9.5);
      ctx.stroke();
      ctx.fillStyle = withAlpha(palette.bg, 0.95);
      const ex = tilt + dir * 2.5;
      if (clerk.blink > 0) {
        ctx.fillRect(ex - 4, headY - 1, 3, 1.2);
        ctx.fillRect(ex + 1.5, headY - 1, 3, 1.2);
      } else {
        ctx.beginPath();
        ctx.arc(ex - 2.5, headY - 0.5, 1.5, 0, Math.PI * 2);
        ctx.arc(ex + 3, headY - 0.5, 1.5, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    ctx.lineWidth = 1;

    // What he is holding, in his own local space.
    if (clerk.reading && clerk.state === 'read') {
      drawCardAt(clerk.reading, dir * 28, headY + 16, 1.22, dir * 0.05, 1, true, true);
      drawThought(tilt - dir * 15, headY - 18, clerk.reading.verdict);
    } else if (clerk.carry.length) {
      clerk.carry.forEach((c, i) => {
        drawCardAt(c, dir * 13 + i * 1.2, hipY + 2 - i * 2.4, 1, dir * 0.04, 1, false, i === 0);
      });
    } else if (act === 'sip') {
      ctx.fillStyle = withAlpha(palette.amber, 0.6);
      ctx.beginPath(); roundRect(dir * 10, shoulder + 1, 7, 8, 2); ctx.fill();
    }

    if (clerk.verdictPop) {
      const p = clerk.verdictPop;
      const rise = (0.9 - p.life) * 14;
      ctx.save();
      ctx.globalAlpha = Math.min(1, p.life * 2.2);
      ctx.strokeStyle = p.kind === 'out' ? palette.red : palette.green;
      ctx.lineWidth = 1.8;
      const py = headY - 16 - rise;
      ctx.beginPath();
      if (p.kind === 'out') {
        ctx.moveTo(-2.6, py - 2.6); ctx.lineTo(2.6, py + 2.6);
        ctx.moveTo(2.6, py - 2.6); ctx.lineTo(-2.6, py + 2.6);
      } else {
        ctx.moveTo(-3.2, py); ctx.lineTo(-0.6, py + 2.6); ctx.lineTo(3.4, py - 3.2);
      }
      ctx.stroke();
      ctx.restore();
      ctx.lineWidth = 1;
    }

    ctx.restore();
  }

  /** The reading bubble: dots while the verdict is out. */
  function drawThought(x, y, verdict) {
    ctx.save();
    ctx.fillStyle = withAlpha(palette.bg1, 0.95);
    ctx.strokeStyle = withAlpha(palette.line, 1);
    ctx.beginPath(); roundRect(x - 13, y - 9, 26, 15, 7); ctx.fill(); ctx.stroke();
    ctx.beginPath(); ctx.arc(x - 8, y + 9, 2.2, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    const n = Math.floor(clerk.breathe * 3) % 3;
    for (let i = 0; i < 3; i++) {
      ctx.fillStyle = withAlpha(verdict ? palette.green : palette.mute, i <= n ? 0.95 : 0.3);
      ctx.beginPath(); ctx.arc(x - 6 + i * 6, y - 1.5, 1.7, 0, Math.PI * 2); ctx.fill();
    }
    ctx.restore();
  }

  function drawPuffs() {
    for (const p of puffs) {
      ctx.globalAlpha = Math.max(0, p.life / p.max) * 0.7;
      ctx.fillStyle = p.color;
      ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2); ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  /** A station's caption, laid on the FLOOR in front of it. */
  function label(text, x, z, color) {
    const p = proj(x, Math.max(0, z - 0.18), 0);
    ctx.save();
    ctx.font = `${8 * p.s}px "JetBrains Mono", monospace`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = withAlpha(color, 0.8);
    // Never off the stage: the front row would otherwise drop out of frame.
    ctx.fillText(String(text).toUpperCase(), p.x, Math.min(p.y + 13, WORLD_H - 7));
    ctx.restore();
  }

  /** Paper: always a shade lighter than the wall it hangs against. */
  function paperFill() {
    return isLight() ? 'rgba(253,252,250,0.98)' : withAlpha(palette.txt, 0.22);
  }

  /** The sheet's outline — on a light wall the line token is too faint. */
  function paperEdge(a) {
    return isLight() ? withAlpha(palette.mute, a * 0.55) : withAlpha(palette.line, a);
  }

  function isLight() {
    return document.documentElement.dataset.theme === 'light';
  }

  function roundRect(x, y, w, h, r) {
    const rr = Math.min(r, Math.abs(w) / 2, Math.abs(h) / 2);
    ctx.beginPath();
    ctx.moveTo(x + rr, y);
    ctx.arcTo(x + w, y, x + w, y + h, rr);
    ctx.arcTo(x + w, y + h, x, y + h, rr);
    ctx.arcTo(x, y + h, x, y, rr);
    ctx.arcTo(x, y, x + w, y, rr);
    ctx.closePath();
  }
}

/* ---- helpers ---- */

function clip(text, maxW, ctx) {
  let s = String(text);
  while (s.length > 1 && ctx.measureText(s).width > maxW) s = s.slice(0, -1);
  return s;
}

function easeOutBack(k) {
  const c = 1.4;
  return 1 + (c + 1) * Math.pow(k - 1, 3) + c * Math.pow(k - 1, 2);
}

function hashHue(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h) % 360;
}

/** The scene paints in the app's own tokens — theme switches carry over. */
function readPalette() {
  const cs = getComputedStyle(document.documentElement);
  const v = (name, fallback) => (cs.getPropertyValue(name).trim() || fallback);
  return {
    bg: v('--bg', '#111'),
    bg1: v('--bg-1', '#191919'),
    line: v('--line', '#333'),
    txt: v('--txt', '#eee'),
    txt2: v('--txt-2', '#ccc'),
    mute: v('--mute', '#888'),
    mute2: v('--mute-2', '#666'),
    amber: v('--amber', '#e0a33a'),
    green: v('--green', '#4caf7d'),
    red: v('--red', '#e5534b'),
    blue: v('--blue', '#69a8e0'),
    blueDeep: v('--blue-deep', '#3a63c0'),
  };
}

/**
 * Alpha on a token colour. The tokens are plain `oklch(l c h)` — the alpha
 * slots straight into the same function (no relative-colour syntax needed);
 * a hex colour (fallback, headless engines) becomes rgba().
 */
function withAlpha(color, a) {
  if (a >= 1) return color;
  const ok = /^oklch\(([^/)]*)\)$/.exec(color);
  if (ok) return `oklch(${ok[1].trim()} / ${a})`;
  if (color.startsWith('#')) {
    const hex = color.slice(1);
    const n = hex.length === 3
      ? hex.split('').map(c => parseInt(c + c, 16))
      : [0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16));
    return `rgba(${n[0]},${n[1]},${n[2]},${a})`;
  }
  return color;
}
