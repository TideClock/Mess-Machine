// The Play page: pads with banks, Hold, Chords, Mute, Drop, swipes, the XY sphere, and the FannyPack strips
// (Padiano, FX vials, LFO, XY) that open under a pad's row. Behaviour follows AppState, PadsScreen and FannyPack.
import { S, M, T, TG, A, h, fill, $, key, pick, flick, sheet, closeSheet, field, chooser, toast, sayUndo, edit, live, setOpts, optsOf, call, wake, ensure,
  kitKey, titleOf, padCol, store, trail, faqTap, noteName, UI, SCRATCH, pct, autosave, send, reread } from './core.js';

/* ================= melodic presses with Hold (AppState.melodicDown) ================= */

const holdSlots = () => (!S.hold ? 0 : S.holdCount === 0 ? 1 : S.holdCount);
export function melodicDown(id, pad, ps, mel) {
  wake();
  if (S.latched.includes(id)) { S.latched.splice(S.latched.indexOf(id), 1); call('unlatch', id); S.swallow.add(id); return; }
  if (S.hold && S.holdCount === 0) {
    for (const o of S.latched) call('unlatch', o);
    S.latched = [id]; call('down', id, pad, ps.join(','), mel, true, -1); return;
  }
  const latch = S.latched.length < holdSlots();
  if (latch) S.latched.push(id);
  call('down', id, pad, ps.join(','), mel, latch, -1);
}
export function melodicUp(id) { if (S.swallow.delete(id)) return; call('up', id); }
export function releaseHolds() { for (const id of S.latched) call('unlatch', id); S.latched = []; }
export function switchHold(v = !S.hold) { S.hold = v; store.set('holdOn', v); trail('hold ' + (v ? 'on' : 'off')); if (!v) releaseHolds(); UI.paint(); }
export function changeHoldCount(n) { S.holdCount = Math.max(0, Math.min(8, n)); store.set('holdN', S.holdCount); while (S.latched.length > holdSlots()) call('unlatch', S.latched.shift()); }

/* ================= chords (AppState.chordFor, voiced) ================= */

export const pianoId = (pad, p) => 10000 + pad * 200 + (p + 96);
export function chordFor(p) { return S.chord === 'off' ? [p] : T.chordFor(p, S.chord, M.scale, M.root).split(',').map(Number); }
function voiced(pad, ps) {
  if (!S.chordSmooth || ps.length < 3) return ps;
  const v = T.smooth(ps.join(','), (S.lastVoiced.get(pad) || []).join(',')).split(',').map(Number);
  S.lastVoiced.set(pad, v); return v;
}
export function pianoDown(pad, p) {
  if (!kitKey(pad)) return;
  const raw = chordFor(p);
  const ps = S.chordPlay ? voiced(pad, raw) : raw;
  S.lastChordText = ps.length > 1 ? T.chordName(p, S.chord, M.scale, M.root) + ' · ' + T.chordNotes(ps.join(',')) + (S.chordPlay ? '' : ' (showing)') : T.noteName(p);
  trail('piano ' + (pad + 1) + ' ' + T.noteName(p));
  melodicDown(pianoId(pad, p), pad, S.chordPlay ? ps : [p], true);
  return ps;
}
export const pianoUp = (pad, p) => melodicUp(pianoId(pad, p));
export function chordModeLabel(m = S.chord) {
  if (m === 'off') return 'single notes';
  if (m.startsWith('set:')) return m.slice(4);
  return { triad: 'triads', seventh: 'sevenths', sus: '1-4-5', power: 'power', octave: 'octaves' }[m] || m;
}
export function switchChords() {
  if (S.chord === 'off') S.chord = S.lastChord; else { S.lastChord = S.chord; S.chord = 'off'; store.set('chordLast', S.lastChord); }
  store.set('chord', S.chord); trail('chords ' + S.chord); S.lastVoiced.clear(); UI.paint();
}

/* ================= FX strip state (AppState.fxSend, fxOut, bypass) ================= */

export const FX_STRIP = [[TG.RVB, 'Verb', 'reverb'], [TG.ECHO, 'Echo', 'echo'], [TG.DIRT, 'Dirt', 'dirt'], [TG.CRUSH, 'Crush', 'crush'], [TG.CHORUS, 'Chor', 'chorus'],
  [TG.PUMP, 'Pump', 'pump'], [TG.WARM, 'Tape', 'tape'], [TG.PHASER, 'Phase', 'phaser'], [TG.FREEZE, 'Freeze', 'freeze'], [TG.FILTER, 'Cut', 'filter'], [TG.RES, 'Res', 'resonance']];
const FX_ALL = FX_STRIP.map((f) => f[0]);
const BUILT_IN_FX = [['Wash', { [TG.RVB]: .7, [TG.ECHO]: .3, [TG.CHORUS]: .2 }], ['Grit', { [TG.DIRT]: .6, [TG.CRUSH]: .3, [TG.FILTER]: .2 }],
  ['Tape', { [TG.WARM]: .6, [TG.CHORUS]: .3, [TG.FILTER]: .15 }], ['Space', { [TG.RVB]: .5, [TG.ECHO]: .5, [TG.PHASER]: .3 }], ['Dry', {}]];
