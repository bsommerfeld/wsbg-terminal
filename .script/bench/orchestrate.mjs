// Usage: node orchestrate.mjs <jcef|shell> <label> [--prod] [--warm <s>] [--long <s>] [--hz120] [--wait-ms N]
// Starts the backend (+ shell), injects bench.js, collects marks/rAF gaps over HTTP,
// samples the process tree every second, quits everything and writes <label>/report.txt.
import fs from 'node:fs'; import http from 'node:http'; import { spawn, execSync } from 'node:child_process';
const S = process.env.WSBG_BENCH_OUT || new URL('.', import.meta.url).pathname.replace(/\/$/, '') + '/runs';
const REPO = new URL('../..', import.meta.url).pathname.replace(/\/$/, '');
const [mode, label, ...flags] = process.argv.slice(2);
const prod = flags.includes('--prod');
const waitMs = flags.includes('--wait-ms') ? +flags[flags.indexOf('--wait-ms') + 1] : 0;
const warmS = flags.includes('--warm') ? +flags[flags.indexOf('--warm') + 1] : 0;
const longS = flags.includes('--long') ? +flags[flags.indexOf('--long') + 1] : 0;
const OUT = `${S}/${label}`; fs.mkdirSync(OUT, { recursive: true });
const sleep = ms => new Promise(r => setTimeout(r, ms));
const now = () => Date.now();
const log = (...a) => { const l = `${now()} ${a.join(' ')}`; console.log(l); fs.appendFileSync(`${OUT}/orchestrator.log`, l + '\n'); };

// ---- collector ----
const marks = {}; const raf = {}; let info = null; let timers = null; let done = false; const errors = [];
const collector = http.createServer((req, res) => {
  let body = ''; req.on('data', c => body += c); req.on('end', () => {
    res.writeHead(200, { 'Access-Control-Allow-Origin': '*' }); res.end('ok');
    try { const j = body ? JSON.parse(body) : {};
      if (req.url === '/mark') { marks[j.name] = j.t; log('MARK', j.name); }
      else if (req.url === '/raf') raf[j.phase] = (raf[j.phase] || []).concat(j.gaps);
      else if (req.url === '/info') info = j;
      else if (req.url === '/done') done = true;
      else if (req.url === '/timers') timers = j;
      else if (req.url === '/error') { errors.push(j.e); log('PAGE ERROR', j.e); }
    } catch (e) { log('collector parse error', e.message); }
  });
});
await new Promise(r => collector.listen(0, '127.0.0.1', r));
const PORT = collector.address().port;
const benchJs = fs.readFileSync(new URL('./bench.template.js', import.meta.url), 'utf8').replace(/__PORT__/g, String(PORT)).replace(/__WARM__/g, String(warmS)).replace(/__LONG__/g, String(longS));
fs.writeFileSync(`${OUT}/bench.js`, benchJs);

// ---- process sampler (1 s) ----
const psRows = []; // {t, pid, group, rss, cpu}
let groups = {}; // pid -> group name
const cpuSecs = s => s.split(':').reduce((v, x) => v * 60 + parseFloat(x), 0);
function samplePs() {
  try {
    const out = execSync('ps -eo pid,ppid,rss,cputime,lstart,comm', { encoding: 'utf8' });
    const t = now();
    for (const line of out.split('\n').slice(1)) {
      const m = line.trim().match(/^(\d+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\S+ \S+ +\d+ \S+ \d+)\s+(.*)$/); if (!m) continue;
      const pid = +m[1], comm = m[6];
      const g = groups[pid] || classify(pid, +m[2], comm, m[5]); if (!g) continue;
      groups[pid] = g; psRows.push({ t, pid, group: g, rss: +m[3] / 1024, cpu: cpuSecs(m[4]) });
    }
  } catch (e) { log('ps failed', e.message); }
}
let backendPid = 0, shellPid = 0, shellStartMs = 0;
function classify(pid, ppid, comm, lstart) {
  if (pid === backendPid) return 'backend-jvm';
  if (/jcef Helper \(GPU\)/.test(comm)) return 'cef-gpu';
  if (/jcef Helper \(Renderer\)/.test(comm)) return 'cef-renderer';
  if (/jcef Helper/.test(comm)) return 'cef-other';
  if (pid === shellPid) return 'shell';
  const base = comm.split('/').pop();
  if (/^com\.apple\.WebKit\.(WebContent|GPU|Networking)/.test(base) && shellStartMs && Date.parse(lstart) >= shellStartMs - 3000) {
    return 'webkit-' + base.match(/WebKit\.(\w+)/)[1].toLowerCase();
  }
  if (/wsbg-terminal\/ollama/.test(comm)) return 'ollama';
  return null;
}
const psTimer = setInterval(samplePs, 1000);

