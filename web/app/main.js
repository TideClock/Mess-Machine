// Mess Machine (web). The screens follow the Android app (build 170); the sound and every mess edit run the app's
// own engine code (engine.js), here on the page for edits and in the AudioWorklet for sound.
import * as T from './engine.js';

const BUILD = 'web 1';
const LETTERS = 'ABCDEFGH';
const LC = ['#FF4FA3', '#00BEB4', '#FFD23F', '#C4A1FF', '#7FD1FF', '#FF7A1A', '#B6FF3B', '#FF4A2E'];
const INK = '#18161A';
const FXN = [['stutter', 'Stutter', '#FF4FA3'], ['echo', 'Echo', '#7FD1FF'], ['crush', 'Crush', '#B6FF3B'], ['tape', 'Tape stop', '#FFB347'],
  ['gate', 'Gate', '#00BEB4'], ['dirty', 'Dirty', '#FF7A1A'], ['ring', 'Ring mod', '#C4A1FF'], ['oct', 'Octave down', '#FFD23F'], ['rev', 'Reverse', '#FF4A2E']];
const RATES = [[4, '1/4'], [2, '1/8'], [1, '1/16'], [0.5, '1/32']];
const NN = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];

const $ = (s, r = document) => r.querySelector(s);
const h = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v; else if (k === 'style') e.style.cssText = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v); else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c != null && c !== false) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
};
const fill = (el, ...kids) => el.replaceChildren(...kids.flat().filter((k) => k != null && k !== false));
const store = { get(k, d) { try { const v = localStorage.getItem('mmweb.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('mmweb.' + k, JSON.stringify(v)); } catch {} } };

/* ================= state ================= */

const S = {
  tab: 'play', take: null, bank: 0, page: 0, follow: true, playing: false, rec: false, click: store.get('click', true), countBars: store.get('countIn', 1),
  heard: { playing: false, pat: -1, step: -1, song: 0 }, pending: -1, loopAtBar: store.get('loopAtBar', true),
  stripOpen: false, songSide: false, paint: 0, copying: false, mode: null, hold: false, latched: new Set(),
  nr: false, nrRate: store.get('nrRate', 1), feel: store.get('feel', 1), pickFor: -1, faq: false, search: '', trail: [],
};
let M = null;          // the mess as the app writes it, parsed (read-only view; edits go through T)
let undo = [], redo = [];
let LIB = {}, ALIAS = {}, CABS = [], FAQ = [];
const trail = (s) => { S.trail.push(new Date().toISOString().slice(11, 19) + ' ' + s); if (S.trail.length > 40) S.trail.shift(); };

/* ================= audio ================= */

const A = { ctx: null, node: null, ready: false, loaded: new Map(), q: [] };
function post(m, tr) { if (A.node) A.node.port.postMessage(m, tr || []); else A.q.push([m, tr]); }
const call = (f, ...a) => post({ t: 'call', f, a });

async function startAudio() {
  const ctx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
  A.ctx = ctx;
  await ctx.resume();
  await ctx.audioWorklet.addModule('worklet.js');
  const node = new AudioWorkletNode(ctx, 'mess-engine', { outputChannelCount: [2], numberOfOutputs: 1, numberOfInputs: 0 });
  node.port.onmessage = (e) => fromEngine(e.data);
  node.connect(ctx.destination);
  A.node = node;
  for (const [m, tr] of A.q.splice(0)) node.port.postMessage(m, tr || []);
  const lat = Math.round(((ctx.baseLatency || 0) + (ctx.outputLatency || 0)) * ctx.sampleRate);
  call('setLatency', lat);
  sendLive();
  send();
  await loadKit();
}

function fromEngine(m) {
  if (m.t === 'pos') {
    const [pl, pat, step, song] = m.h.split(',').map(Number);
    const was = S.heard;
    S.heard = { playing: !!pl, pat, step, song };
    if (step !== was.step || pat !== was.pat) onStep(was);
    if (m.err) console.warn('engine errors', m.err, m.last);
  } else if (m.t === 'rec') {
    // Build 105: while Rec plays, everything recorded is one take, and one Undo takes the whole take back.
    const before = T.messJson();
    for (const s of m.ev) { const r = JSON.parse(s); T.applyRec(r.type, r.pat, r.key, r.step, r.len, r.hit, r.strength, r.pitches.join(',')); }
    const after = T.messJson();
    if (after !== before) {
      if (S.take == null) { S.take = before; redo = []; trail('take'); }
      reread(); send(); autosave(after);
      if (S.tab === 'seq') renderSeq(); else renderTop();
    }
  } else if (m.t === 'err') console.warn('engine', m.what, m.err);
}

/** Sounds: built-in keys load from snd/<chunk>.json (base64 FLAC, decoded by the app's own decoder in the worklet). */
function entryOf(key) { return LIB[key] || LIB[ALIAS[key]] || null; }
const chunks = new Map();
let sndIndex = null;
async function chunk(f) {
  if (!sndIndex) sndIndex = fetch('snd/index.json').then((r) => r.json());
  const name = (await sndIndex)[f];
  if (!chunks.has(name)) chunks.set(name, fetch('snd/' + name + '.json').then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); }));
  return chunks.get(name);
}
function ensure(key) {
  if (!key || key.startsWith('synth:')) return Promise.resolve(true);
  if (A.loaded.has(key)) return A.loaded.get(key);
  const e = entryOf(key);
  if (!e) return Promise.resolve(false);
  const p = chunk(e.f).then((d) => {
    const b64 = d[e.f]; if (!b64) throw new Error('not in its chunk');
    const s = atob(b64), u = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
    post({ t: 'file', key, buf: u.buffer, bpm: e.bpm || 0, beats: e.beats || 0 }, [u.buffer]);
    return true;
  }).catch((err) => { console.warn('sound', key, err); A.loaded.delete(key); return false; });
  A.loaded.set(key, p);
  return p;
}
async function loadKit() {
  const keys = (M.kit || []).filter(Boolean);
  await Promise.all(keys.map(ensure));
  call('refresh');
}

function sendLive() { call('setLive', S.click, S.rec, false, S.nr, S.nrRate * S.feel, 'rep', 'cut', ''); }
function send() { post({ t: 'mess', json: T.messJson() }); }

/* ================= the mess ================= */

function reread() { M = JSON.parse(T.messJson()); }
/** Every change to the mess: one undo step, the worklet gets the new mess, autosave. */
function edit(fn, label) {
  const before = T.messJson();
  const r = fn();
  const after = T.messJson();
  if (after !== before) {
    undo.push(before); if (undo.length > 80) undo.shift(); redo = [];
    if (label) trail(label);
    commit(after);
  }
  return r;
}
function closeTake() { if (S.take != null && !(S.rec && S.playing)) { if (S.take !== T.messJson()) { undo.push(S.take); if (undo.length > 80) undo.shift(); } S.take = null; renderTop(); } }
function commit(json) { reread(); send(); autosave(json); paint(); }
let saveTimer = 0;
function autosave(json) { clearTimeout(saveTimer); saveTimer = setTimeout(() => store.set('current', json || T.messJson()), 400); }
function doUndo() { if (!undo.length) return; redo.push(T.messJson()); T.setMess(undo.pop()); commit(); toast('Undone.'); trail('undo'); }
function doRedo() { if (!redo.length) return; undo.push(T.messJson()); T.setMess(redo.pop()); commit(); toast('Redone.'); trail('redo'); }
function open(json, name) {
  const norm = T.normalize(json);
  if (!norm) { toast('That file is not a mess I can read.'); return false; }
  undo.push(T.messJson()); T.setMess(norm); S.page = 0; commit(); loadKit(); toast('Opened ' + (name || M.name || 'the mess') + '.'); return true;
}

const pat = () => M.pats[M.cur];
const kitKey = (i) => (M.kit[i] || '');
const titleOf = (k) => { if (!k) return ''; if (k.startsWith('synth:')) return 'Synth ' + k.slice(6); const e = entryOf(k); return e ? e.t : k.replace(/^.*[/:]/, '').replace(/\.wav$/, '').replace(/-/g, ' '); };
const jarCol = (k) => { if (k.startsWith('synth:')) return '#A070E0'; const e = entryOf(k); const c = e && CABS.find((x) => x.id === e.c); return c ? c.col : '#8E877D'; };
const mix = (a, b, t) => { const p = (x) => [1, 3, 5].map((i) => parseInt(x.slice(i, i + 2), 16)); const A1 = p(a), B1 = p(b); return '#' + A1.map((v, i) => Math.round(v + (B1[i] - v) * t).toString(16).padStart(2, '0')).join(''); };
const padCol = (k) => mix(jarCol(k).slice(0, 7), INK, 0.22);
const stepOn = (P, k, j) => (P.steps[k] || '')[j] === '1';
const accOf = (P, k, j) => ((P.acc && P.acc[k]) || '')[j] || '.';
const notesAt = (P, k) => { const set = new Set(); const s = P.notes && P.notes[k]; if (s) for (const n of s.split(',')) { const st = parseInt(n, 10); if (!isNaN(st)) set.add(st); } return set; };
const usedLoop = (P) => Object.values(P.steps).some((s) => s.includes('1')) || Object.values(P.notes || {}).some((s) => s.length);
const songArr = () => { const s = M.song || ''; const a = []; for (let i = 0; i < 64; i++) { const c = s[i]; a.push(c && LETTERS.includes(c) ? LETTERS.indexOf(c) : -1); } return a; };
const songLen = () => { const a = songArr(); let n = 0; a.forEach((v, i) => { if (v >= 0) n = i + 1; }); return n; };

/* ================= the toast ================= */

let toastTimer = 0;
function toast(msg, action, fn) {
  const t = $('#toast');
  fill(t, h('span', {}, msg), action ? h('button', { onclick: () => { t.classList.remove('on'); fn(); } }, action) : null);
  t.classList.add('on'); clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('on'), 3200);
}
const sayUndo = (msg) => toast(msg, 'Undo', doUndo);