const fxPresets = () => BUILT_IN_FX.concat(store.get('myFx', []));
const fxIn = (pad, t) => !S.bypass.has(pad) && !S.fxOut.has(pad + ':' + t);
const fxApply = (pad, t) => call('home', pad, t, fxIn(pad, t) ? (S.fxSend.get(pad + ':' + t) || 0) : 0);
/** After a mess loads or Undo: the faders go back to where the mess keeps them (the engine rests there itself). */
export function restFx() {
  S.fxSend.clear();
  for (const [p, o] of Object.entries(M.padOpts || {})) if (o.fx) for (const part of o.fx.split(';')) { const [t, v] = part.split('='); if (+v > 0) S.fxSend.set(p + ':' + t, +v); }
}
let keepTimer = 0;
function keepFxSoon() { clearTimeout(keepTimer); keepTimer = setTimeout(keepFx, 450); }
function keepFx() {
  const want = new Map();
  for (const [k, v] of S.fxSend) { const [p, t] = k.split(':').map(Number); if (v > 0) { if (!want.has(p)) want.set(p, []); want.get(p).push([t, v]); } }
  const pads = new Set([...want.keys(), ...Object.keys(M.padOpts || {}).map(Number).filter((p) => (M.padOpts[p].fx || '') !== '')]);
  const code = (p) => (want.get(p) || []).sort((a, b) => a[0] - b[0]).map(([t, v]) => t + '=' + Math.round(v * 1000) / 1000).join(';');
  const changed = [...pads].filter((p) => code(p) !== ((M.padOpts && M.padOpts[p] && M.padOpts[p].fx) || ''));
  if (!changed.length) return;
  edit(() => { for (const p of changed) { const o = optsOf(p); o.fx = code(p); T.setPadOptsJson(p, JSON.stringify(o)); } }, 'fx');
}
export function fxSendTo(pad, t, v) {
  const x = Math.max(0, Math.min(1, v));
  S.fxSend.set(pad + ':' + t, x);
  if (!fxIn(pad, t)) { if (S.bypass.has(pad)) { S.bypass.delete(pad); for (const o of FX_ALL) if (o !== t) S.fxOut.add(pad + ':' + o); } S.fxOut.delete(pad + ':' + t); }
  call('home', pad, t, x); keepFxSoon();
}
function toggleFxIn(pad, t) {
  if (S.bypass.has(pad)) { S.bypass.delete(pad); for (const o of FX_ALL) if (o !== t) S.fxOut.add(pad + ':' + o); S.fxOut.delete(pad + ':' + t); }
  else if (S.fxOut.has(pad + ':' + t)) S.fxOut.delete(pad + ':' + t); else S.fxOut.add(pad + ':' + t);
  for (const o of FX_ALL) fxApply(pad, o);
}
function toggleBypass(pad) { if (S.bypass.has(pad)) S.bypass.delete(pad); else S.bypass.add(pad); for (const o of FX_ALL) fxApply(pad, o); }
function loadFxPreset(pad, name) {
  const m = (fxPresets().find((x) => x[0] === name) || [])[1]; if (!m) return;
  for (const t of FX_ALL) { const v = m[t] || 0; S.fxSend.set(pad + ':' + t, v); call('home', pad, t, v); }
  S.bypass.delete(pad); for (const t of FX_ALL) S.fxOut.delete(pad + ':' + t);
  keepFx(); toast(name + ' on pad ' + (pad + 1) + '.');
}

/* ================= XY setups (Xy.kt) ================= */

export const XY_TARGETS = [[-1, 'Nothing', 0, 0, 1], [TG.PITCH, 'Pitch', 7, -24, 24], [TG.FILTER, 'Filter', 1, 0, 1], [TG.RES, 'Resonance', 1, 0, 1], [TG.VOL, 'Volume', -1, -1, 1],
  [TG.PAN, 'Pan', 1, -1, 1], [TG.ATT, 'Attack', .5, 0, 1], [TG.REL, 'Release', 1, 0, 2], [TG.START, 'Start point', .8, 0, .95], [TG.RVB, 'Reverb', 1, 0, 1], [TG.ECHO, 'Echo', 1, 0, 1],
  [TG.DIRT, 'Dirty', 1, 0, 1], [TG.CRUSH, 'Crush', 1, 0, 1], [TG.CHORUS, 'Chorus', 1, 0, 1], [TG.PUMP, 'Pump', 1, 0, 1], [TG.WARM, 'Tape warmth', 1, 0, 1], [TG.PHASER, 'Phaser', 1, 0, 1],
  [TG.WOB, 'Wobble', 1, 0, 1], [TG.GLIDE, 'Glide time', .5, 0, 1], [TG.ROLL, 'Repeat roll', 1, 0, 1], [TG.GATE, 'Gate', 1, 0, 1], [TG.RING, 'Ring mod', 1, 0, 1], [TG.STUT, 'Stutter', 1, 0, 1], [TG.TAPE, 'Tape stop', 1, 0, 1]];
const D = (target = -1, to = 0, from = 0) => ({ target, to, from });
export const XY_PRESETS = [
  { name: 'Sweep', four: false, dirs: [D(TG.FILTER, 1), D(TG.RES, 1), D(), D()], spring: true },
  { name: 'Dub', four: true, dirs: [D(TG.ECHO, 1), D(TG.RVB, 1), D(TG.FILTER, .9), D(TG.CRUSH, .8)], spring: true },
  { name: 'Bend', four: true, dirs: [D(TG.PITCH, 7), D(TG.CHORUS, 1), D(TG.PITCH, -12), D(TG.WOB, 1)], spring: true },
  { name: 'Lo-fi', four: false, dirs: [D(TG.CRUSH, 1), D(TG.DIRT, 1), D(), D()], spring: true },
  { name: 'Scrub', four: false, dirs: [D(TG.START, .9), D(TG.PITCH, 12), D(), D()], spring: true },
];
export function xyParse(s) {
  try {
    const f = s.split('|');
    const dirs = f[6].split(',').map((d) => { const x = d.split(':'); return D(parseInt(x[0], 10), parseFloat(x[1]), x[2] ? parseFloat(x[2]) : 0); });
    while (dirs.length < 4) dirs.push(D());
    return { name: f[0], four: f[1] === '4', dirs: dirs.slice(0, 4), spring: f[2] === 's', ring: f[3] === 'o', size: +f[4] || 1, steps: f[5] === 't' };
  } catch { return null; }
}
export function xyCode(x) {
  const n = (v) => (Number.isInteger(v) ? v.toFixed(1) : String(v));
  return [x.name.replace(/[|;]/g, ' '), x.four ? '4' : '2', x.spring ? 's' : 'k', x.ring ? 'o' : 'q', String(x.size == null ? 1 : x.size), x.steps === false ? 'f' : 't',
    x.dirs.map((d) => d.from ? `${d.target}:${n(d.to)}:${n(d.from)}` : `${d.target}:${n(d.to)}`).join(',')].join('|');
}
export function xyOf(pad) { const o = M.padOpts && M.padOpts[pad]; return (o && o.xy && xyParse(o.xy)) || XY_PRESETS[0]; }
/** XySetup.values: two-way fx, fy 0..1 from bottom left; four-way -1..1 from the middle. */
export function xyValues(x, fx, fy) {
  const out = new Map();
  const add = (d, amt) => { if (d.target >= 0 && (amt > 0 || d.from !== 0)) out.set(d.target, (out.get(d.target) || 0) + d.from + (d.to - d.from) * Math.max(0, Math.min(1, amt))); };
  if (!x.four) { add(x.dirs[0], fx); add(x.dirs[1], fy); }
  else { const dz = .12, z = (v) => (Math.abs(v) < dz ? 0 : (Math.abs(v) - dz) / (1 - dz)); if (fx > 0) add(x.dirs[0], z(fx)); else add(x.dirs[2], z(fx)); if (fy > 0) add(x.dirs[1], z(fy)); else add(x.dirs[3], z(fy)); }
  return out;
}
const xyTargets = (x) => new Set(x.dirs.slice(0, x.four ? 4 : 2).map((d) => d.target).filter((t) => t >= 0));
export function xyApply(pad, x, fx, fy) { const v = xyValues(x, fx, fy); for (const t of xyTargets(x)) { if (v.has(t)) call('mod', pad, t, v.get(t)); else call('release', pad, t); } }
export function xyLetGo(pad, x) { if (x.spring !== false) for (const t of xyTargets(x)) call('release', pad, t); }