// ---- start backend ----
const cp = fs.readFileSync(`${S}/cp.txt`, 'utf8').trim();
const javaArgs = ['-XX:+UseZGC', '-Xmx4g', '--enable-native-access=ALL-UNNAMED',
  '--add-opens=java.desktop/sun.awt=ALL-UNNAMED', '--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED',
  '--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED', '--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED',
  '-cp', `${REPO}/terminal/target/classes:${cp}`, 'de.bsommerfeld.wsbg.terminal.ui.AppMain'];
const env = { ...process.env, WSBG_OFFLINE: prod ? 'false' : 'true' };
if (mode === 'jcef') env.WSBG_FRAME_PROFILE = `${OUT}/frames.csv`;
if (mode === 'shell') env.WSBG_SHELL = 'external';
const hz120 = flags.includes('--hz120');
const backendLog = fs.openSync(`${OUT}/backend.log`, 'w');
const backend = spawn('java', javaArgs, { cwd: REPO, env, stdio: ['pipe', 'pipe', backendLog] });
backendPid = backend.pid; log('BACKEND_PID', backendPid, 'mode', mode, prod ? 'PROD' : 'OFFLINE');
let entryUrl = null; let buf = '';
backend.stdout.on('data', d => { const s = d.toString(); fs.writeSync(backendLog, s); buf += s;
  const m = buf.match(/WSBG_ENTRY_URL=(\S+)/); if (m && !entryUrl) { entryUrl = m[1]; log('ENTRY_URL', entryUrl); }
  const e = buf.match(/Entry URL: (\S+)/); if (e && !entryUrl && mode === 'jcef') { entryUrl = e[1]; log('ENTRY_URL', entryUrl); }
  if (buf.length > 200000) buf = buf.slice(-50000); });
backend.on('exit', c => log('backend exited', c));
marks.LAUNCH = now();

// ---- attach the page ----
let shell = null;
if (mode === 'jcef') {
  // wait for the CDP target, inject bench.js
  let target = null;
  for (let i = 0; i < 240 && !target; i++) {
    try { const list = await (await fetch('http://127.0.0.1:9222/json')).json();
      target = list.find(x => x.type === 'page' && x.url.startsWith('http://127.0.0.1') && !x.url.includes('devtools')); } catch {}
    if (!target) await sleep(250);
  }
  if (!target) { log('no CDP target'); process.exit(1); }
  marks.PAGE_SEEN = now();
  const ws = new WebSocket(target.webSocketDebuggerUrl); await new Promise(r => ws.onopen = r);
  let id = 0; const pending = new Map();
  ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
  const call = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
  if (waitMs) { log('waiting', waitMs, 'ms before the scenario'); await sleep(waitMs); }
  // Same as the shell: the script runs at document start (so it can wrap the page's timers) - install it, then reload.
  await call('Page.enable');
  const r = await call('Page.addScriptToEvaluateOnNewDocument', { source: benchJs });
  log('installed bench.js', JSON.stringify(r.result || r.error).slice(0, 120));
  await call('Page.reload', { ignoreCache: false });
} else {
  for (let i = 0; i < 480 && !entryUrl; i++) await sleep(250);
  if (!entryUrl) { log('backend never announced its URL'); process.exit(1); }
  if (waitMs) { log('waiting', waitMs, 'ms before the shell starts'); await sleep(waitMs); }
  shellStartMs = now();
  const shellLog = fs.openSync(`${OUT}/shell.log`, 'w');
  shell = spawn(`${REPO}/shell/target/debug/wsbg-shell`, ['--url', entryUrl, '--init-script', `${OUT}/bench.js`, '--pos', '100,60', ...(hz120 ? ['--hz120'] : [])], { stdio: ['ignore', shellLog, shellLog] });
  shellPid = shell.pid; log('SHELL_PID', shellPid);
  shell.on('exit', c => log('shell exited', c));
}

// ---- wait for the scenario ----
const maxTicks = 1200 + 4 * (warmS + longS);
for (let i = 0; i < maxTicks && !done; i++) await sleep(250);
log(done ? 'scenario done' : 'scenario TIMEOUT');
await sleep(2000);
clearInterval(psTimer); samplePs();

