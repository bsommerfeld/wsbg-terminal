// Engine-agnostic UI benchmark, injected into the terminal page (JCEF via CDP, the
// shell via its init script). Same scenario on every engine: wait for the intro,
// IDLE, IDLE2 (animations paused), SCROLL (JS-driven smooth scroll), FULLPAINT
// (theme flips), ANIM_COMP (a small compositor-only ambient animation), ANIM_PAINT
// (a small paint-heavy ambient animation). Phase marks and per-frame rAF gaps go
// to the collector on 127.0.0.1:__PORT__.
(() => {
  if (window.__wsbgBenchStarted) return;
  window.__wsbgBenchStarted = true;
  const PORT = __PORT__;
  // Timer instrumentation: which setInterval/setTimeout callbacks of the page are slow?
  const slow = new Map(); // source snippet -> {n, max, total}
  const wrapTimer = orig => function (fn, ...rest) {
    if (typeof fn !== 'function') return orig.call(this, fn, ...rest);
    const src = String(fn).replace(/\s+/g, ' ').slice(0, 70);
    return orig.call(this, function (...a) {
      const t0 = performance.now(); try { return fn.apply(this, a); } finally {
        const d = performance.now() - t0; if (d > 4) { const e = slow.get(src) || { n: 0, max: 0, total: 0 }; e.n++; e.max = Math.max(e.max, d); e.total += d; slow.set(src, e); }
      }
    }, ...rest);
  };
  window.setInterval = wrapTimer(window.setInterval); window.setTimeout = wrapTimer(window.setTimeout);
  const nativeSleep = (ms) => new Promise(r => setTimeout(r, ms));
  const sleep = nativeSleep;
  const post = (path, body, keep = false) => fetch(`http://127.0.0.1:${PORT}/${path}`, {
    method: 'POST', body: JSON.stringify(body), keepalive: keep, mode: 'no-cors'
  }).catch(() => {});
  // keepalive bodies are capped at 64 KB - long phases are sent in chunks.
  const postGaps = async (name, g) => { for (let i = 0; i < g.length; i += 3000) await post('raf', { phase: name, gaps: g.slice(i, i + 3000), part: i / 3000 }); if (!g.length) await post('raf', { phase: name, gaps: [], part: 0 }); };
  const mark = name => post('mark', { name, t: Date.now() });

  let gaps = []; let running = false; let last = 0;
  const tick = t => { if (!running) return; if (last) gaps.push(t - last); last = t; requestAnimationFrame(tick); };
  const startSampler = () => { running = true; last = 0; requestAnimationFrame(tick); };
  const phase = async (name, fn) => {
    gaps = []; last = 0; await mark(name + '_START');
    await fn();
    const g = gaps.slice(); gaps = [];
    await mark(name + '_END');
    await postGaps(name, g);
  };
  const biggestScroller = () => {
    let best = null, ba = 0;
    for (const e of document.querySelectorAll('*')) {
      const cs = getComputedStyle(e);
      if (!/auto|scroll/.test(cs.overflowY) || e.scrollHeight <= e.clientHeight + 40) continue;
      const r = e.getBoundingClientRect(); const a = r.width * r.height;
      if (a > ba) { ba = a; best = e; }
    }
    return best;
  };
  const layoutProbe = () => {
    const sel = ['#titlebar', '.tb-title', '.tb-actions', '#widget-reddit', '#widget-reddit .widget-head', '#widget-reddit .widget-body',
      '#widget-reddit .row', '#widget-reddit .row .headline', '#widget-fj', '#widget-fj .row', 'footer', '.cell.exch', '#fear-greed-badge', '#eurusd-badge'];
    const out = {};
    for (const s of sel) {
      const e = document.querySelector(s); if (!e) { out[s] = null; continue; }
      const r = e.getBoundingClientRect(); const cs = getComputedStyle(e);
      out[s] = { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1),
        font: cs.fontFamily.split(',')[0].replace(/"/g, ''), size: cs.fontSize, color: cs.color, bg: cs.backgroundColor };
    }
    return out;
  };

  (async () => {
    const t0 = performance.now();
    while (document.readyState !== 'complete' || document.getElementById('intro')) await sleep(100);
    await mark('INTRO_GONE');
    await post('info', {
      ua: navigator.userAgent, dpr: devicePixelRatio, w: innerWidth, h: innerHeight,
      nodes: document.getElementsByTagName('*').length,
      fonts: [...document.fonts].filter(f => f.status === 'loaded').map(f => f.family + ' ' + f.weight),
      animations: document.getAnimations().map(a => ({ n: a.animationName || 'wa', t: (a.effect?.target?.tagName || '?') + '.' + String(a.effect?.target?.className || '').slice(0, 30), ps: a.playState })),
      layout: layoutProbe(),
      sinceInject: Math.round(performance.now() - t0)
    });
    startSampler();

    // Production runs: let the workload get going (model load, fetch tabs, pipeline)
    // before measuring, then watch a long quiet window for stalls caused by it.
    const WARM_S = __WARM__, LONG_S = __LONG__;
    if (WARM_S > 0) { await mark('WARMUP_START'); await sleep(WARM_S * 1000); await mark('WARMUP_END'); }
    if (LONG_S > 0) {
      const st = document.createElement('style');
      st.textContent = `@keyframes wsbg-long-rot { from { transform: rotate(0deg) } to { transform: rotate(360deg) } }
        .wsbg-long { position: fixed; left: 40px; bottom: 60px; width: 48px; height: 48px; border-radius: 50%; z-index: 99999; background: conic-gradient(#ffb000, #ff3d00, #ffb000); will-change: transform; animation: wsbg-long-rot 2s linear infinite; }`;
      document.head.appendChild(st); const el = document.createElement('div'); el.className = 'wsbg-long'; document.body.appendChild(el);
      await sleep(300);
      await phase('LONG', () => sleep(LONG_S * 1000));
      el.remove(); st.remove();
    }

    await phase('IDLE', () => sleep(10000));

    const anims = document.getAnimations();
    anims.forEach(a => a.pause());
    const webps = [...document.images].filter(i => /cook/.test(i.src)); webps.forEach(i => i.style.visibility = 'hidden');
    await sleep(500);
    await phase('IDLE2', () => sleep(8000));
    anims.forEach(a => a.play()); webps.forEach(i => i.style.visibility = '');

    const sc = biggestScroller();
    if (sc) {
      const top0 = sc.scrollTop;
      await phase('SCROLL', async () => {
        for (let i = 0; i < 90; i++) { sc.scrollBy({ top: i < 45 ? 120 : -120, behavior: 'smooth' }); await sleep(16); }
        await sleep(1500);
      });
      sc.scrollTop = top0;
    }

    await phase('FULLPAINT', async () => {
      const theme0 = document.documentElement.dataset.theme;
      for (let i = 0; i < 8; i++) { document.documentElement.dataset.theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'; await sleep(400); }
      document.documentElement.dataset.theme = theme0; await sleep(600);
    });

    // Ambient animations: what does a small free-running animation cost on this engine?
    const style = document.createElement('style');
    style.textContent = `
      @keyframes wsbg-b-spin { from { transform: rotate(0deg) scale(1); opacity: .6 } 50% { transform: rotate(180deg) scale(1.15); opacity: 1 } to { transform: rotate(360deg) scale(1); opacity: .6 } }
      @keyframes wsbg-b-rot { from { transform: rotate(0deg) } to { transform: rotate(360deg) } }
      @keyframes wsbg-b-trans { from { transform: translateX(0) } 50% { transform: translateX(160px) } to { transform: translateX(0) } }
      @keyframes wsbg-b-fade { from { opacity: .2 } 50% { opacity: 1 } to { opacity: .2 } }
      @keyframes wsbg-b-wave { from { background-position: 0 0 } to { background-position: 400px 0 } }
      .wsbg-b { position: fixed; left: 40px; bottom: 60px; width: 96px; height: 96px; border-radius: 50%; z-index: 99999; background: conic-gradient(#ffb000, #ff3d00, #ffb000); }
      .wsbg-b.comp  { will-change: transform, opacity; animation: wsbg-b-spin 2s linear infinite; }
      .wsbg-b.rot   { will-change: transform; animation: wsbg-b-rot 2s linear infinite; }
      .wsbg-b.trans { will-change: transform; animation: wsbg-b-trans 2s ease-in-out infinite; }
      .wsbg-b.fade  { will-change: opacity; animation: wsbg-b-fade 2s ease-in-out infinite; }
      .wsbg-b.paint { background: repeating-linear-gradient(90deg, #ffb000 0 20px, #ff3d00 20px 40px); filter: blur(6px); animation: wsbg-b-wave 2s linear infinite; }`;
    document.head.appendChild(style);
    // The page's own ambient animation: both unread portals lit for 6 s (deterministic,
    // whatever the read state is), so its cost can be compared across engines and fixes.
    {
      const portals = [...document.querySelectorAll('#widget-reddit .unread-portal')];
      const wasLit = portals.map(p => p.classList.contains('lit'));
      portals.forEach(p => p.classList.add('lit'));
      await sleep(1300); // the 1.1 s opacity transition must be over before measuring
      await phase('ANIM_WAVES', () => sleep(6000));
      portals.forEach((p, i) => p.classList.toggle('lit', wasLit[i]));
      await sleep(300);
    }
    for (const [name, cls] of [['ANIM_COMP', 'comp'], ['ANIM_ROT', 'rot'], ['ANIM_TRANS', 'trans'], ['ANIM_FADE', 'fade'], ['ANIM_PAINT', 'paint']]) {
      const el = document.createElement('div'); el.className = 'wsbg-b ' + cls; document.body.appendChild(el);
      await sleep(300);
      await phase(name, () => sleep(6000));
      el.remove(); await sleep(200);
    }
    style.remove();
    // The rAF sampler itself forces a rendering update per frame; measure idle without it (CPU only).
    running = false; await sleep(300);
    await phase('IDLE_NOSAMPLER', () => sleep(6000));
    await post('timers', [...slow.entries()].map(([src, e]) => ({ src, n: e.n, max: +e.max.toFixed(1), total: +e.total.toFixed(1) })).sort((a, b) => b.total - a.total).slice(0, 8));
    running = false;
    await mark('DONE');
    await post('done', {}, true);
  })().catch(e => post('error', { e: String(e) }));
})();