/* ================= the pads ================= */

function padW() { const w = ($('#page') && $('#page').clientWidth) || 380; return (w - 24 - 36) / 4; }
function bankRow(tagBase, withClick) {
  const r = h('div', { class: 'bankrow' }, h('span', { class: 'word' }, 'Bank'),
    [0, 1, 2, 3].map((b) => bankKey(tagBase, b)));
  if (withClick) {
    r.append(key('loop-click', '', { cls: 'small', cap: 'Click', on: S.click, onclick: () => { S.click = !S.click; store.set('click', S.click); UI.sendLive(); UI.paint(); } }),
      key('loop-countin', '', { cls: 'small', cap: S.countBars ? 'Count ' + S.countBars : 'Count off', on: S.countBars > 0, onclick: () => { S.countBars = (S.countBars + 1) % 3; store.set('countIn', S.countBars); toast(S.countBars ? `With Rec on, Play counts in ${S.countBars} bar${S.countBars > 1 ? 's' : ''} first.` : 'No count-in.'); UI.paint(); } }));
  }
  return r;
}
/** A bank number: tap to switch; in Mute, hold it (500 ms) to mute or unmute the whole bank. */
function bankKey(tagBase, b) {
  const k = key(tagBase + b, '', { cap: String(b + 1), on: S.bank === b, onclick: () => { if (k.dataset.held) return; S.bank = b; UI.paint(); } });
  let timer = 0;
  k.addEventListener('pointerdown', () => { if (S.mode !== 'mute') return; timer = setTimeout(() => { k.dataset.held = '1'; muteBank(b); setTimeout(() => delete k.dataset.held, 400); }, 500); });
  k.addEventListener('pointerup', () => clearTimeout(timer)); k.addEventListener('pointerleave', () => clearTimeout(timer));
  return k;
}
function muteBank(b) {
  const keys = []; for (let d = 0; d < 16; d++) { const k = kitKey(b * 16 + d); if (k) keys.push(k); }
  if (!keys.length) return;
  const allMuted = keys.every((k) => (M.mutes || []).includes(k));
  edit(() => { for (const k of keys) if (allMuted === (M.mutes || []).includes(k)) T.toggleMute(k); }, 'mute bank');
  toast(`Bank ${b + 1} ${allMuted ? 'unmuted' : 'muted'}.`);
}
export { bankRow };

const opened = (pad) => S.open.get(pad) || new Set();
export function packOf(pad) { const o = M.padOpts && M.padOpts[pad]; return o && o.pack ? o.pack.split(',').filter(Boolean) : []; }
/** Open or shut one strip under a pad's row; opening a strip puts it in the pad's pack. */
export function toggleStrip(pad, strip) {
  if (!kitKey(pad)) { toast(`Put a sound on pad ${pad + 1} first.`); return; }
  const s = new Set(opened(pad));
  if (s.has(strip)) s.delete(strip); else { s.add(strip); const pk = packOf(pad); if (!pk.includes(strip)) setOpts(pad, (o) => { o.pack = pk.concat(strip).join(','); }); }
  if (s.size) S.open.set(pad, s); else S.open.delete(pad);
  trail(strip + ' ' + (s.has(strip) ? 'open' : 'shut') + ' ' + (pad + 1));
  UI.paint();
}
/** Pack: zip or unzip the picked pad's pack. An empty pack starts with Keys in it. */
export function togglePack(pad = M.sel) {
  if (!kitKey(pad)) { toast(`Put a sound on pad ${pad + 1} first.`); return; }
  if (S.open.has(pad)) { S.open.delete(pad); UI.paint(); return; }
  const pk = packOf(pad);
  if (!pk.length) { toggleStrip(pad, 'keys'); return; }
  S.open.set(pad, new Set(pk)); UI.paint();
}
export function openPackPicker(pad = M.sel) {
  if (!kitKey(pad)) { toast(`Put a sound on pad ${pad + 1} first.`); return; }
  const pk = packOf(pad);
  const names = [['keys', 'Keys (Padiano)'], ['fx', 'FX vials'], ['lfo', 'LFO'], ['xy', 'XY square']];
  sheet(`Pack · pad ${pad + 1}`, 'pack-close', h('p', { class: 'note' }, 'What rides in this pad\'s FannyPack, in order. Pack opens and shuts them all.'),
    h('div', { class: 'list' }, names.map(([id, n]) => h('button', { class: 'btn' + (pk.includes(id) ? ' y' : ''), 'data-faq': 'pack-' + id,
      onclick: () => { const now = packOf(pad); setOpts(pad, (o) => { o.pack = (now.includes(id) ? now.filter((x) => x !== id) : now.concat(id)).join(','); }, 'pack'); const s = new Set(packOf(pad)); if (s.size) S.open.set(pad, s); else S.open.delete(pad); UI.paint(); openPackPicker(pad); } }, n))));
}

