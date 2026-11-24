// Mess Machine (web): what every screen shares. State, the mess, the audio bridge, keys, sheets, the toast.
// The sound and every mess edit run the app's own engine code (engine.js): here on the page for edits, in the
// AudioWorklet for sound.
import * as T from './engine.js';
export { T };

export const BUILD = 'web 2';
export const LETTERS = 'ABCDEFGH';
export const LC = ['#FF4FA3', '#00BEB4', '#FFD23F', '#C4A1FF', '#7FD1FF', '#FF7A1A', '#B6FF3B', '#FF4A2E'];
export const NN = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
/** Engine.T_ targets */
export const TG = { PITCH: 0, FILTER: 1, RES: 2, VOL: 3, PAN: 4, ATT: 5, REL: 6, START: 7, RVB: 8, ECHO: 9, DIRT: 10, CRUSH: 11, CHORUS: 12, WOB: 13,
  GLIDE: 14, ROLL: 15, GATE: 16, RING: 17, STUT: 18, TAPE: 19, PUMP: 20, WARM: 21, PHASER: 22, FREEZE: 23 };
export const SCRATCH = 64;

export const $ = (s, r = document) => r.querySelector(s);
export const h = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v; else if (k === 'style') e.style.cssText = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v); else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
};
export const fill = (el, ...kids) => el.replaceChildren(...kids.flat(Infinity).filter((k) => k != null && k !== false));
export const store = { get(k, d) { try { const v = localStorage.getItem('mmweb.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('mmweb.' + k, JSON.stringify(v)); } catch {} } };

/* ================= state ================= */

export const S = {
  tab: 'play', take: null, bank: 0, page: 0, follow: true, playing: false, rec: false, click: store.get('click', true), countBars: store.get('countIn', 1),
  heard: { playing: false, pat: -1, step: -1, song: 0 }, pending: -1, loopAtBar: store.get('loopAtBar', true),
  stripOpen: false, songSide: false, paint: 0, copying: false, mode: null, pickFor: -1, faq: false, search: '', trail: [],
  // playing
  hold: store.get('holdOn', false), holdCount: store.get('holdN', 3), latched: [], swallow: new Set(),
  nr: false, nrRate: store.get('nrRate', 1), feel: store.get('feel', 1), nrStyle: store.get('nrStyle', 'cut'), nrPat: store.get('nrPat', ''), arp: store.get('arp', 'rep'),
  chord: store.get('chord', 'off'), lastChord: store.get('chordLast', 'triad'), chordPlay: store.get('chordPlay', true), chordSmooth: store.get('chordSmooth', true),
  lastVoiced: new Map(), lastChordText: '', drop: null,
  // pads and strips
  open: new Map(), base: new Map(), fxSend: new Map(), fxOut: new Set(), bypass: new Set(),
  padiano: store.get('padiano', { n: 12, rows: 1, inKey: false, size: 1 }),
  hot: store.get('hot', { up: 'edit', down: 'keys', left: 'sample', right: 'fx' }),
  // seq
  seq: 'steps', rollLen: 1, rollOct: 0,
  // synth
  synthPad: SCRATCH, synthFull: false, replaceArmed: -1,
  // look
  skin: store.get('skin', 'classic'), big: store.get('big', false),
};
export let M = null;          // the mess as the app writes it, parsed (read-only view; edits go through T)
export const U = { undo: [], redo: [] };
export const LIBS = { LIB: {}, ALIAS: {}, CABS: [], FAQ: [] };
export const trail = (s) => { S.trail.push(new Date().toISOString().slice(11, 19) + ' ' + s); if (S.trail.length > 40) S.trail.shift(); };
/** Screens register here so the shell can repaint them. */
export const UI = {};

/* ================= audio ================= */

export const A = { ctx: null, node: null, loaded: new Map(), main: new Map(), q: [] };
export function post(m, tr) { if (A.node) A.node.port.postMessage(m, tr || []); else A.q.push([m, tr]); }
export const call = (f, ...a) => post({ t: 'call', f, a });
export const wake = () => { if (A.ctx && A.ctx.state !== 'running') A.ctx.resume(); };
export function sendLive() { call('setLive', S.click, S.rec, false, S.nr, S.nrRate * S.feel, S.arp, S.nrStyle, S.nrPat); }
export function send() { post({ t: 'mess', json: T.messJson() }); }

/* ----- sounds: built-in keys load from snd/<chunk>.json (base64 FLAC, decoded by the app's own decoder) ----- */

export const entryOf = (key) => LIBS.LIB[key] || LIBS.LIB[LIBS.ALIAS[key]] || null;
const chunks = new Map();
let sndIndex = null;
async function chunk(f) {
  if (!sndIndex) sndIndex = fetch('snd/index.json').then((r) => r.json());
  const name = (await sndIndex)[f];
  if (!name) throw new Error('no chunk for ' + f);
  if (!chunks.has(name)) chunks.set(name, fetch('snd/' + name + '.json').then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); }));
  return chunks.get(name);
}
const own = new Map();   // bn: sounds made in this browser (mic takes, imports, bottles): key -> Uint8Array of WAV
/* Workbench chops: a region of another sound; no file of its own (the region rides in the mess as the chop's edit). */
const chops = store.get('chops', {});
export function addChop(base, title) {
  const id = 'chop:' + Date.now().toString(36) + String(Math.floor(Math.random() * 1000)).padStart(3, '0');
  chops[id] = { base, title }; store.set('chops', chops);
  LIBS.LIB[id] = { k: id, t: title, c: 'mine', f: '', ty: 'chop', q: [] };
  return id;
}
export const chopOf = (k) => (chops[k] ? chops[k].base : null);
export async function bytesOf(key) {
  if (own.has(key)) return own.get(key).slice(0);
  if (chops[key]) return bytesOf(chops[key].base);
  const e = entryOf(key);
  if (!e || !e.f) return null;
  const d = await chunk(e.f);
  const b64 = d[e.f]; if (!b64) return null;
  const s = atob(b64), u = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
  return u;
}
/** The worklet's engine gets the sound. */
export function ensure(key) {
  if (!key || key.startsWith('synth:')) return Promise.resolve(true);
  if (A.loaded.has(key)) return A.loaded.get(key);
  const e = entryOf(key);
  const p = bytesOf(key).then((u) => {
    if (!u) return false;
    post({ t: 'file', key, buf: u.buffer, bpm: (e && e.bpm) || 0, beats: (e && e.beats) || 0 }, [u.buffer]);
    return true;
  }).catch((err) => { console.warn('sound', key, err); A.loaded.delete(key); return false; });
  A.loaded.set(key, p);
  return p;
}
/** The page's own engine gets the sound too (waveforms, exports). */
export function ensureMain(key) {
  if (!key || key.startsWith('synth:')) return Promise.resolve(true);
  if (A.main.has(key)) return A.main.get(key);
  const e = entryOf(key);
  const p = bytesOf(key).then((u) => { if (!u) return false; return T.addFile(key, new Int8Array(u.buffer), (e && e.bpm) || 0, (e && e.beats) || 0) > 0; }).catch(() => false);
  A.main.set(key, p);
  return p;
}
export async function loadKit() {
  const keys = (M.kit || []).filter(Boolean);
  for (const s of Object.values(M.synths || {})) if (s.src) keys.push(s.src);
  await Promise.all(keys.map(ensure));
  call('refresh');
}
/** Keep a sound made here (WAV bytes) so it plays and survives a reload in this browser. */
export async function keepOwn(key, wav) {
  own.set(key, wav);
  try {
    const db = await idb();
    await new Promise((res, rej) => { const t = db.transaction('snd', 'readwrite'); t.objectStore('snd').put(wav, key); t.oncomplete = res; t.onerror = () => rej(t.error); });
  } catch (e) { console.warn('keep', e); }
}
let idbP = null;
function idb() {
  if (!idbP) idbP = new Promise((res, rej) => { const q = indexedDB.open('mmweb', 1); q.onupgradeneeded = () => q.result.createObjectStore('snd'); q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); });
  return idbP;
}
export async function loadOwn() {
  try {
    const db = await idb();
    await new Promise((res) => { const t = db.transaction('snd', 'readonly'); const st = t.objectStore('snd'); const c = st.openCursor();
      c.onsuccess = () => { const cur = c.result; if (!cur) return res(); own.set(cur.key, new Uint8Array(cur.value)); cur.continue(); }; c.onerror = () => res(); });
  } catch {}
  for (const k of own.keys()) LIBS.LIB[k] = LIBS.LIB[k] || { k, t: store.get('own.' + k, 'My sound'), c: 'mine', f: '', ty: 'mine', q: [] };
  for (const [k, c] of Object.entries(chops)) LIBS.LIB[k] = { k, t: c.title, c: 'mine', f: '', ty: 'chop', q: [] };
}
export const ownKeys = () => [...own.keys(), ...Object.keys(chops)];
export function forgetOwn(key) { own.delete(key); delete chops[key]; store.set('chops', chops); delete LIBS.LIB[key]; idb().then((db) => db.transaction('snd', 'readwrite').objectStore('snd').delete(key)).catch(() => {}); }