/* ================= keys ================= */

function key(tag, word, { icon, tile, on, lamp, cls, cap, onclick, title, disabled, w } = {}) {
  const el = h('button', { class: 'key ' + (cls || '') + (on ? ' on' : ''), 'data-faq': tag, 'aria-label': title || word, disabled,
    style: (lamp ? `--lamp:${lamp};` : '') + (w ? `--kw:${w}px;` : '') }, h('span', { class: 'cap' + (icon ? ' fill' : ''), style: tile ? `--tile:${tile}` : '' },
    h('i', {}, icon ? h('img', { src: 'icons/ic_' + icon + '.png', alt: '' }) : null, cap != null ? cap : (icon ? null : word)), h('b')),
  word && icon ? h('span', { class: 'plate' }, word) : (word && cap != null ? h('span', { class: 'plate' }, word) : null));
  if (onclick) el.addEventListener('click', (e) => { if (faqTap(tag, e)) return; onclick(e); });
  return el;
}
function faqTap(tag, e) {
  if (!S.faq) return false;
  e.preventDefault(); e.stopPropagation();
  const line = FAQ.find(([p]) => new RegExp('^' + p.split('*').map((x) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.+') + '$').test(tag));
  toast(line ? line[1] + ': ' + line[2] : 'No line for this one yet.');
  return true;
}

/* ================= top bar ================= */

function renderTop() {
  const t = $('#top');
  const P = pat();
  const lp = S.heard.playing && S.heard.pat >= 0 ? S.heard.pat : M.cur;
  const bars = P.len / 16;
  const pos = S.heard.playing && S.heard.step >= 0 ? `${LETTERS[lp]} ${Math.floor(S.heard.step / 16) + 1}.${Math.floor((S.heard.step % 16) / 4) + 1}` : 'LOOP ' + LETTERS[M.cur] + (bars > 1 ? ' ×' + bars : '');
  fill(t, 
    key('play', 'Play', { icon: 'play', tile: 'var(--t-transport)', on: S.playing, cls: 'brass', onclick: togglePlay }),
    key('stop', 'Stop', { icon: 'stop', tile: 'var(--t-transport)', onclick: stop }),
    key('rec', 'Rec', { icon: 'rec', tile: 'var(--t-transport)', on: S.rec, lamp: 'var(--pink)', cls: 'brass', onclick: toggleRec }),
    key('undo', 'Undo', { icon: 'undo', tile: 'var(--t-transport)', disabled: !undo.length, onclick: doUndo }),
    h('button', { class: 'lcd', 'data-faq': 'bpm', onclick: (e) => { if (!faqTap('bpm', e)) openTempo(); } }, h('span', {}, String(M.bpm), h('small', {}, S.heard.countIn ? 'COUNT ' + S.heard.countIn : 'BPM'))),
    loopLcd(pos),
    h('span', { class: 'gap' }),
    key('help-toggle', 'FAQ', { icon: 'what_the_faq', tile: 'var(--t-other)', on: S.faq, onclick: () => { S.faq = !S.faq; document.body.classList.toggle('faq', S.faq); toast(S.faq ? 'Tap anything to learn what it is. Tap FAQ again to play.' : 'Back to playing.'); renderTop(); } }),
    key('menu', 'Menu', { icon: 'settings', tile: 'var(--t-other)', onclick: openMenu }),
  );
}
function loopLcd(text) {
  const b = h('button', { class: 'lcd', 'data-faq': 'loop-open' }, h('span', {}, h('span', { style: 'font-size:15px' }, text)));
  let x0 = null;
  b.addEventListener('pointerdown', (e) => { x0 = e.clientX; });
  b.addEventListener('pointerup', (e) => {
    if (x0 == null) return; const dx = e.clientX - x0; x0 = null;
    if (faqTap('loop-open', e)) return;
    if (Math.abs(dx) >= 28) pickLoop((((S.pending >= 0 ? S.pending : M.cur) + (dx < 0 ? 1 : -1)) % 8 + 8) % 8);
    else { S.stripOpen = !S.stripOpen; renderStrip(); }
  });
  return b;
}

function togglePlay() {
  if (A.ctx) A.ctx.resume();
  if (S.playing) { call('pause'); S.playing = false; trail('pause'); }
  else { S.follow = true; call('play', S.rec ? S.countBars * 16 : 0); S.playing = true; trail('play'); }
  closeTake(); paint();
}
function stop() {
  if (!S.playing) { call('stop'); call('panic'); S.latched.clear(); toast('Stopped and hushed: held notes and effects all let go.'); }
  else { call('stop'); S.playing = false; }
  S.pending = -1; trail('stop'); closeTake(); paint();
}
function toggleRec() { S.rec = !S.rec; sendLive(); closeTake(); trail(S.rec ? 'rec on' : 'rec off'); if (S.rec && !S.playing && S.countBars) toast(`Rec is on. Play counts in ${S.countBars} bar${S.countBars > 1 ? 's' : ''} first.`); paint(); }

/* ================= playhead ================= */

let lastBeat = -1;
function onStep(was) {
  const { step, pat: pi, playing } = S.heard;
  if (S.playing && !playing && was.playing) { S.playing = false; closeTake(); }
  if (S.pending >= 0 && (!playing || (step >= 0 && step % 16 === 15))) { const k = S.pending; S.pending = -1; selectLoop(k); }
  const rule = $('#rule');
  if (playing && step >= 0 && step % 4 === 0 && step !== lastBeat) {
    lastBeat = step; rule.classList.add('beat'); rule.classList.toggle('one', step % 16 === 0);
    setTimeout(() => rule.classList.remove('beat', 'one'), Math.min(220, 60000 / M.bpm * 0.8));
  }
  if (playing && S.follow && pi === M.cur && step >= 0) { const pg = Math.floor(step / 16); if (pg !== S.page && S.tab === 'seq') { S.page = pg; renderSeq(); } }
  renderTop();
  markPlayhead();
  if (S.stripOpen) markStrip();
}
function markPlayhead() {
  const g = $('#grid'); if (!g) return;
  for (const c of g.querySelectorAll('.now')) c.classList.remove('now');
  const { step, pat: pi, playing } = S.heard;
  if (!playing || pi !== M.cur || Math.floor(step / 16) !== S.page) return;
  for (const c of g.querySelectorAll(`[data-j="${step}"]`)) c.classList.add('now');
}

/* ================= loop strip ================= */

function pickLoop(i) {
  if (S.copying) { S.copying = false; if (i !== M.cur) { edit(() => T.copyLoopTo(i), 'copy loop'); toast(`Loop ${LETTERS[M.cur]} copied onto ${LETTERS[i]}.`); } return; }
  if (i === M.cur && S.pending < 0) return;
  if (S.playing && S.loopAtBar && i !== M.cur) { S.pending = i; trail('loop ' + LETTERS[i] + ' at the bar'); renderStrip(); }
  else { S.pending = -1; selectLoop(i); }
}
function selectLoop(i) { T.setCur(i); reread(); send(); S.page = 0; trail('loop ' + LETTERS[i]); autosave(); paint(); }
function setLen(n, fill, msg) { if (edit(() => T.setLen(n, fill), 'bars ' + n / 16)) { S.page = Math.min(S.page, n / 16 - 1); toast(msg); } }

function renderStrip() {
  const st = $('#strip');
  st.classList.toggle('open', S.stripOpen);
  if (!S.stripOpen) { fill(st, ); return; }
  const side = h('div', { class: 'slider' },
    h('button', { class: S.songSide ? '' : 'on', 'data-faq': 'loop-tab-loop', onclick: (e) => { if (faqTap('loop-tab-loop', e)) return; S.songSide = false; renderStrip(); } }, 'Loop'),
    h('button', { class: S.songSide ? 'on' : '', 'data-faq': 'loop-song', onclick: (e) => { if (faqTap('loop-song', e)) return; S.songSide = true; renderStrip(); } }, 'Song'));
  const close = h('button', { class: 'closer', 'aria-label': 'Close the loop strip', 'data-faq': 'loop-close', onclick: (e) => { if (faqTap('loop-close', e)) return; S.stripOpen = false; renderStrip(); } });
  const letters = h('div', { class: 'letters' }, [...LETTERS].map((L, i) => h('button', {
    class: 'letter' + (usedLoop(M.pats[i]) ? ' used' : '') + ((S.songSide ? S.paint === i : M.cur === i) ? ' cur' : '') + (S.pending === i ? ' wait' : ''),
    style: `--lc:${LC[i]}`, 'data-faq': 'loop-' + L, 'data-i': i,
    onclick: (e) => { if (faqTap('loop-*', e)) return; if (S.songSide) { S.paint = i; renderStrip(); } else pickLoop(i); } }, L)));
  const kids = [h('div', { class: 'row' }, side, close), letters];
  if (!S.songSide) {
    const P = pat(); const bars = P.len / 16;
    kids.push(h('div', { class: 'row' },
      key('loop-when', S.loopAtBar ? 'Wait bar' : 'Now', { icon: S.loopAtBar ? 'wait_bar' : 'now', tile: 'var(--t-other)', on: S.loopAtBar, onclick: () => { S.loopAtBar = !S.loopAtBar; store.set('loopAtBar', S.loopAtBar); renderStrip(); } }),
      h('span', { class: 'gap', style: 'flex:1' }), h('span', { class: 'word' }, bars + (bars > 1 ? ' bars' : ' bar')),
      key('loop-minus', '', { icon: 'bar_minus', tile: 'var(--t-other)', title: 'One bar less', onclick: () => bars <= 1 ? toast('One bar is the least.') : setLen(P.len - 16, true, `Loop ${LETTERS[M.cur]} is ${bars - 1} bar${bars - 1 > 1 ? 's' : ''} now.`) }),
      key('loop-plus', '', { icon: 'bar_plus', tile: 'var(--t-other)', title: 'One bar more', onclick: () => bars >= 8 ? toast('8 bars is the most a loop holds.') : (setLen(P.len + 16, false, `Loop ${LETTERS[M.cur]} is ${bars + 1} bars now; bar ${bars + 1} is empty.`), S.page = bars, paint()) }),
      key('loop-double', '', { icon: 'double', tile: 'var(--t-other)', title: 'Double', onclick: () => bars * 2 > 8 ? toast('Doubled it would pass 8 bars.') : setLen(P.len * 2, true, `Loop ${LETTERS[M.cur]} is ${bars * 2} bars now. What was there got copied into the new bars.`) })));
    kids.push(h('div', { class: 'row' },
      h('button', { class: 'btn' + (S.copying ? ' y' : ''), 'data-faq': 'loop-copy', onclick: (e) => { if (faqTap('loop-*', e)) return; S.copying = !S.copying; if (S.copying) toast('Tap the loop to copy ' + LETTERS[M.cur] + ' onto.'); renderStrip(); } }, 'Copy to'),
      h('button', { class: 'btn r', 'data-faq': 'loop-clear', onclick: (e) => { if (faqTap('loop-clear', e)) return; edit(() => T.clearLoop(), 'clear loop'); sayUndo(`Loop ${LETTERS[M.cur]} cleared.`); } }, 'Clear loop')));
  } else {
    const arr = songArr(), n = Math.min(64, Math.max(16, Math.ceil((songLen() + 1) / 16) * 16));
    kids.push(h('div', { class: 'slider' },
      h('button', { class: M.mode === 'song' ? '' : 'on', 'data-faq': 'song-play-loop', onclick: (e) => { if (faqTap('song-play-loop', e)) return; edit(() => T.setSongMode(false)); } }, 'Play loop'),
      h('button', { class: M.mode === 'song' ? 'on' : '', 'data-faq': 'song-play-song', onclick: (e) => { if (faqTap('song-play-song', e)) return; if (!songLen()) toast('Paint some loops into the song lane first.'); else edit(() => T.setSongMode(true)); } }, 'Play song')));
    kids.push(h('div', { class: 'song', 'data-faq': 'song-body' }, arr.slice(0, n).map((v, i) => h('button', {
      style: v >= 0 ? `background:${LC[v]}` : '', 'data-i': i, class: S.heard.playing && M.mode === 'song' && S.heard.song === i ? 'now' : '',
      onclick: (e) => { if (faqTap('song-body', e)) return; edit(() => T.setSongBar(i, v === S.paint ? -1 : S.paint), 'song bar'); } }, v >= 0 ? LETTERS[v] : ''))));
    kids.push(h('p', { class: 'note' }, 'Pick a letter above, then tap bars to paint it. Tap a bar again to clear it.'));
  }
  fill(st, ...kids);
}
function markStrip() {
  for (const b of document.querySelectorAll('#strip .letter')) b.classList.toggle('playing', S.heard.playing && +b.dataset.i === S.heard.pat);
}

/* ================= Play tab: pads ================= */

function bankRow(tagBase, withClick) {
  const r = h('div', { class: 'bankrow' }, h('span', { class: 'word' }, 'Bank'),
    [0, 1, 2, 3].map((b) => key(tagBase + b, '', { cap: String(b + 1), on: S.bank === b, onclick: () => { S.bank = b; paint(); } })));
  if (withClick) {
    r.append(key('loop-click', '', { cls: 'small', cap: 'Click', on: S.click, onclick: () => { S.click = !S.click; store.set('click', S.click); sendLive(); paint(); } }),
      key('loop-countin', '', { cls: 'small', cap: S.countBars ? 'Count ' + S.countBars : 'Count off', on: S.countBars > 0, onclick: () => { S.countBars = (S.countBars + 1) % 3; store.set('countIn', S.countBars); toast(S.countBars ? `With Rec on, Play counts in ${S.countBars} bar${S.countBars > 1 ? 's' : ''} first.` : 'No count-in.'); paint(); } }));
  }
  return r;
}

function renderPads() {
  const tab = $('#t-play');
  const grid = h('div', { class: 'pads' + (S.mode === 'mute' ? ' mutemode' : '') + (S.mode === 'edit' ? ' editmode' : ''), id: 'pads' });
  for (let d = 0; d < 16; d++) {
    const i = S.bank * 16 + d, k = kitKey(i);
    if (!k) { grid.append(h('div', { class: 'pad empty', 'data-i': i, 'data-faq': 'pad-' + i }, h('span', { class: 'n' }, String(i + 1) + ' •'), h('span', { class: 'plus' }, '+'))); continue; }
    const name = titleOf(k);
    // tokens.json padFaces.nameSize: room = pad - 16; cap = clamp(room / 6, 11.5, 15); size = max(7, min(cap, room * 1.13 / longest word))
    const longest = Math.max(...name.split(/\s+/).map((w) => w.length));
    const room = padW() - 16;
    const fs = Math.max(7, Math.min(Math.max(11.5, Math.min(15, room / 6)), room * 1.13 / longest * 0.92));
    grid.append(h('div', { class: 'pad' + (M.sel === i ? ' sel' : '') + ((M.mutes || []).includes(k) ? ' muted' : '') + (M.solos && M.solos.length && !M.solos.includes(k) ? ' muted' : ''),
      style: `--pc:${padCol(k)};--fs:${fs.toFixed(1)}px`, 'data-i': i, 'data-faq': 'pad-' + i },
      h('span', { class: 'n' }, String(i + 1)), S.latched.has(i) ? h('span', { class: 'h' }, 'H') : null, h('span', { class: 'nm' }, name)));
  }
  fill(tab, bankRow('bank-', true), grid);
  wirePads(grid);
}

function padW() { const w = ($('#page') && $('#page').clientWidth) || 380; return (w - 24 - 36) / 4; }
const touches = new Map();
function wirePads(grid) {
  grid.addEventListener('pointerdown', (e) => {
    const el = e.target.closest('.pad'); if (!el) return;
    e.preventDefault();
    const i = +el.dataset.i, k = kitKey(i);
    if (S.faq) { faqTap('pad-' + i, e); return; }
    grid.setPointerCapture?.(e.pointerId);
    const t = { i, k, el, x0: e.clientX, y0: e.clientY, t0: performance.now(), id: (e.pointerId % 900) + 1, cancelled: false, sounded: false, done: false };
    touches.set(e.pointerId, t);
    if (!k) return;                       // empty pads act on a still lift
    if (S.mode === 'mute') { edit(() => T.toggleMute(k), 'mute ' + titleOf(k)); t.done = true; return; }
    if (S.mode === 'edit') { S.mode = null; openEdit(i); t.done = true; paint(); return; }
    if (S.hold) {
      if (S.latched.has(i)) { call('unlatch', 1000 + i); S.latched.delete(i); }
      else { call('down', 1000 + i, i, '0', false, true, -1); S.latched.add(i); }
      t.done = true; renderPads(); return;
    }
    if (A.ctx && A.ctx.state !== 'running') A.ctx.resume();
    call('down', t.id, i, '0', false, false, -1); t.sounded = true;
    el.classList.add('hit');
    if (M.sel !== i) { T.setSel(i); M.sel = i; for (const p of grid.querySelectorAll('.pad.sel')) p.classList.remove('sel'); el.classList.add('sel'); autosave(); }
  });
  grid.addEventListener('pointermove', (e) => {
    const t = touches.get(e.pointerId); if (!t || t.done) return;
    const dx = e.clientX - t.x0, dy = e.clientY - t.y0, dist = Math.hypot(dx, dy), dt = performance.now() - t.t0;
    if (t.sounded && !t.cancelled && dist >= 24 && dt < 300) { call('cancel', t.id); t.cancelled = true; }
    if (dist >= 44 && dt < 300 && t.k) {
      t.done = true; t.el.classList.remove('hit');
      const dir = Math.abs(dy) > Math.abs(dx) * 2 ? (dy < 0 ? 'up' : 'down') : Math.abs(dx) > Math.abs(dy) * 2 ? (dx < 0 ? 'left' : 'right') : null;
      if (dir === 'up' || dir === 'left') openEdit(t.i);
      else if (dir === 'right') { S.tab = 'perform'; paint(); }
      else if (dir === 'down') toast('Padiano comes in a later web build.');
    }
  });
  const lift = (e) => {
    const t = touches.get(e.pointerId); if (!t) return;
    touches.delete(e.pointerId);
    t.el.classList.remove('hit');
    if (t.sounded) call('up', t.id);
    if (!t.k && Math.hypot(e.clientX - t.x0, e.clientY - t.y0) < 12) {
      const r = t.el.getBoundingClientRect(), x = (e.clientX - r.left) / r.width, y = (e.clientY - r.top) / r.height;
      if (y < 0.92 - x) toast('Recording onto a pad needs the mic, and this page cannot open it. Pick a sound instead (bottom corner).');
      else { S.pickFor = t.i; S.tab = 'sounds'; paint(); toast(`Pick a sound for pad ${t.i + 1}.`); }
    }
  };
  grid.addEventListener('pointerup', lift); grid.addEventListener('pointercancel', lift);
}

/* ================= the dock (Play and Synth) ================= */

function renderDock() {
  const d = $('#dock');
  const show = S.tab === 'play';
  d.style.display = show ? '' : 'none';
  if (!show) return;
  const rate = RATES.find((r) => r[0] === S.nrRate);
  fill(d, 
    key('dock-edit', 'Edit', { icon: 'edit', tile: 'var(--t-perform)', on: S.mode === 'edit', onclick: () => { S.mode = S.mode === 'edit' ? null : 'edit'; if (S.mode) toast('Tap a pad to edit it.'); paint(); } }),
    key('hold-toggle', 'Hold', { icon: 'hold', tile: 'var(--t-perform)', on: S.hold, onclick: () => { S.hold = !S.hold; if (!S.hold) { for (const i of S.latched) call('unlatch', 1000 + i); S.latched.clear(); } else toast('Hold: tap a pad to keep it going, tap it again to let go.'); paint(); } }),
    flickKey(key('repeat-toggle', rate ? rate[1] : 'Repeat', { icon: 'repeat', tile: 'var(--t-perform)', on: S.nr, onclick: () => { S.nr = !S.nr; sendLive(); paint(); } }), openRepeat),
    key('mute-key', 'Mute', { icon: 'mute', tile: 'var(--t-other)', on: S.mode === 'mute', lamp: 'var(--oxblood)', onclick: () => { S.mode = S.mode === 'mute' ? null : 'mute'; if (S.mode) toast('Mute: tap pads to mute or unmute them.'); paint(); } }),
    h('button', { class: 'lcd', 'data-faq': 'keys-panel', onclick: (e) => { if (!faqTap('keys-panel', e)) openKey(); } }, h('span', {}, `${NN[M.root]} ${M.scale.toUpperCase()}`.slice(0, 16))),
  );
}
/** A quick swipe up on some dock keys opens their settings. */
function flickKey(el, fn) {
  let y0 = 0, t0 = 0;
  el.addEventListener('pointerdown', (e) => { y0 = e.clientY; t0 = performance.now(); });
  el.addEventListener('pointerup', (e) => { if (y0 - e.clientY >= 44 && performance.now() - t0 < 300) { e.stopImmediatePropagation(); fn(); } }, true);
  return el;
}

/* ================= Seq tab: steps ================= */

function renderSeq() {
  const tab = $('#t-seq');
  const P = pat();
  const pages = P.len / 16;
  if (S.page >= pages) S.page = pages - 1;
  const rows = [];
  for (let d = 0; d < 16; d++) { const i = S.bank * 16 + d; if (kitKey(i)) rows.push(i); }
  const head = h('div', { class: 'row' }, h('span', { class: 'word', style: 'color:var(--dim)' }, 'Sequence'),
    h('div', { class: 'slider' }, h('button', { class: 'on', 'data-faq': 'seq-steps' }, 'Steps'),
      h('button', { 'data-faq': 'seq-roll', onclick: (e) => { if (!faqTap('seq-roll', e)) toast('The Roll comes in a later web build.'); } }, 'Roll')));
  const tools = h('div', { class: 'row', style: 'margin-top:10px' },
    h('button', { class: 'btn', 'data-faq': 'random', onclick: (e) => { if (faqTap('random', e)) return; if (!edit(() => T.randomDrums(S.bank), 'random drums')) toast(`Bank ${S.bank + 1} is empty.`); } }, 'Random drums'),
    h('button', { class: 'btn r', 'data-faq': 'clear-all-notes', onclick: (e) => { if (faqTap('clear-all-notes', e)) return; edit(() => T.clearNotes(), 'clear notes'); sayUndo(`Pitches and roll notes cleared from loop ${LETTERS[M.cur]}.`); } }, 'Clear notes'));
  const barsRow = pages > 1 ? h('div', { class: 'bars' }, Array.from({ length: pages }, (_, b) => h('button', { class: b === S.page ? 'on' : '', 'data-faq': 'page-' + b,
    onclick: (e) => { if (faqTap('page-*', e)) return; S.page = b; S.follow = !S.playing; renderSeq(); } }, 'Bar ' + (b + 1)))) : null;
  const grid = h('div', { class: 'grid', id: 'grid', style: 'grid-template-columns:minmax(64px,96px) repeat(16,minmax(0,1fr))' });
  for (const i of rows) {
    const k = kitKey(i), pc = padCol(k), dots = notesAt(P, k);
    grid.append(h('div', { class: 'nm' + (M.sel === i ? ' sel' : ''), style: `--pc:${pc}`, 'data-faq': 'steps-name-' + i, 'data-pad': i }, String(i + 1), ' ', titleOf(k)));
    for (let c = 0; c < 16; c++) {
      const j = S.page * 16 + c, on = stepOn(P, k, j), a = accOf(P, k, j);
      grid.append(h('div', { class: 'c' + (c % 8 >= 4 ? ' q' : '') + (on ? ' on' : '') + (on && a === 'a' ? ' acc' : '') + (on && a === 'g' ? ' ghost' : '') + (dots.has(j) ? ' dot' : ''),
        style: `--pc:${pc}`, 'data-k': k, 'data-j': j, 'data-faq': 'steps-' + i }));
    }
  }
  const empty = rows.length ? null : h('p', { class: 'note' }, `Bank ${S.bank + 1} has no sounds yet. Put some on its pads first.`);
  fill(tab, head, bankRow('steps-bank-', false), tools, barsRow, grid, empty);
  wireGrid(grid);
  markPlayhead();
}
function wireGrid(grid) {
  let g = null;
  grid.addEventListener('pointerdown', (e) => {
    const nm = e.target.closest('.nm');
    if (nm) { if (faqTap(nm.dataset.faq, e)) return; const i = +nm.dataset.pad; T.setSel(i); M.sel = i; call('preview', kitKey(i), 0, false); renderSeq(); return; }
    const c = e.target.closest('.c'); if (!c) return;
    if (faqTap(c.dataset.faq, e)) return;
    e.preventDefault(); grid.setPointerCapture?.(e.pointerId);
    const before = T.messJson();
    const k = c.dataset.k, j = +c.dataset.j;
    const st = T.toggleStep(k, j);
    g = { k, before, on: st >= 0, seen: new Set([j]) };
    if (st === 1 && !S.playing) call('preview', k, 0, false);
    reread(); send(); paintCells(k);
  });
  grid.addEventListener('pointermove', (e) => {
    if (!g) return;
    const el = document.elementFromPoint(e.clientX, e.clientY);
    const c = el && el.closest && el.closest('#grid .c');
    if (!c || c.dataset.k !== g.k) return;
    const j = +c.dataset.j; if (g.seen.has(j)) return;
    g.seen.add(j); T.paintStep(g.k, j, g.on); reread(); send(); paintCells(g.k);
  });
  const end = () => {
    if (!g) return;
    const after = T.messJson();
    if (after !== g.before) { undo.push(g.before); if (undo.length > 80) undo.shift(); redo = []; trail('steps ' + titleOf(g.k)); autosave(after); renderTop(); }
    g = null;
  };
  grid.addEventListener('pointerup', end); grid.addEventListener('pointercancel', end);
}
function paintCells(k) {
  const P = pat(), dots = notesAt(P, k);
  for (const c of document.querySelectorAll(`#grid .c[data-k="${CSS.escape(k)}"]`)) {
    const j = +c.dataset.j, on = stepOn(P, k, j), a = accOf(P, k, j);
    c.classList.toggle('on', on); c.classList.toggle('acc', on && a === 'a'); c.classList.toggle('ghost', on && a === 'g'); c.classList.toggle('dot', dots.has(j));
  }
}

/* ================= Perform tab ================= */

function renderPerform() {
  const tab = $('#t-perform');
  const vials = h('div', { class: 'vials', 'data-faq': 'master-throws' }, FXN.map(([f, name, col]) => h('div', { class: 'vial', 'data-f': f, style: `--vc:${col}`, 'data-faq': 'fx-' + f }, h('i'), h('span', {}, name))));
  const xy = h('div', { class: 'xy', 'data-faq': 'perform-xy' }, h('span', { class: 'word lab' }, 'Mud · Thin'));
  fill(tab, h('div', { class: 'h2' }, 'Master throws'), vials, xy,
    h('p', { class: 'note' }, 'Hold a vial to throw its effect on the whole mix; where your finger sits is how much. Drag in the square for the master filter: left is mud, right is thin.'));
  const vt = new Map();
  const amt = (el, e) => { const r = el.getBoundingClientRect(); return Math.max(0.05, Math.min(1, 1 - (e.clientY - r.top) / r.height)); };
  vials.addEventListener('pointerdown', (e) => {
    const v = e.target.closest('.vial'); if (!v) return;
    if (faqTap(v.dataset.faq, e)) return;
    e.preventDefault(); vials.setPointerCapture?.(e.pointerId);
    const a = amt(v, e); vt.set(e.pointerId, v);
    if (A.ctx) A.ctx.resume();
    call('fxAmount', v.dataset.f, a); call('fx', v.dataset.f, true);
    v.style.setProperty('--amt', a * 100 + '%'); trail('fx ' + v.dataset.f);
  });
  vials.addEventListener('pointermove', (e) => { const v = vt.get(e.pointerId); if (!v) return; const a = amt(v, e); call('fxAmount', v.dataset.f, a); v.style.setProperty('--amt', a * 100 + '%'); });
  const off = (e) => { const v = vt.get(e.pointerId); if (!v) return; vt.delete(e.pointerId); call('fx', v.dataset.f, false); v.style.setProperty('--amt', '0%'); };
  vials.addEventListener('pointerup', off); vials.addEventListener('pointercancel', off);
  const xyAt = (e, on) => { const r = xy.getBoundingClientRect(); const x = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)), y = Math.max(0, Math.min(1, (e.clientY - r.top) / r.height));
    call('xy', x, 1 - y, on); xy.style.setProperty('--x', x * 100 + '%'); xy.style.setProperty('--y', y * 100 + '%'); };
  let xyId = null;
  xy.addEventListener('pointerdown', (e) => { if (faqTap('perform-xy', e)) return; e.preventDefault(); xy.setPointerCapture?.(e.pointerId); xyId = e.pointerId; xyAt(e, true); });
  xy.addEventListener('pointermove', (e) => { if (e.pointerId === xyId) xyAt(e, true); });
  const xyOff = (e) => { if (e.pointerId !== xyId) return; xyId = null; xyAt(e, false); };
  xy.addEventListener('pointerup', xyOff); xy.addEventListener('pointercancel', xyOff);
}

/* ================= Sounds tab ================= */

function renderSounds() {
  const tab = $('#t-sounds');
  const target = S.pickFor >= 0 ? S.pickFor : M.sel;
  const q = S.search.trim().toLowerCase();
  const onPad = new Set(M.kit.filter(Boolean));
  const search = h('input', { class: 'search', type: 'search', placeholder: 'Search sounds, types, feels', value: S.search, 'data-faq': 'sounds-search' });
  search.addEventListener('input', () => { S.search = search.value; renderSoundList(list, target, onPad); });
  const list = h('div');
  fill(tab, h('div', { class: 'h2' }, S.pickFor >= 0 ? `A sound for pad ${S.pickFor + 1}` : 'Sounds'), search, list);
  renderSoundList(list, target, onPad);
}
function renderSoundList(list, target, onPad) {
  const q = S.search.trim().toLowerCase();
  const sounds = Object.values(LIB);
  fill(list, ...CABS.map((c) => {
    const items = sounds.filter((s) => s.c === c.id && (!q || (s.t + ' ' + (s.ty || '') + ' ' + (s.q || []).join(' ') + ' ' + (s.g || '') + ' ' + (s.mkey || '')).toLowerCase().includes(q)));
    if (!items.length) return null;
    const shown = q ? items : items.slice(0, c.open ? items.length : 24);
    const jar = h('div', { class: 'jar', style: `--jc:${c.col}` }, h('h3', {}, c.name, h('small', { class: 'note', style: 'margin:0' }, String(items.length))),
      shown.map((s) => h('div', { class: 'snd' + (onPad.has(s.k) ? ' onpad' : '') },
        h('div', { class: 't', 'data-faq': 'sound-row', onclick: async (e) => { if (faqTap('sound-*', e)) return; if (A.ctx) A.ctx.resume(); await ensure(s.k); call('preview', s.k, 0, false); } },
          s.t, h('small', {}, [s.ty, s.bpm ? Math.round(s.bpm) + ' bpm' : '', s.mkey || ''].filter(Boolean).join(' · '))),
        h('button', { class: 'put', 'data-faq': 'sound-put', onclick: (e) => { if (faqTap('sound-*', e)) return; putOnPad(s.k, target); } }, 'Pad ' + (target + 1)))));
    if (!q && items.length > shown.length) jar.append(h('button', { class: 'btn', onclick: () => { c.open = true; renderSoundList(list, target, onPad); } }, `All ${items.length}`));
    return jar;
  }).filter(Boolean));
}
async function putOnPad(k, pad) {
  if (pad < 0 || pad >= 64) pad = 0;
  await ensure(k);
  edit(() => T.assign(pad, k), 'sound on pad ' + (pad + 1));
  call('refresh');
  toast(`${titleOf(k)} is on pad ${pad + 1}.`);
  if (S.pickFor >= 0) { S.pickFor = -1; S.bank = Math.floor(pad / 16); S.tab = 'play'; paint(); }
}

/* ================= sheets ================= */

function sheet(title, faq, ...body) {
  const sh = $('#sheet');
  fill(sh, h('div', { class: 'head' }, h('div', { class: 'h2' }, title), h('button', { class: 'closer', 'aria-label': 'Close', 'data-faq': faq, onclick: closeSheet })), ...body);
  sh.classList.add('on'); $('#scrim').classList.add('on');
  history.pushState({ sheet: 1 }, '');
  return sh;
}
function closeSheet(fromBack) {
  if (!$('#sheet').classList.contains('on')) return;
  $('#sheet').classList.remove('on'); $('#scrim').classList.remove('on');
  if (fromBack !== true && history.state && history.state.sheet) history.back();
}
window.addEventListener('popstate', () => closeSheet(true));
document.getElementById('scrim').addEventListener('click', () => closeSheet());

function field(label, min, max, stepV, value, fmt, oninput, onchange) {
  const out = h('output', {}, fmt(value));
  const r = h('input', { type: 'range', min, max, step: stepV, value });
  r.addEventListener('input', () => { out.textContent = fmt(+r.value); oninput(+r.value); });
  r.addEventListener('change', () => onchange && onchange(+r.value));
  return h('div', { class: 'field' }, h('label', {}, label), r, out);
}

function openTempo() {
  let before = null;
  const live = (fn) => { if (before == null) before = T.messJson(); fn(); reread(); send(); renderTop(); };
  const done = () => { const after = T.messJson(); if (before && after !== before) { undo.push(before); redo = []; autosave(after); trail('tempo ' + M.bpm); } before = null; renderTop(); };
  const taps = [];
  sheet('Tempo', 'tempo-close',
    field('Tempo', 60, 180, 1, M.bpm, (v) => v + ' bpm', (v) => live(() => T.setBpm(v)), done),
    field('Swing', 0, 75, 1, M.swing, (v) => v + '%', (v) => live(() => T.setSwing(v)), done),
    h('div', { class: 'row', style: 'margin-top:10px' },
      h('button', { class: 'btn', onclick: () => { live(() => T.setBpm(M.bpm - 1)); done(); openTempo(); } }, '− 1'),
      h('button', { class: 'btn', onclick: () => { live(() => T.setBpm(M.bpm + 1)); done(); openTempo(); } }, '+ 1'),
      h('button', { class: 'btn y', 'data-faq': 'tempo-tap', onclick: () => {
        const now = performance.now(); if (taps.length && now - taps[taps.length - 1] > 2000) taps.length = 0; taps.push(now);
        if (taps.length >= 3) { const d = (taps[taps.length - 1] - taps[0]) / (taps.length - 1); live(() => T.setBpm(Math.round(60000 / d))); done(); toast('Tempo ' + M.bpm + '.'); }
      } }, 'Tap tempo')));
}

function openKey() {
  const scales = JSON.parse(T.scales());
  const pick = (scale, root) => { edit(() => T.setKey(scale, root), 'key'); openKey(); };
  sheet('Key and scale', 'keys-close',
    h('div', { class: 'row', 'data-faq': 'pick-root' }, NN.map((n, r) => h('button', { class: 'btn' + (M.root === r ? ' y' : ''), style: 'min-width:48px', onclick: () => pick(M.scale, r) }, n))),
    h('div', { class: 'list', 'data-faq': 'pick-scale' }, scales.map((s) => h('button', { class: 'btn' + (M.scale === s ? ' y' : ''), onclick: () => pick(s, M.root) }, s))));
}

function openRepeat() {
  sheet('Repeat', 'repeat-close',
    h('p', { class: 'note' }, 'With Repeat on, a held pad plays again and again at this rate.'),
    h('div', { class: 'row', 'data-faq': 'rate-*' }, RATES.map(([r, n]) => h('button', { class: 'btn' + (S.nrRate === r ? ' y' : ''), onclick: () => { S.nrRate = r; store.set('nrRate', r); sendLive(); renderDock(); openRepeat(); } }, n))),
    h('div', { class: 'slider', style: 'margin-top:10px', 'data-faq': 'feel-*' }, [[1, 'Straight'], [2 / 3, 'Triplet'], [1.5, 'Dotted']].map(([f, n]) =>
      h('button', { class: S.feel === f ? 'on' : '', onclick: () => { S.feel = f; store.set('feel', f); sendLive(); openRepeat(); } }, n))));
}

function openEdit(i) {
  const k = kitKey(i); if (!k) return;
  T.setSel(i); reread();
  const ed = JSON.parse(T.editJson(k));
  let before = null;
  const set = (name, v) => { if (before == null) before = T.messJson(); ed[name] = v; T.setEditJson(k, JSON.stringify(ed)); reread(); send(); };
  const done = () => { const after = T.messJson(); if (before && after !== before) { undo.push(before); redo = []; autosave(after); trail('edit ' + titleOf(k)); renderTop(); } before = null; };
  const f = (n, label, min, max, st, fmt) => field(label, min, max, st, ed[n], fmt, (v) => set(n, v), done);
  const pct = (v) => Math.round(v * 100) + '%';
  const flag = (n, label) => h('button', { class: 'btn' + (ed[n] ? ' y' : ''), onclick: (e) => { set(n, !ed[n]); done(); e.currentTarget.classList.toggle('y', ed[n]); } }, label);
  sheet(`Pad ${i + 1} · ${titleOf(k)}`, 'tray-done',
    h('div', { class: 'row' }, h('button', { class: 'btn y', onclick: () => call('preview', k, 0, false) }, 'Hear it'), flag('rev', 'Reverse'), flag('loop', 'Loop'),
      h('button', { class: 'btn' + ((M.mutes || []).includes(k) ? ' y' : ''), onclick: () => { edit(() => T.toggleMute(k), 'mute'); openEdit(i); } }, 'Mute'),
      h('button', { class: 'btn' + ((M.solos || []).includes(k) ? ' y' : ''), onclick: () => { edit(() => T.toggleSolo(k), 'solo'); openEdit(i); } }, 'Solo')),
    f('vol', 'Volume', 0, 2, 0.01, pct), f('pan', 'Pan', -1, 1, 0.01, (v) => v === 0 ? 'mid' : (v < 0 ? 'L ' : 'R ') + Math.round(Math.abs(v) * 100)),
    f('tune', 'Tune', -24, 24, 1, (v) => (v > 0 ? '+' : '') + v + ' st'), f('fine', 'Fine', -100, 100, 1, (v) => v + ' ct'),
    f('tone', 'Tone', -1, 1, 0.01, (v) => v === 0 ? 'flat' : v < 0 ? 'dark ' + pct(-v) : 'thin ' + pct(v)),
    f('st', 'Start', 0, 0.99, 0.001, pct), f('en', 'End', 0.01, 1, 0.001, pct),
    f('att', 'Attack', 0, 2, 0.01, (v) => Math.round(v * 1000) + ' ms'), f('rel', 'Release', 0, 2, 0.01, (v) => Math.round(v * 1000) + ' ms'),
    f('choke', 'Choke', 0, 4, 1, (v) => v ? 'group ' + v : 'none'),
    h('div', { class: 'row', style: 'margin-top:12px' },
      h('button', { class: 'btn', onclick: () => { S.pickFor = i; S.tab = 'sounds'; closeSheet(); paint(); } }, 'Swap sound'),
      h('button', { class: 'btn r', onclick: () => { edit(() => T.clearSoundInLoop(i), 'out of loop'); sayUndo(`Pad ${i + 1} is out of loop ${LETTERS[M.cur]}.`); } }, 'Out of this loop'),
      h('button', { class: 'btn r', onclick: () => { closeSheet(); edit(() => T.clearPad(i), 'clear pad'); sayUndo(`Pad ${i + 1} cleared.`); } }, 'Clear pad')));
}

/* ----- the menu, messes and notes ----- */

function messes() { return store.get('messes', []); }
function openMenu() {
  sheet('Menu', 'menu-close',
    h('div', { class: 'list' },
      h('button', { class: 'btn', 'data-faq': 'messes', onclick: openMesses }, 'Messes'),
      h('button', { class: 'btn', 'data-faq': 'notes-open', onclick: openNotes }, 'Notes for Claude'),
      h('button', { class: 'btn', disabled: !redo.length, onclick: () => { doRedo(); closeSheet(); } }, 'Redo'),
      h('button', { class: 'btn', onclick: openAbout }, 'About this web build')));
}
function openMesses() {
  const list = h('div', { class: 'list' });
  const redraw = () => fill(list, ...messes().map((m, n) => h('div', { class: 'it' },
    h('div', { class: 't' }, m.name || 'Untitled mess', h('small', {}, new Date(m.at).toLocaleString())),
    h('button', { class: 'btn y', onclick: () => { if (open(m.json, m.name)) closeSheet(); } }, 'Open'),
    h('button', { class: 'btn r', onclick: () => { const a = messes(); const [gone] = a.splice(n, 1); store.set('messes', a); redraw(); toast(`Tossed ${gone.name || 'it'}.`, 'Undo', () => { const b = messes(); b.splice(n, 0, gone); store.set('messes', b); }); } }, 'Toss'))));
  redraw();
  const name = h('input', { class: 'search', value: M.name || '', placeholder: 'Name this mess', maxlength: 48 });
  const file = h('input', { type: 'file', accept: '.json,.mess,.txt,application/json', style: 'display:none' });
  file.addEventListener('change', async () => { const f = file.files[0]; if (f && open(await f.text(), f.name)) closeSheet(); });
  const paste = h('textarea', { placeholder: 'Paste a mess here (from the app: Messes, Share, copy) and press Open pasted.' });
  sheet('Messes', 'messes-close', name,
    h('div', { class: 'row' },
      h('button', { class: 'btn y', onclick: () => {
        edit(() => T.setName(name.value.trim()), 'name'); const json = T.messJson(); const a = messes(); const i = a.findIndex((m) => m.name === M.name);
        const rec = { name: M.name || 'Untitled mess', at: Date.now(), json }; if (i >= 0) a[i] = rec; else a.unshift(rec); store.set('messes', a); redraw(); toast('Saved ' + rec.name + '.'); } }, 'Save'),
      h('button', { class: 'btn', onclick: () => { if (open(T.starterMess(), 'a new mess')) closeSheet(); } }, 'New'),
      h('button', { class: 'btn', onclick: () => file.click() }, 'Open a file'),
      h('button', { class: 'btn', onclick: async () => { try { await navigator.clipboard.writeText(T.messJson()); toast('The mess is copied. Paste it into the app or a file.'); } catch { toast('Copy is blocked here.'); } } }, 'Copy mess')),
    file, list, paste, h('button', { class: 'btn', onclick: () => { if (paste.value.trim() && open(paste.value.trim(), 'the pasted mess')) closeSheet(); } }, 'Open pasted'),
    h('p', { class: 'note' }, 'Messes save in this browser. The file format is the app\'s own, so a copied mess opens on the phone and the other way round.'));
}
async function openNotes() {
  const box = h('textarea', { placeholder: 'What happened, what you expected, what you want. Claude reads these.' });
  const st = h('p', { class: 'note' });
  sheet('Notes for Claude', 'notes-close', box,
    h('div', { class: 'row' }, h('button', { class: 'btn y', onclick: async () => {
      const text = box.value.trim(); if (!text) return;
      const note = { text, build: BUILD, at: new Date().toISOString(), ua: navigator.userAgent, mess: `${M.name || 'untitled'}: ${M.kit.filter(Boolean).length} pads, loop ${LETTERS[M.cur]} ${pat().len / 16} bars, ${M.bpm} bpm`, last: S.trail.slice(-15) };
      const db = await dbNs();
      if (db) { try { await db.collection('notes').doc(String(Date.now())).set(note); st.textContent = 'Sent. Claude will see it.'; box.value = ''; return; } catch (e) { st.textContent = 'Could not send: ' + e.message; } }
      const q = store.get('notes', []); q.push(note); store.set('notes', q); st.textContent = 'Kept in this browser (not signed in here). Copy it into the chat if you like.'; box.value = '';
    } }, 'Send')), st);
}
async function dbNs() { try { return window.claude ? await window.claude.use('db') : null; } catch { return null; } }
function openAbout() {
  sheet('About this web build', 'about-close',
    h('p', { class: 'note' }, `${BUILD}. The sound is the Android app's own engine (build 170), compiled to run in the browser, so a mess plays the same here as on the phone.`),
    h('p', { class: 'note' }, 'Built so far: pads, banks, click and count-in, Rec, Hold, Repeat, Mute, the loop strip (A to H, 1 to 8 bars, copy, clear, song painter), Steps with accents and drag paint, master throws and the filter square, sounds by jar with search, pad edits, tempo, key and scale, messes and notes.'),
    h('p', { class: 'note' }, 'Not yet: Synth, Roll, Padiano and Chords, Pack strips, Drop, the sample sheet\'s Play page and effects, exports, Learn, MIDI.'));
}

/* ================= tabs ================= */

const TABS = [['play', 'Play', 'play_tab'], ['synth', 'Synth', 'synth'], ['seq', 'Seq', 'steps'], ['perform', 'Perform', 'fx'], ['sounds', 'Sounds', 'sounds']];
function renderTabs() {
  fill($('#tabs'), 
    key('notes', 'Note', { icon: 'notes_bug', tile: 'var(--t-place)', onclick: openNotes }),
    h('div', { id: 'tabslot' }, TABS.map(([id, word, icon]) => key('tab-' + word, word, { icon, tile: 'var(--t-place)', on: S.tab === id,
      onclick: () => { if (id !== 'sounds') S.pickFor = -1; S.tab = id; S.mode = null; trail('tab ' + word); paint(); } }))));
}
function renderSynth() {
  fill($('#t-synth'), h('div', { class: 'h2' }, 'Synth'), h('p', { class: 'note' }, 'The engine already plays the app\'s synths (Analog, FM, Wavetable, Granular, Drums, Pluck): a mess from the phone with synth pads sounds right here. This page, where you build them, comes in the next web build.'));
}

function paint() {
  if (!M) return;
  renderTop(); renderStrip(); renderTabs(); renderDock();
  for (const [id] of TABS) $('#t-' + id).classList.toggle('on', S.tab === id);
  if (S.tab === 'play') renderPads();
  else if (S.tab === 'seq') renderSeq();
  else if (S.tab === 'perform') renderPerform();
  else if (S.tab === 'sounds') renderSounds();
  else if (S.tab === 'synth') renderSynth();
}

/* ================= keyboard (desktop extra) ================= */

const KB = '1234qwerasdfzxcv';
const kbDown = new Set();
addEventListener('keydown', (e) => {
  if (e.target.closest && e.target.closest('input,textarea')) return;
  if (e.code === 'Space') { e.preventDefault(); togglePlay(); return; }
  const d = KB.indexOf(e.key.toLowerCase());
  if (d < 0 || e.repeat || kbDown.has(d)) return;
  const i = S.bank * 16 + d; if (!kitKey(i)) return;
  kbDown.add(d); if (A.ctx) A.ctx.resume(); call('down', 950 + d, i, '0', false, false, -1);
});
addEventListener('keyup', (e) => { const d = KB.indexOf(e.key.toLowerCase()); if (d >= 0 && kbDown.delete(d)) call('up', 950 + d); });

/* ================= boot ================= */

function grit() {
  const c = document.createElement('canvas'); c.width = c.height = 96; const g = c.getContext('2d');
  let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  for (let n = 0; n < 96 * 96; n++) { const r = rnd(); if (r < 0.06) g.fillStyle = 'rgba(255,244,226,.10)'; else if (r < 0.12) g.fillStyle = 'rgba(255,244,226,.055)'; else continue; g.fillRect(n % 96, Math.floor(n / 96), 1, 1); }
  document.documentElement.style.setProperty('--grit', `url(${c.toDataURL()})`);
}

async function boot() {
  grit();
  const [lib, faq] = await Promise.all([fetch('library.json').then((r) => r.json()), fetch('faq.json').then((r) => r.json()).catch(() => [])]);
  for (const s of lib.sounds) LIB[s.k] = s;
  ALIAS = lib.aliases || {}; CABS = lib.cabs; FAQ = faq;
  const saved = store.get('current', null);
  const json = saved && T.normalize(saved) ? saved : T.demoMess();
  T.setMess(json); reread();
  paint();
  $('#start .go').addEventListener('click', async () => {
    $('#start').remove();
    try { await startAudio(); } catch (e) { toast('Sound could not start here: ' + e.message); console.error(e); }
  }, { once: true });
}
boot();