export function renderPads(tab) {
  const grid = h('div', { class: 'pads' + (S.mode === 'mute' ? ' mutemode' : '') + (S.mode === 'edit' ? ' editmode' : '') + (S.drop ? ' dropmode' : ''), id: 'pads' });
  const room = padW() - 16;
  for (let r = 0; r < 4; r++) {
    for (let c = 0; c < 4; c++) grid.append(padEl(S.bank * 16 + r * 4 + c, room));
    for (let c = 0; c < 4; c++) {
      const i = S.bank * 16 + r * 4 + c;
      if (!S.open.has(i) || !kitKey(i)) continue;
      const order = packOf(i).concat(['keys', 'fx', 'lfo', 'xy']).filter((x, n, a) => a.indexOf(x) === n);
      for (const st of order) if (S.open.get(i).has(st)) grid.append(strip(i, st));
    }
  }
  fill(tab, bankRow('bank-', true), grid);
  wirePads(grid);
}
function padEl(i, room) {
  const k = kitKey(i);
  if (!k) return h('div', { class: 'pad empty', 'data-i': i, 'data-faq': 'pad-' + i }, h('span', { class: 'n' }, String(i + 1) + ' •'), h('span', { class: 'plus' }, '+'));
  const name = titleOf(k);
  const longest = Math.max(...name.split(/\s+/).map((w) => w.length));
  // tokens.json padFaces.nameSize
  const fs = Math.max(7, Math.min(Math.max(11.5, Math.min(15, room / 6)), room * 1.13 / longest * 0.92));
  const o = (M.padOpts && M.padOpts[i]) || {};
  const marks = [];
  if (o.lfo && o.lfo.startsWith('1')) marks.push('var(--yellow)');
  if (Object.keys(M.pats[M.cur].motion || {}).some((m) => m.startsWith(i + ':'))) marks.push('var(--teal)');
  if (o.fx) marks.push('var(--riot)');
  const muted = (M.mutes || []).includes(k) || (M.solos && M.solos.length && !M.solos.includes(k));
  return h('div', { class: 'pad' + (M.sel === i ? ' sel' : '') + (muted ? ' muted' : '') + (S.open.has(i) ? ' packed' : '') + (S.drop && S.drop.has(k) ? ' kept' : ''),
    style: `--pc:${padCol(k)};--fs:${fs.toFixed(1)}px`, 'data-i': i, 'data-faq': 'pad-' + i },
    h('span', { class: 'n' }, String(i + 1)), S.latched.includes(1000 + i) ? h('span', { class: 'h' }, 'H') : null,
    k.startsWith('synth:') ? h('span', { class: 'h', style: 'font-size:12px' }, '◎') : null,
    marks.length ? h('span', { class: 'marks' }, marks.map((c) => h('i', { style: 'background:' + c }))) : null,
    h('span', { class: 'nm' }, name));
}

const touches = new Map();
function wirePads(grid) {
  grid.addEventListener('pointerdown', (e) => {
    const el = e.target.closest('.pad'); if (!el || !grid.contains(el)) return;
    e.preventDefault();
    const i = +el.dataset.i, k = kitKey(i);
    if (S.faq) { faqTap('pad-' + i, e); return; }
    try { grid.setPointerCapture(e.pointerId); } catch {}
    const t = { i, k, el, x0: e.clientX, y0: e.clientY, t0: performance.now(), id: (e.pointerId % 900) + 1, cancelled: false, sounded: false, done: false, path: [[e.clientX, e.clientY]], turn: 0, sphere: null };
    touches.set(e.pointerId, t);
    if (!k) return;                       // empty pads act on a still lift
    if (S.mode === 'mute') { edit(() => T.toggleMute(k), 'mute ' + titleOf(k)); t.done = true; return; }
    if (S.mode === 'edit') { S.mode = null; t.done = true; UI.openEdit(i); UI.paint(); return; }
    if (S.drop) { S.drop.add(k); call('setDrop', [...S.drop].join('\n')); el.classList.add('kept'); }
    wake();
    if (S.hold) { melodicDown(1000 + i, i, [0], false); t.done = true; t.held = true; UI.paint(); return; }
    call('down', t.id, i, '0', false, false, -1); t.sounded = true;
    el.classList.add('hit');
    if (navigator.vibrate && store.get('buzz', true)) try { navigator.vibrate(8); } catch {}
    if (M.sel !== i) { T.setSel(i); M.sel = i; for (const p of grid.querySelectorAll('.pad.sel')) p.classList.remove('sel'); el.classList.add('sel'); autosave(); UI.dock && UI.dock(); }
  });
  grid.addEventListener('pointermove', (e) => {
    const t = touches.get(e.pointerId); if (!t || t.done || !t.k) return;
    const dx = e.clientX - t.x0, dy = e.clientY - t.y0, dist = Math.hypot(dx, dy), dt = performance.now() - t.t0;
    if (t.sphere) { sphereMove(t, e); return; }
    // a circle drawn on a pad turns it into a sphere: path over 40 px whose turning passes 5 radians
    const last = t.path[t.path.length - 1];
    if (Math.hypot(e.clientX - last[0], e.clientY - last[1]) > 4) {
      t.path.push([e.clientX, e.clientY]);
      const n = t.path.length;
      if (n >= 3) { const [a, b, c] = [t.path[n - 3], t.path[n - 2], t.path[n - 1]]; let d = Math.atan2(c[1] - b[1], c[0] - b[0]) - Math.atan2(b[1] - a[1], b[0] - a[0]); while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; t.turn += d; }
      if (Math.abs(t.turn) > 5) { startSphere(t); return; }
    }
    if (t.sounded && !t.cancelled && dist >= 24 && dt < 300) { call('cancel', t.id); t.cancelled = true; }
    if (dist >= 44 && dt < 300) {
      t.done = true; t.el.classList.remove('hit');
      const dir = Math.abs(dy) > Math.abs(dx) * 2 ? (dy < 0 ? 'up' : 'down') : Math.abs(dx) > Math.abs(dy) * 2 ? (dx < 0 ? 'left' : 'right') : null;
      if (dir) hotKey(S.hot[dir] || 'none', t.i);
    }
  });
  const lift = (e) => {
    const t = touches.get(e.pointerId); if (!t) return;
    touches.delete(e.pointerId);
    t.el.classList.remove('hit');
    if (t.sphere) { xyLetGo(t.i, t.sphere.x); t.sphere.el.remove(); }
    if (t.sounded) call('up', t.id);
    if (!t.k && Math.hypot(e.clientX - t.x0, e.clientY - t.y0) < 12) {
      const r = t.el.getBoundingClientRect(), x = (e.clientX - r.left) / r.width, y = (e.clientY - r.top) / r.height;
      if (y < 0.92 - x) UI.recordToPad(t.i);
      else { S.pickFor = t.i; S.tab = 'sounds'; UI.paint(); toast(`Pick a sound for pad ${t.i + 1}.`); }
    }
  };
  grid.addEventListener('pointerup', lift); grid.addEventListener('pointercancel', lift);
}
/** HotKeys (Settings): one action per swipe direction off a pad. The same swipe again closes what it opened. */
function hotKey(what, i) {
  if (what === 'edit') UI.openEdit(i);
  else if (what === 'sample') UI.openSample(i);
  else if (what === 'keys') toggleStrip(i, 'keys');
  else if (what === 'fx') toggleStrip(i, 'fx');
  else if (what === 'lfo') toggleStrip(i, 'lfo');
  else if (what === 'xy') toggleStrip(i, 'xy');
  else if (what === 'mute') { const k = kitKey(i); edit(() => T.toggleMute(k), 'mute'); }
}
function startSphere(t) {
  if (t.sounded && !t.cancelled) { /* the hit keeps sounding: the sphere bends it */ }
  const x = xyOf(t.i);
  const ball = h('div', { class: 'sphere' }); document.body.append(ball);
  const xs = t.path.map((p) => p[0]), ys = t.path.map((p) => p[1]);
  const cx = (Math.min(...xs) + Math.max(...xs)) / 2, cy = (Math.min(...ys) + Math.max(...ys)) / 2;
  t.sphere = { x, el: ball, cx, cy };
  trail('sphere ' + (t.i + 1) + ' ' + x.name);
  toast(`Sphere: ${x.name}. Push it around; lift to let go.`);
}
function sphereMove(t, e) {
  const R = 70, s = t.sphere;
  const rx = Math.max(-1, Math.min(1, (e.clientX - s.cx) / R)), ry = Math.max(-1, Math.min(1, -(e.clientY - s.cy) / R));
  s.el.style.transform = `translate(${e.clientX - 28}px,${e.clientY - 28}px)`;
  if (s.x.four) xyApply(t.i, s.x, rx, ry); else xyApply(t.i, s.x, (rx + 1) / 2, (ry + 1) / 2);
}