/* ================= the mess ================= */

export function reread() { M = JSON.parse(T.messJson()); }
/** Every change to the mess: one undo step, the worklet gets the new mess, autosave. */
export function edit(fn, label) {
  const before = T.messJson();
  const r = fn();
  const after = T.messJson();
  if (after !== before) {
    U.undo.push(before); if (U.undo.length > 80) U.undo.shift(); U.redo = [];
    if (label) trail(label);
    commit(after);
  }
  return r;
}
/** A live change (a drag): the sound follows now; begin() before and end() after make it one undo step. */
export const live = { before: null,
  begin() { if (this.before == null) this.before = T.messJson(); },
  move() { reread(); send(); },
  end(label) { const b = this.before; this.before = null; if (b == null) return; const a = T.messJson(); if (a !== b) { U.undo.push(b); if (U.undo.length > 80) U.undo.shift(); U.redo = []; if (label) trail(label); autosave(a); UI.top && UI.top(); } } };
export function commit(json) { reread(); send(); autosave(json); UI.restFx && UI.restFx(); UI.paint && UI.paint(); }
let saveTimer = 0;
export function autosave(json) { clearTimeout(saveTimer); saveTimer = setTimeout(() => store.set('current', json || T.messJson()), 400); }
export function closeTake() { if (S.take != null && !(S.rec && S.playing)) { if (S.take !== T.messJson()) { U.undo.push(S.take); if (U.undo.length > 80) U.undo.shift(); } S.take = null; UI.top && UI.top(); } }
export function doUndo() { if (!U.undo.length) return; U.redo.push(T.messJson()); T.setMess(U.undo.pop()); commit(); toast('Undone.'); trail('undo'); }
export function doRedo() { if (!U.redo.length) return; U.undo.push(T.messJson()); T.setMess(U.redo.pop()); commit(); toast('Redone.'); trail('redo'); }
export function openMess(json, name) {
  const norm = T.normalize(json);
  if (!norm) { toast('That file is not a mess I can read.'); return false; }
  U.undo.push(T.messJson()); T.setMess(norm); T.ensureScratch(); S.page = 0; commit(); loadKit(); toast('Opened ' + (name || M.name || 'the mess') + '.'); return true;
}