// ---- quit ----
if (shell) { shell.kill('SIGTERM'); await sleep(1500); }
backend.kill('SIGTERM');
for (let i = 0; i < 60 && backend.exitCode === null; i++) await sleep(250);
if (backend.exitCode === null) { log('backend still alive, SIGKILL'); backend.kill('SIGKILL'); }
try { execSync("pkill -f 'jcef Helper'"); } catch {}
collector.close();

// ---- report ----
const pct = (a, p) => { if (!a.length) return NaN; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))]; };
const f1 = x => Number.isFinite(x) ? x.toFixed(1) : '-';
const lines = [];
const P = (...a) => { lines.push(a.join(' ')); };
P(`# ${label}  mode=${mode}  ${prod ? 'PROD' : 'OFFLINE'}  ${new Date().toISOString()}`);
P(`launch→page seen: ${marks.PAGE_SEEN ? marks.PAGE_SEEN - marks.LAUNCH : '-'} ms   launch→intro gone: ${marks.INTRO_GONE ? marks.INTRO_GONE - marks.LAUNCH : '-'} ms${shellStartMs ? `   shell start→intro gone: ${marks.INTRO_GONE - shellStartMs} ms` : ''}`);
if (info) { P(`ua: ${info.ua}`); P(`viewport ${info.w}x${info.h} @${info.dpr}  nodes ${info.nodes}  fonts: ${info.fonts.join(', ')}`); P(`animations at idle: ${JSON.stringify(info.animations)}`); }
if (errors.length) P(`PAGE ERRORS: ${errors.join(' | ')}`);
const phases = ['LONG', 'IDLE', 'IDLE2', 'SCROLL', 'FULLPAINT', 'ANIM_WAVES', 'ANIM_COMP', 'ANIM_ROT', 'ANIM_TRANS', 'ANIM_FADE', 'ANIM_PAINT', 'IDLE_NOSAMPLER'];
P(''); P('phase        dur   rAF/s   gap p50   p95    p99    max   >33ms  >50ms | CPU % of one core: ' + ['backend-jvm', 'cef-gpu', 'cef-renderer', 'cef-other', 'shell', 'webkit-webcontent', 'webkit-gpu', 'webkit-networking', 'ollama'].join(' '));
const groupNames = ['backend-jvm', 'cef-gpu', 'cef-renderer', 'cef-other', 'shell', 'webkit-webcontent', 'webkit-gpu', 'webkit-networking', 'ollama'];
function cpuInWindow(a, b) {
  const res = {}; const byPid = {};
  for (const r of psRows) if (r.t >= a - 600 && r.t <= b + 600) (byPid[r.pid] ||= []).push(r);
  for (const pid in byPid) { const rows = byPid[pid].sort((x, y) => x.t - y.t); const g = rows[0].group; const dt = (rows[rows.length - 1].t - rows[0].t) / 1000; if (dt <= 0) continue; const dc = rows[rows.length - 1].cpu - rows[0].cpu; res[g] = (res[g] || 0) + 100 * dc / dt; }
  return res;
}
function rssAt(t) { const res = {}; const byPid = {}; for (const r of psRows) if (Math.abs(r.t - t) < 1500) byPid[r.pid] = r; for (const pid in byPid) res[byPid[pid].group] = (res[byPid[pid].group] || 0) + byPid[pid].rss; return res; }
for (const ph of phases) {
  const a = marks[ph + '_START'], b = marks[ph + '_END']; if (!a || !b) { P(`${ph.padEnd(11)} (missing)`); continue; }
  const g = raf[ph] || []; const dur = (b - a) / 1000; const cpu = cpuInWindow(a, b);
  P(`${ph.padEnd(11)} ${f1(dur).padStart(5)}s ${f1(g.length / dur).padStart(6)}  ${f1(pct(g, 50)).padStart(6)} ${f1(pct(g, 95)).padStart(6)} ${f1(pct(g, 99)).padStart(6)} ${f1(Math.max(0, ...g)).padStart(6)}  ${String(g.filter(x => x > 33).length).padStart(5)}  ${String(g.filter(x => x > 50).length).padStart(5)} | ${groupNames.map(n => f1(cpu[n] || 0).padStart(5)).join(' ')}`);
}
P(''); P('RSS MB at phase end (UI processes only): ' + phases.filter(ph => marks[ph + '_END']).map(ph => { const r = rssAt(marks[ph + '_END']); return ph + ' ' + f1(Object.entries(r).filter(([k]) => !/backend|ollama/.test(k)).reduce((s, [, v]) => s + v, 0)); }).join('  '));
if (timers) { P('slow page timers (callbacks >4 ms; n / max ms / total ms):'); for (const t of timers) P(`  n=${t.n} max=${t.max} total=${t.total}  ${t.src}`); }
if (raf.LONG) { const g = raf.LONG; P(`LONG window: ${(g.reduce((a, b) => a + b, 0) / 1000).toFixed(0)} s, gaps >50 ms: ${g.filter(x => x > 50).length}, >100 ms: ${g.filter(x => x > 100).length}, >250 ms: ${g.filter(x => x > 250).length}, worst: ${[...g].sort((a, b) => b - a).slice(0, 8).map(x => x.toFixed(0)).join(' ')} ms`); }
if (mode === 'jcef' && fs.existsSync(`${OUT}/frames.csv`)) {
  try {
    const blog = fs.readFileSync(`${OUT}/backend.log`, 'utf8').replace(/\x1b\[[0-9;]*m/g, '').split('\n');
    const opened = blog.find(l => l.includes('Browser window opened')); const mm = opened.match(/^(\d\d):(\d\d):(\d\d)\.(\d\d\d)/);
    const day = new Date(marks.LAUNCH); day.setHours(+mm[1], +mm[2], +mm[3], +mm[4]); let openedWall = day.getTime(); if (openedWall < marks.LAUNCH - 3600e3) openedWall += 86400e3;
    const C = []; for (const line of fs.readFileSync(`${OUT}/frames.csv`, 'utf8').split('\n')) if (line.startsWith('C,')) C.push(+line.split(',')[1] / 1000);
    const off = openedWall - C[0];
    P('delivered frames (OSR pipeline, frame profiler CSV; first frame aligned to "Browser window opened", ±0.5 s):');
    for (const ph of ['LONG', ...phases.filter(p => p !== 'LONG')]) { const a = marks[ph + '_START'], b = marks[ph + '_END']; if (!a || !b) continue;
      const rows = C.filter(t => t + off >= a && t + off <= b); const g = rows.slice(1).map((t, i) => t - rows[i]); const dur = (b - a) / 1000;
      P(`  ${ph.padEnd(14)} ${rows.length} frames (${f1(rows.length / dur)}/s)  gaps>50ms ${g.filter(x => x > 50).length}  >100ms ${g.filter(x => x > 100).length}  >250ms ${g.filter(x => x > 250).length}  p99 ${f1(pct(g, 99))}  worst ${[...g].sort((x, y) => y - x).slice(0, 6).map(x => x.toFixed(0)).join(' ')}`); }
  } catch (e) { P('frame CSV analysis failed: ' + e.message); }
}
P(''); P('long rAF gaps (>40 ms) per phase, seconds into the phase:');
for (const ph of phases) { const g = raf[ph] || []; let t = 0; const at = []; for (const x of g) { t += x; if (x > 40) at.push(`${(t / 1000).toFixed(2)}s:${x.toFixed(0)}`); } P(`  ${ph.padEnd(11)} ${at.join(' ') || '-'}`); }
P(''); const tEnd = marks.DONE || now(); const rss = rssAt(tEnd - 1000);
P('RSS MB at end: ' + Object.entries(rss).map(([k, v]) => `${k} ${f1(v)}`).join('  ') + `   | UI total (no backend/ollama): ${f1(Object.entries(rss).filter(([k]) => !/backend|ollama/.test(k)).reduce((s, [, v]) => s + v, 0))}`);
const rssPeak = {}; for (const r of psRows) { const key = r.t - (r.t % 1000); (rssPeak[key] ||= {})[r.group] = ((rssPeak[key] || {})[r.group] || 0) + r.rss; }
let peakUi = 0; for (const k in rssPeak) { const v = Object.entries(rssPeak[k]).filter(([g]) => !/backend|ollama/.test(g)).reduce((s, [, x]) => s + x, 0); if (v > peakUi) peakUi = v; }
P(`UI RSS peak during run: ${f1(peakUi)} MB`);
if (info) P('layout: ' + JSON.stringify(info.layout));
fs.writeFileSync(`${OUT}/report.txt`, lines.join('\n') + '\n');
fs.writeFileSync(`${OUT}/data.json`, JSON.stringify({ marks, raf, info, timers, psRows, errors }, null, 0));
console.log('\n' + lines.filter(l => !l.startsWith('layout:')).join('\n'));
process.exit(0);