/* ================= the strips (FannyPack) ================= */

function strip(pad, what) {
  if (what === 'keys') return padiano(pad);
  if (what === 'fx') return fxStrip(pad);
  if (what === 'lfo') return lfoStrip(pad);
  if (what === 'xy') return xyStrip(pad);
  return null;
}
function stripHead(pad, extra) {
  const k = kitKey(pad);
  return h('div', { class: 'shead' }, h('span', { class: 'chip', style: `--pc:${padCol(k)}` }), h('span', { class: 'snm' }, `${pad + 1} ${titleOf(k)}`), extra);
}

/* ----- Padiano ----- */

export function padiano(pad, { synth } = {}) {
  const k = kitKey(pad);
  const o = (M.padOpts && M.padOpts[pad]) || {};
  const P = S.padiano;
  const base = S.base.get(pad) || 0;
  const head = h('div', { class: 'row', style: 'gap:5px;margin-bottom:6px;align-items:flex-start' },
    synth ? null : h('span', { class: 'chipname' }, h('span', { class: 'chip', style: `--pc:${padCol(k)}` }), `${pad + 1} ${titleOf(k)}`),
    key('pkeys-octdown-' + pad, '', { cap: 'Oct−', cls: 'mini', onclick: () => { S.base.set(pad, Math.max(-36, base - 12)); UI.paint(); } }),
    key('pkeys-octup-' + pad, '', { cap: 'Oct+', cls: 'mini', onclick: () => { S.base.set(pad, Math.min(24, base + 12)); UI.paint(); } }),
    key('glide-' + pad, '', { cap: 'Glide', cls: 'mini', on: !!o.glide, onclick: () => setOpts(pad, (x) => { x.glide = !x.glide; }, 'glide') }),
    key('piano-xy-' + pad, '', { cap: 'XY', cls: 'mini', on: opened(pad).has('xy'), onclick: () => toggleStrip(pad, 'xy') }),
    key('piano-set-' + pad, '', { cap: '⚙', cls: 'mini', onclick: () => openPadianoSettings(pad) }));
  const sc = new Set(scaleOf());
  const rows = [];
  for (let r = P.rows - 1; r >= 0; r--) {
    const lo = base + 12 * r, keysRow = [];
    for (let p = lo; p <= lo + P.n; p++) {
      const pc = ((p - M.root) % 12 + 12) % 12, black = [1, 3, 6, 8, 10].includes(((p % 12) + 12) % 12), inS = sc.has(pc);
      if (P.inKey && !inS) continue;
      keysRow.push(h('div', { class: 'pk' + (black ? ' b' : ' w') + (inS ? ' in' : ' out'), 'data-p': p }, p === lo || p === lo + P.n ? h('small', {}, noteName(p)) : null, ((p % 12) + 12) % 12 === 0 ? h('i') : null));
    }
    rows.push(h('div', { class: 'pkrow', style: `--pc:${padCol(k)}` }, keysRow));
  }
  const kb = h('div', { class: 'piano size' + P.size, 'data-faq': 'piano-keys-' + pad }, rows);
  const info = h('div', { class: 'note', id: 'pinfo-' + pad }, `${NN(M.root)} ${M.scale.toLowerCase()} · ${chordModeLabel()} · ${noteName(base)} to ${noteName(base + 12 * (P.rows - 1) + P.n)}`);
  wirePiano(kb, pad, info);
  return h('div', { class: 'stripbox' + (synth ? ' synthkeys' : ''), style: `--pc:${padCol(k)}` }, head, kb, info);
}
const NN = (r) => ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'][r];
function scaleOf() { const map = { Chromatic: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11], Major: [0, 2, 4, 5, 7, 9, 11], Minor: [0, 2, 3, 5, 7, 8, 10], 'Harmonic minor': [0, 2, 3, 5, 7, 8, 11],
  Dorian: [0, 2, 3, 5, 7, 9, 10], Phrygian: [0, 1, 3, 5, 7, 8, 10], Mixolydian: [0, 2, 4, 5, 7, 9, 10], 'Major pentatonic': [0, 2, 4, 7, 9], 'Minor pentatonic': [0, 3, 5, 7, 10], Blues: [0, 3, 5, 6, 7, 10] };
  return map[M.scale] || map.Chromatic; }