export const pat = () => M.pats[M.cur];
export const kitKey = (i) => (M.kit[i] || '');
export const optsOf = (pad) => JSON.parse(T.padOptsJson(pad));
export function setOpts(pad, fn, label) { edit(() => { const o = optsOf(pad); fn(o); T.setPadOptsJson(pad, JSON.stringify(o)); }, label); }
export const titleOf = (k) => {
  if (!k) return '';
  if (k.startsWith('synth:')) { const s = M && M.synths && M.synths[k]; return s ? s.name : 'Synth'; }
  const e = entryOf(k); return e ? e.t : k.replace(/^.*[/:]/, '').replace(/\.wav$/, '').replace(/-/g, ' ');
};
export const jarCol = (k) => { if (k.startsWith('synth:')) return '#A070E0'; const e = entryOf(k); const c = e && LIBS.CABS.find((x) => x.id === e.c); return c ? c.col : '#8E877D'; };
export const mix = (a, b, t) => { const p = (x) => [1, 3, 5].map((i) => parseInt(x.slice(i, i + 2), 16)); const A1 = p(a), B1 = p(b); return '#' + A1.map((v, i) => Math.round(v + (B1[i] - v) * t).toString(16).padStart(2, '0')).join(''); };
export const padCol = (k) => mix(jarCol(k).slice(0, 7), getComputedStyle(document.documentElement).getPropertyValue('--ink').trim() || '#18161A', 0.22);
export const stepOn = (P, k, j) => (P.steps[k] || '')[j] === '1';
export const accOf = (P, k, j) => ((P.acc && P.acc[k]) || '')[j] || '.';
export const notesAt = (P, k) => { const set = new Set(); const s = P.notes && P.notes[k]; if (s) for (const n of s.split(',')) { const st = parseInt(n, 10); if (!isNaN(st)) set.add(st); } return set; };
export const usedLoop = (P) => Object.values(P.steps).some((s) => s.includes('1')) || Object.values(P.notes || {}).some((s) => s.length);
export const songArr = () => { const s = M.song || ''; const a = []; for (let i = 0; i < 64; i++) { const c = s[i]; a.push(c && LETTERS.includes(c) ? LETTERS.indexOf(c) : -1); } return a; };
export const songLen = () => { const a = songArr(); let n = 0; a.forEach((v, i) => { if (v >= 0) n = i + 1; }); return n; };
export const noteName = (p) => NN[((p % 12) + 12) % 12] + (4 + Math.floor(p / 12));
export const pcCol = (k) => k < 4 ? mix('#00BEB4', '#FF4FA3', k / 4) : k < 8 ? mix('#FF4FA3', '#FFD23F', (k - 4) / 4) : mix('#FFD23F', '#FF4A2E', (k - 8) / 4);

