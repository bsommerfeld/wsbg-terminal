// Usage: node compare.mjs <run-label> <run-label> ...  — side-by-side of the per-phase numbers + layout diff vs the first run.
import fs from 'node:fs';
const S = process.env.WSBG_BENCH_OUT || new URL('.', import.meta.url).pathname.replace(/\/$/, '') + '/runs';
const labels = process.argv.slice(2);
const runs = labels.map(l => ({ l, ...JSON.parse(fs.readFileSync(`${S}/${l}/data.json`, 'utf8')) }));
const pct = (a, p) => { if (!a.length) return NaN; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))]; };
const f1 = x => Number.isFinite(x) ? x.toFixed(1) : '-';
const f0 = x => Number.isFinite(x) ? x.toFixed(0) : '-';
const UI = g => !/backend|ollama/.test(g);
function cpu(run, a, b, filter) {
  const byPid = {}; for (const r of run.psRows) if (r.t >= a - 600 && r.t <= b + 600 && filter(r.group)) (byPid[r.pid] ||= []).push(r);
  let tot = 0; for (const pid in byPid) { const rows = byPid[pid].sort((x, y) => x.t - y.t); const dt = (rows.at(-1).t - rows[0].t) / 1000; if (dt > 0) tot += 100 * (rows.at(-1).cpu - rows[0].cpu) / dt; }
  return tot;
}
function rss(run, t, filter) { const byPid = {}; for (const r of run.psRows) if (Math.abs(r.t - t) < 1500 && filter(r.group)) byPid[r.pid] = r; return Object.values(byPid).reduce((s, r) => s + r.rss, 0); }
const phases = ['LONG', 'IDLE', 'IDLE2', 'SCROLL', 'FULLPAINT', 'ANIM_WAVES', 'ANIM_COMP', 'ANIM_ROT', 'ANIM_TRANS', 'ANIM_FADE', 'ANIM_PAINT', 'IDLE_NOSAMPLER'].filter(p => runs.some(r => r.marks[p + '_END']));
console.log('runs: ' + runs.map(r => `${r.l} [${r.info?.ua?.includes('AppleWebKit/605') && !r.info?.ua?.includes('Chrome') ? 'WebKit' : 'Chromium'}]`).join(' | '));
console.log('\nrAF cadence per phase: fps / gap p95 / p99 / max ms / gaps>50ms');
console.log('phase'.padEnd(15) + runs.map(r => r.l.padStart(32)).join(''));
for (const ph of phases) console.log(ph.padEnd(15) + runs.map(r => { const g = r.raf[ph]; const a = r.marks[ph + '_START'], b = r.marks[ph + '_END']; if (!g || !a) return '-'.padStart(32); const fps = g.length / ((b - a) / 1000); return `${f0(fps)}/${f0(pct(g, 95))}/${f0(pct(g, 99))}/${f0(Math.max(0, ...g))}/${g.filter(x => x > 50).length}`.padStart(32); }).join(''));
console.log('\nUI CPU % of one core per phase (all UI processes, backend excluded)');
console.log('phase'.padEnd(15) + runs.map(r => r.l.padStart(32)).join(''));
for (const ph of phases) console.log(ph.padEnd(15) + runs.map(r => { const a = r.marks[ph + '_START'], b = r.marks[ph + '_END']; if (!a) return '-'.padStart(32); return f1(cpu(r, a, b, UI)).padStart(32); }).join(''));
console.log('\nbackend JVM CPU % per phase (in JCEF mode this includes the Chromium browser process)');
for (const ph of phases) console.log(ph.padEnd(15) + runs.map(r => { const a = r.marks[ph + '_START'], b = r.marks[ph + '_END']; if (!a) return '-'.padStart(32); return f1(cpu(r, a, b, g => g === 'backend-jvm')).padStart(32); }).join(''));
console.log('\nRSS MB: UI processes at IDLE end / at DONE / peak ; backend JVM at DONE');
console.log(''.padEnd(15) + runs.map(r => { const peak = {}; for (const x of r.psRows) if (UI(x.group)) { const k = x.t - x.t % 1000; peak[k] = (peak[k] || 0) + x.rss; } return `${f0(rss(r, r.marks.IDLE_END, UI))} / ${f0(rss(r, r.marks.DONE, UI))} / ${f0(Math.max(...Object.values(peak)))} ; jvm ${f0(rss(r, r.marks.DONE, g => g === 'backend-jvm'))}`.padStart(32); }).join(''));
console.log('\nstartup: launch→intro gone ms: ' + runs.map(r => `${r.l} ${r.marks.INTRO_GONE - r.marks.LAUNCH}`).join(' | '));
// layout diff vs first run
const base = runs[0].info?.layout || {};
for (const r of runs.slice(1)) {
  const L = r.info?.layout || {}; const diffs = [];
  for (const sel in base) { const a = base[sel], b = L[sel]; if (!a || !b) { if (a !== b) diffs.push(`${sel}: ${a ? 'missing' : 'extra'} in ${r.l}`); continue; }
    const d = ['x', 'y', 'w', 'h'].filter(k => Math.abs(a[k] - b[k]) > 1).map(k => `${k} ${a[k]}→${b[k]}`);
    for (const k of ['font', 'size', 'color', 'bg']) if (a[k] !== b[k]) d.push(`${k} ${a[k]}→${b[k]}`);
    if (d.length) diffs.push(`${sel}: ${d.join(', ')}`); }
  console.log(`\nlayout ${runs[0].l} → ${r.l}: ${diffs.length ? '\n  ' + diffs.join('\n  ') : 'identical (positions within 1 px, same fonts/colours)'}`);
}
console.log('\nslow page timers (from the last run that reported them):'); const tr = [...runs].reverse().find(r => r.timers); if (tr) for (const t of tr.timers) console.log(`  ${tr.l}: n=${t.n} max=${t.max} total=${t.total}  ${t.src}`);