function wirePiano(kb, pad, info) {
  const fingers = new Map();
  const at = (e) => { const el = document.elementFromPoint(e.clientX, e.clientY); const k = el && el.closest && el.closest('.pk'); return k && kb.contains(k) ? k : null; };
  const lit = (p, on) => { for (const k of kb.querySelectorAll(`.pk[data-p="${p}"]`)) k.classList.toggle('down', on); };
  const press = (f, k) => { const p = +k.dataset.p; f.p = p; const ps = pianoDown(pad, p) || [p]; for (const q of ps) lit(q, true); f.ps = ps; info.textContent = S.lastChordText; };
  const free = (f) => { if (f.p == null) return; pianoUp(pad, f.p); for (const q of f.ps || [f.p]) lit(q, false); f.p = null; };
  kb.addEventListener('pointerdown', (e) => { const k = at(e); if (!k) return; if (faqTap('piano-keys-' + pad, e)) return; e.preventDefault(); try { kb.setPointerCapture(e.pointerId); } catch {} const f = {}; fingers.set(e.pointerId, f); press(f, k); });
  kb.addEventListener('pointermove', (e) => { const f = fingers.get(e.pointerId); if (!f) return; const k = at(e); if (k && +k.dataset.p !== f.p) { free(f); press(f, k); } });
  const up = (e) => { const f = fingers.get(e.pointerId); if (!f) return; fingers.delete(e.pointerId); free(f); };
  kb.addEventListener('pointerup', up); kb.addEventListener('pointercancel', up);
}
function openPadianoSettings(pad) {
  const P = S.padiano, save = () => { store.set('padiano', P); UI.paint(); openPadianoSettings(pad); };
  const o = optsOf(pad);
  sheet('Padiano', 'piano-close-' + pad,
    h('p', { class: 'note' }, 'Rows go an octave up each. In key only shows just the notes of the mess\'s key.'),
    h('div', { class: 'word' }, 'Rows'), pick([[1, '1'], [2, '2'], [3, '3']], P.rows, (v) => { P.rows = v; save(); }),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'Size'), pick([[0, 'S'], [1, 'M'], [2, 'L'], [3, 'XL']], P.size, (v) => { P.size = v; save(); }),
    field('Notes shown', 7, 36, 1, P.n, (v) => v + ' semitones', (v) => { P.n = v; }, () => save()),
    h('div', { class: 'row', style: 'margin-top:10px' },
      h('button', { class: 'btn' + (P.inKey ? ' y' : ''), onclick: () => { P.inKey = !P.inKey; save(); } }, 'In key only'),
      h('button', { class: 'btn' + (o.legato ? ' y' : ''), onclick: () => { setOpts(pad, (x) => { x.legato = !x.legato; }, 'legato'); openPadianoSettings(pad); } }, 'Glide only when legato')),
    field('Glide time', 0, 1, 0.01, o.glideT, (v) => Math.round(v * 1000) + ' ms', (v) => { live.begin(); const x = optsOf(pad); x.glideT = v; T.setPadOptsJson(pad, JSON.stringify(x)); live.move(); }, () => live.end('glide time')));
}

/* ----- FX vials on one pad ----- */

function fxStrip(pad) {
  const vials = h('div', { class: 'pvials', 'data-faq': 'fxstrip-' + pad }, FX_STRIP.map(([t, name, icon]) => {
    const v = S.fxSend.get(pad + ':' + t) || 0;
    return h('div', { class: 'pv' + (fxIn(pad, t) ? '' : ' out'), 'data-t': t },
      h('div', { class: 'tube', style: `--amt:${v * 100}%` }, h('i')),
      h('button', { class: 'lamp' + (fxIn(pad, t) && v > 0 ? ' lit' : ''), 'aria-label': name + ' in or out', 'data-faq': 'fxin-' + t }),
      h('span', { class: 'vn' }, name));
  }));
  const strip = h('div', { class: 'stripbox fx' },
    h('div', { class: 'row', style: 'margin-bottom:8px' }, h('span', { class: 'word', style: 'color:var(--yellow)' }, `FX · pad ${pad + 1}`)),
    h('div', { class: 'row', style: 'margin-bottom:8px' },
      h('button', { class: 'btn', 'data-faq': 'fxpreset-load', onclick: () => chooser('Load FX', fxPresets().map((p) => [p[0], p[0]]), null, (n) => { loadFxPreset(pad, n); UI.paint(); }) }, 'Load ▾'),
      h('button', { class: 'btn', 'data-faq': 'fxpreset-save', onclick: () => saveFxPreset(pad) }, 'Save'),
      h('button', { class: 'btn' + (S.bypass.has(pad) ? ' y' : ''), 'data-faq': 'fx-bypass', onclick: () => { toggleBypass(pad); UI.paint(); } }, 'Bypass'),
      h('button', { class: 'btn r', onclick: () => { for (const t of FX_ALL) { S.fxSend.delete(pad + ':' + t); call('home', pad, t, 0); } keepFx(); UI.paint(); } }, 'All off')),
    vials);
  const drag = new Map();
  vials.addEventListener('pointerdown', (e) => {
    const pv = e.target.closest('.pv'); if (!pv) return;
    const t = +pv.dataset.t;
    if (e.target.closest('.lamp')) { if (faqTap('fxin-*', e)) return; toggleFxIn(pad, t); UI.paint(); return; }
    if (faqTap('fx-' + t, e)) return;
    e.preventDefault(); try { vials.setPointerCapture(e.pointerId); } catch {}
    drag.set(e.pointerId, pv); setVial(pv, e);
  });
  const setVial = (pv, e) => { const tube = pv.querySelector('.tube'), r = tube.getBoundingClientRect(); const v = Math.max(0, Math.min(1, 1 - (e.clientY - r.top) / r.height)); fxSendTo(pad, +pv.dataset.t, v); tube.style.setProperty('--amt', v * 100 + '%'); pv.querySelector('.lamp').classList.toggle('lit', v > 0); };
  vials.addEventListener('pointermove', (e) => { const pv = drag.get(e.pointerId); if (pv) setVial(pv, e); });
  const end = (e) => drag.delete(e.pointerId);
  vials.addEventListener('pointerup', end); vials.addEventListener('pointercancel', end);
  return strip;
}
function saveFxPreset(pad) {
  const m = {}; for (const t of FX_ALL) { const v = S.fxSend.get(pad + ':' + t) || 0; if (v > 0) m[t] = v; }
  const mine = store.get('myFx', []); let n = 1; while (fxPresets().some((p) => p[0] === 'Mine ' + n)) n++;
  mine.push(['Mine ' + n, m]); store.set('myFx', mine); toast(`Saved as Mine ${n}. Load it on any pad.`);
}