/* ================= toast ================= */

let toastTimer = 0;
export function toast(msg, action, fn) {
  const t = $('#toast');
  fill(t, h('span', {}, msg), action ? h('button', { onclick: () => { t.classList.remove('on'); fn(); } }, action) : null);
  t.classList.add('on'); clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('on'), 3200);
}
export const sayUndo = (msg) => toast(msg, 'Undo', doUndo);

/* ================= keys, sliders, sheets ================= */

export function faqTap(tag, e) {
  if (!S.faq) return false;
  e.preventDefault(); e.stopPropagation();
  const line = LIBS.FAQ.find(([p]) => new RegExp('^' + p.split('*').map((x) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.+') + '$').test(tag));
  toast(line ? line[1] + ': ' + line[2] : 'No line for this one yet.');
  return true;
}
/** The panel key: bakelite cap in a metal bezel, a lamp in its lip (only on switches), a stamped word plate under. */
export function key(tag, word, { icon, tile, on, lamp, cls, cap, onclick, title, disabled, w, sw } = {}) {
  const el = h('button', { class: 'key ' + (cls || '') + (on ? ' on' : '') + (sw === false ? ' nolamp' : ''), 'data-faq': tag, 'aria-label': title || word, disabled,
    style: (lamp ? `--lamp:${lamp};` : '') + (w ? `--kw:${w}px;` : '') }, h('span', { class: 'cap' + (icon ? ' fill' : ''), style: tile ? `--tile:${tile}` : '' },
    h('i', {}, icon ? h('img', { src: 'icons/ic_' + icon + '.png', alt: '' }) : null, cap != null ? cap : (icon ? null : word)), h('b')),
  word && (icon || cap != null) ? h('span', { class: 'plate' }, word) : null);
  if (onclick) el.addEventListener('click', (e) => { if (faqTap(tag, e)) return; onclick(e); });
  return el;
}
/** A pick-one slider: a milled track with a raised block on the pick. */
export function pick(items, cur, onpick, { tag, cls } = {}) {
  return h('div', { class: 'slider ' + (cls || ''), 'data-faq': tag }, items.map(([v, label], i) => h('button', { class: v === cur ? 'on' : '', 'data-faq': tag ? tag + '-' + i : null,
    onclick: (e) => { if (tag && faqTap(tag, e)) return; onpick(v); } }, label)));
}
/** A quick swipe up on a key opens its settings (the app's settings flick). */
export function flick(el, fn) {
  let y0 = 0, t0 = 0;
  el.addEventListener('pointerdown', (e) => { y0 = e.clientY; t0 = performance.now(); });
  el.addEventListener('pointerup', (e) => { if (y0 - e.clientY >= 44 && performance.now() - t0 < 300) { el.dataset.flicked = '1'; fn(); setTimeout(() => delete el.dataset.flicked, 50); } });
  el.addEventListener('click', (e) => { if (el.dataset.flicked) { e.stopImmediatePropagation(); e.preventDefault(); } }, true);
  return el;
}
export function sheet(title, faq, ...body) {
  const sh = $('#sheet');
  fill(sh, h('div', { class: 'head' }, h('div', { class: 'h2' }, title), h('button', { class: 'closer', 'aria-label': 'Close', 'data-faq': faq, onclick: () => closeSheet() })), ...body);
  if (!sh.classList.contains('on')) history.pushState({ sheet: 1 }, '');
  sh.classList.add('on'); $('#scrim').classList.add('on');
  sh.scrollTop = 0;
  return sh;
}
export function closeSheet(fromBack) {
  if (!$('#sheet').classList.contains('on')) return;
  $('#sheet').classList.remove('on'); $('#scrim').classList.remove('on');
  if (fromBack !== true && history.state && history.state.sheet) history.back();
  UI.sheetClosed && UI.sheetClosed();
}
export function field(label, min, max, stepV, value, fmt, oninput, onchange, tag) {
  const out = h('output', {}, fmt(value));
  const r = h('input', { type: 'range', min, max, step: stepV, value, 'aria-label': label });
  r.addEventListener('input', () => { out.textContent = fmt(+r.value); oninput(+r.value); });
  r.addEventListener('change', () => onchange && onchange(+r.value));
  return h('div', { class: 'field', 'data-faq': tag }, h('label', {}, label), r, out);
}
/** A choice list in a sheet. */
export function chooser(title, items, cur, onpick) {
  sheet(title, 'pick-close', h('div', { class: 'list' }, items.map(([v, label]) => h('button', { class: 'btn' + (v === cur ? ' y' : ''), onclick: () => { closeSheet(); onpick(v); } }, label))));
}
export const pct = (v) => Math.round(v * 100) + '%';