/* ----- LFO strip (FannyPack.LfoStrip) ----- */

const LFO_WAVES = [[0, 'Sine'], [1, 'Tri'], [2, 'Saw ↑'], [3, 'Saw ↓'], [4, 'Square'], [5, 'Random']];
const LFO_TARGETS = [[TG.FILTER, 'Filter'], [TG.RES, 'Resonance'], [TG.VOL, 'Volume'], [TG.PAN, 'Pan'], [TG.PITCH, 'Pitch'], [TG.RVB, 'Reverb'], [TG.ECHO, 'Echo'],
  [TG.DIRT, 'Dirty'], [TG.CRUSH, 'Crush'], [TG.CHORUS, 'Chorus'], [TG.PUMP, 'Pump'], [TG.WARM, 'Tape'], [TG.PHASER, 'Phaser']];
const LFO_STOPS = [[32, '2 bars'], [16, '1 bar'], [8, '1/2'], [4, '1/4'], [2, '1/8'], [1, '1/16'], [0.5, '1/32']];
const LFO_DEF = '0,0,4.0,1,0.5,0,0.55,0.25,0';
export function lfoOf(pad) { const o = (M.padOpts && M.padOpts[pad]) || {}; const f = (o.lfo || LFO_DEF).split(','); return { on: f[0] === '1', wave: +f[1], rate: +f[2], target: +f[3], depth: +f[4], type: +f[5], cut: +f[6], res: +f[7], retrig: f[8] === '1' }; }
const lfoCode = (l) => [l.on ? 1 : 0, l.wave, l.rate, l.target, l.depth, l.type, l.cut, l.res, l.retrig ? 1 : 0].join(',');
function setLfo(pad, fn, commitNow = true) {
  const o = optsOf(pad), l = lfoOf(pad); fn(l); o.lfo = lfoCode(l);
  if (commitNow) edit(() => T.setPadOptsJson(pad, JSON.stringify(o)), 'lfo');
  else { live.begin(); T.setPadOptsJson(pad, JSON.stringify(o)); live.move(); }
}
function lfoParts(rate) { const st = LFO_STOPS.map((s) => s[0]); for (const f of [1, 2 / 3, 1.5]) for (const b of st) if (Math.abs(b * f - rate) < 1e-6) return [b, f]; return [4, 1]; }
function lfoStrip(pad) {
  const l = lfoOf(pad), [base, feel] = lfoParts(l.rate);
  const wave = (LFO_WAVES.find((w) => w[0] === l.wave) || LFO_WAVES[0])[1], tgt = (LFO_TARGETS.find((t) => t[0] === l.target) || LFO_TARGETS[0])[1];
  const rates = h('div', { class: 'ratestrip', 'data-faq': 'lfo-rate-' + pad }, LFO_STOPS.map(([b, n]) => h('button', { class: b === base ? 'on' : '', onclick: () => setLfo(pad, (x) => { x.rate = b * feel; x.on = true; }) }, n)));
  const ft = TG.FILTER, rt = TG.RES;
  return h('div', { class: 'stripbox' },
    h('div', { class: 'row', style: 'margin-bottom:6px' }, h('span', { class: 'word', style: 'color:var(--yellow)' }, 'LFO'),
      key('lfo-on-' + pad, '', { cap: l.on ? 'On' : 'Off', cls: 'mini', on: l.on, lamp: 'var(--teal)', onclick: () => setLfo(pad, (x) => { x.on = !x.on; }) }),
      h('span', { style: 'flex:1' }), h('button', { class: 'btn', 'data-faq': 'lfo-more-' + pad, onclick: () => openLfoTray(pad) }, `${wave} · ${tgt} ▾`)),
    rates,
    pick([[1, 'Straight'], [2 / 3, 'Triplet'], [1.5, 'Dotted']], feel, (f) => setLfo(pad, (x) => { x.rate = base * f; })),
    field('Depth', 0, 1, 0.01, l.depth, pct, (v) => setLfo(pad, (x) => { x.depth = v; }, false), () => live.end('lfo depth'), 'lfo-depth'),
    l.target === ft || l.target === rt ? [
      field('Cutoff', 0, 1, 0.01, 1 - (S.fxSend.get(pad + ':' + ft) || 0), (v) => Math.round(90 * Math.pow(20000 / 90, v)) + ' Hz', (v) => fxSendTo(pad, ft, 1 - v)),
      field('Res', 0, 1, 0.01, S.fxSend.get(pad + ':' + rt) || 0, pct, (v) => fxSendTo(pad, rt, v))] : null);
}
function openLfoTray(pad) {
  const l = lfoOf(pad), o = optsOf(pad);
  const again = () => openLfoTray(pad);
  sheet(`LFO · pad ${pad + 1}`, 'lfo-close',
    pick(LFO_WAVES, l.wave, (w) => { setLfo(pad, (x) => { x.wave = w; x.on = true; }); again(); }, { tag: 'lfo-wave', cls: 'teal' }),
    h('div', { class: 'row', style: 'margin:10px 0' },
      h('button', { class: 'btn', 'data-faq': 'lfo-wobble-' + pad, onclick: () => { setLfo(pad, (x) => { x.on = true; x.wave = 0; x.rate = 4; x.target = TG.PITCH; x.depth = 0.03; x.retrig = false; }); again(); } }, 'Wobble'),
      h('button', { class: 'btn', 'data-faq': 'lfo-retrig-' + pad, onclick: () => { setLfo(pad, (x) => { x.retrig = !x.retrig; }); again(); } }, l.retrig ? 'Each hit' : 'With loop')),
    h('div', { class: 'word' }, 'Pad filter'),
    pick([[1, 'Lo'], [2, 'Hi'], [3, 'Band']], o.ftype || 1, (v) => { setOpts(pad, (x) => { x.ftype = v; }, 'filter type'); again(); }, { tag: 'lfo-type', cls: 'teal' }),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'What it moves'),
    h('div', { class: 'list' }, LFO_TARGETS.map(([t, n]) => h('button', { class: 'btn' + (l.target === t ? ' y' : ''), onclick: () => { setLfo(pad, (x) => { x.target = t; x.on = true; }); again(); } }, n))));
}

/* ----- XY square on one pad ----- */

function xyStrip(pad) {
  const x = xyOf(pad);
  const sq = h('div', { class: 'xy pad-xy', 'data-faq': 'xyp-' + pad }, h('span', { class: 'word lab' }, x.name + (x.four ? ' · 4-way' : '')), h('span', { class: 'dot' }));
  let id = null;
  const at = (e, on) => {
    const r = sq.getBoundingClientRect(), fx = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)), fy = Math.max(0, Math.min(1, 1 - (e.clientY - r.top) / r.height));
    sq.style.setProperty('--x', fx * 100 + '%'); sq.style.setProperty('--y', (1 - fy) * 100 + '%');
    if (on) { if (x.four) xyApply(pad, x, fx * 2 - 1, fy * 2 - 1); else xyApply(pad, x, fx, fy); } else xyLetGo(pad, x);
  };
  sq.addEventListener('pointerdown', (e) => { if (faqTap('xyp-' + pad, e)) return; e.preventDefault(); try { sq.setPointerCapture(e.pointerId); } catch {} id = e.pointerId; sq.classList.add('on'); at(e, true); });
  sq.addEventListener('pointermove', (e) => { if (e.pointerId === id) at(e, true); });
  const off = (e) => { if (e.pointerId !== id) return; id = null; sq.classList.remove('on'); at(e, false); };
  sq.addEventListener('pointerup', off); sq.addEventListener('pointercancel', off);
  return h('div', { class: 'stripbox' },
    h('div', { class: 'row', style: 'margin-bottom:6px' }, h('span', { class: 'word', style: 'color:var(--yellow)' }, `XY · pad ${pad + 1}`), h('span', { style: 'flex:1' }),
      h('button', { class: 'btn', 'data-faq': 'xy-setup', onclick: () => openXySetup(pad) }, x.name + ' ▾')),
    sq, h('p', { class: 'note' }, 'Hold a note or let the loop play, then drag here. Or draw a circle on the pad itself for a sphere.'));
}
export function openXySetup(pad) {
  const x = xyOf(pad);
  const set = (nx) => { setOpts(pad, (o) => { o.xy = xyCode(nx); }, 'xy setup'); openXySetup(pad); };
  const tname = (t) => (XY_TARGETS.find((q) => q[0] === t) || XY_TARGETS[0])[1];
  const dirNames = x.four ? ['Right', 'Up', 'Left', 'Down'] : ['Across (X)', 'Up (Y)'];
  sheet(`XY setup · pad ${pad + 1}`, 'xy-close',
    h('div', { class: 'list' }, XY_PRESETS.map((p) => h('button', { class: 'btn' + (p.name === x.name ? ' y' : ''), onclick: () => set(JSON.parse(JSON.stringify(p))) }, p.name))),
    pick([[false, '2-way'], [true, '4-way']], x.four, (v) => set({ ...x, four: v, name: x.name })),
    pick([[true, 'Spring back'], [false, 'Stay']], x.spring !== false, (v) => set({ ...x, spring: v })),
    dirNames.map((n, d) => h('div', { class: 'row', style: 'margin-top:8px' }, h('span', { class: 'word', style: 'min-width:7em' }, n),
      h('button', { class: 'btn', onclick: () => chooser(n + ' moves', XY_TARGETS.map((q) => [q[0], q[1]]), x.dirs[d].target, (t) => {
        const q = XY_TARGETS.find((y) => y[0] === t); const dirs = x.dirs.map((y) => ({ ...y })); dirs[d] = D(t, q ? q[2] : 0); set({ ...x, dirs, name: 'Mine' });
      }) }, tname(x.dirs[d].target) + ' ▾'))));
}

/* ================= Drop (held: only pads you touch keep sounding) ================= */

export function wireDrop(el) {
  el.addEventListener('pointerdown', (e) => { if (S.faq) return; e.preventDefault(); S.drop = new Set(); call('setDrop', ''); el.classList.add('down', 'on'); trail('drop'); UI.paint(); });
  const end = () => { if (!S.drop) return; S.drop = null; call('setDrop', '-'); el.classList.remove('down', 'on'); UI.paint(); };
  el.addEventListener('pointerup', end); el.addEventListener('pointercancel', end); el.addEventListener('pointerleave', end);
  el.addEventListener('click', (e) => { if (faqTap('drop-key', e)) return; });
  return el;
}

/* ================= Hold tray ================= */

export function openHoldTray() {
  sheet('Hold', 'hold-close',
    h('p', { class: 'note' }, 'With Hold on, this many notes keep playing after you lift (Last: only the newest).'),
    h('div', { class: 'row' },
      h('button', { class: 'btn', 'data-faq': 'hold-down', onclick: () => { changeHoldCount(S.holdCount - 1); openHoldTray(); } }, '−'),
      h('span', { class: 'lcd', style: 'max-width:120px' }, h('span', {}, S.holdCount === 0 ? 'LAST' : String(S.holdCount))),
      h('button', { class: 'btn', 'data-faq': 'hold-up', onclick: () => { changeHoldCount(S.holdCount + 1); openHoldTray(); } }, '+'),
      h('button', { class: 'btn r', 'data-faq': 'hold-release', onclick: () => { releaseHolds(); UI.paint(); toast('Let go of every held note.'); } }, 'Let go')));
}
