// Mess Machine (web): the shell. Top bar, loop strip, bottom dock, tab slot, Perform and Sounds tabs, tempo, key and
// repeat sheets, the playhead, recording and boot. The other screens live in play.js, seq.js, synth.js, sample.js, files.js.
import { S, M, U, A, LIBS, UI, h, fill, $, key, pick, flick, sheet, closeSheet, field, chooser, toast, sayUndo, edit, live, call, post, wake, ensure, loadKit,
  loadOwn, ownKeys, forgetOwn, entryOf, kitKey, titleOf, padCol, store, trail, faqTap, LETTERS, LC, NN, pat, usedLoop, songArr, songLen, doUndo, closeTake, reread, send,
  sendLive, autosave, pct, SCRATCH } from './core.js';
import { T as _T } from './core.js';
import { renderPads, restFx, switchHold, releaseHolds, switchChords, chordModeLabel, togglePack, openPackPicker, wireDrop, openHoldTray, melodicDown } from './play.js';
import { renderSeq, markPlayhead } from './seq.js';
import { renderSynth } from './synth.js';
import { openSample } from './sample.js';
import { openMenu, openNotes, applySkin, recordToPad, checkLesson, lessonCtx, SKINS } from './files.js';

const FXN = [['stutter', 'Stutter', '#FF4FA3'], ['echo', 'Echo', '#7FD1FF'], ['crush', 'Crush', '#B6FF3B'], ['tape', 'Tape stop', '#FFB347'],
  ['gate', 'Gate', '#00BEB4'], ['dirty', 'Dirty', '#FF7A1A'], ['ring', 'Ring mod', '#C4A1FF'], ['oct', 'Octave down', '#FFD23F'], ['rev', 'Reverse', '#FF4A2E']];
const RATES = [[4, '1/4'], [2, '1/8'], [1, '1/16'], [0.5, '1/32']];
const PATS = [['', 'Every one'], ['xx.', '2 on, 1 off'], ['x.', 'On, off'], ['xxx.', '3 on, 1 off'], ['x..', '1 on, 2 off'], ['x.xx', 'Gallop'], ['x..x..x.', 'Tresillo'], ['x..x..x...x.x...', 'Clave']];

/* ================= audio ================= */

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
  call('setLatency', Math.round(((ctx.baseLatency || 0) + (ctx.outputLatency || 0)) * ctx.sampleRate));
  sendLive(); send();
  for (const [k, v] of S.fxSend) { const [p, t] = k.split(':').map(Number); call('home', p, t, v); }
  await loadKit();
  wakeLock();
  import('./midi.js').then((m) => m.startMidi()).catch(() => {});
}
let wl = null;
async function wakeLock() {
  try { if (store.get('awake', true)) { if (!wl && navigator.wakeLock) wl = await navigator.wakeLock.request('screen'); } else if (wl) { await wl.release(); wl = null; } } catch {}
}
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { wl = null; wakeLock(); } });
UI.wakeLock = wakeLock;

function fromEngine(m) {
  if (m.t === 'pos') {
    const [pl, pi, step, song] = m.h.split(',').map(Number);
    const was = S.heard;
    S.heard = { playing: !!pl, pat: pi, step, song, countIn: m.c };
    if (step !== was.step || pi !== was.pat || m.c !== was.countIn) onStep(was);
    if (m.err) console.warn('engine errors', m.err, m.last);
  } else if (m.t === 'rec') {
    // Build 105: while Rec plays, everything recorded is one take, and one Undo takes the whole take back.
    const before = T().messJson();
    for (const s of m.ev) { const r = JSON.parse(s); T().applyRec(r.type, r.pat, r.key, r.step, r.len, r.hit, r.strength, r.pitches.join(',')); }
    const after = T().messJson();
    if (after !== before) {
      if (S.take == null) { S.take = before; U.redo = []; trail('take'); }
      reread(); send(); autosave(after);
      if (S.tab === 'seq') paint(); else renderTop();
    }
  } else if (m.t === 'err') console.warn('engine', m.what, m.err);
}
const T = () => _T;

/* ================= top bar ================= */

function renderTop() {
  const t = $('#top');
  const P = pat();
  const lp = S.heard.playing && S.heard.pat >= 0 ? S.heard.pat : M.cur;
  const bars = P.len / 16;
  const pos = S.heard.playing && S.heard.step >= 0 ? `${LETTERS[lp]} ${Math.floor(S.heard.step / 16) + 1}.${Math.floor((S.heard.step % 16) / 4) + 1}` : 'LOOP ' + LETTERS[M.cur] + (bars > 1 ? ' ×' + bars : '');
  fill(t,
    key('play', 'Play', { icon: 'play', tile: 'var(--t-transport)', on: S.playing, cls: 'brass', onclick: togglePlay }),
    key('stop', 'Stop', { icon: 'stop', tile: 'var(--t-transport)', onclick: stop, sw: false }),
    key('rec', 'Rec', { icon: 'rec', tile: 'var(--t-transport)', on: S.rec, lamp: 'var(--pink)', cls: 'brass', onclick: toggleRec }),
    key('undo', 'Undo', { icon: 'undo', tile: 'var(--t-transport)', disabled: !U.undo.length && S.take == null, onclick: () => { closeTake(); doUndo(); }, sw: false }),
    h('button', { class: 'lcd', 'data-faq': 'bpm', onclick: (e) => { if (!faqTap('bpm', e)) openTempo(); } }, h('span', {}, String(M.bpm), h('small', {}, S.heard.countIn ? 'COUNT ' + S.heard.countIn : 'BPM'))),
    loopLcd(pos),
    h('span', { class: 'gap' }),
    key('help-toggle', 'FAQ', { icon: 'what_the_faq', tile: 'var(--t-other)', on: S.faq, onclick: () => { S.faq = !S.faq; document.body.classList.toggle('faq', S.faq); toast(S.faq ? 'Tap anything to learn what it is. Tap FAQ again to play.' : 'Back to playing.'); renderTop(); } }),
    key('menu', 'Menu', { icon: 'settings', tile: 'var(--t-other)', onclick: openMenu, sw: false }),
  );
}
UI.top = renderTop;
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
  wake();
  if (S.playing) { call('pause'); S.playing = false; trail('pause'); }
  else { if (M.mode === 'song' && !songLen()) edit(() => T().setSongMode(false)); S.follow = true; call('play', S.rec ? S.countBars * 16 : 0); S.playing = true; trail('play'); }
  closeTake(); paint();
}
function stop() {
  if (!S.playing) { call('stop'); call('panic'); releaseHolds(); toast('Stopped and hushed: held notes, effects and filters all let go.'); }
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
  if (playing && S.follow && pi === M.cur && step >= 0) { const pg = Math.floor(step / 16); if (pg !== S.page && S.tab === 'seq') { S.page = pg; paint(); return; } }
  renderTop();
  markPlayhead();
  markRoll();
  if (S.stripOpen) markStrip();
  checkLesson();
}
function markRoll() {
  const g = $('#rollgrid'); if (!g) return;
  for (const c of g.querySelectorAll('.now')) c.classList.remove('now');
  const { step, pat: pi, playing } = S.heard;
  if (!playing || pi !== M.cur || Math.floor(step / 16) !== S.page) return;
  for (const c of g.querySelectorAll(`.rc[data-s="${step}"]`)) c.classList.add('now');
}

/* ================= loop strip ================= */

function pickLoop(i) {
  if (S.copying) { S.copying = false; if (i !== M.cur) { edit(() => T().copyLoopTo(i), 'copy loop'); toast(`Loop ${LETTERS[M.cur]} copied onto ${LETTERS[i]}.`); } return; }
  if (i === M.cur && S.pending < 0) return;
  if (S.playing && S.loopAtBar && i !== M.cur) { S.pending = i; trail('loop ' + LETTERS[i] + ' at the bar'); renderStrip(); }
  else { S.pending = -1; selectLoop(i); }
}
function selectLoop(i) { T().setCur(i); reread(); send(); S.page = 0; trail('loop ' + LETTERS[i]); autosave(); paint(); }
function setLen(n, fill2, msg) { if (edit(() => T().setLen(n, fill2), 'bars ' + n / 16)) { S.page = Math.min(S.page, n / 16 - 1); toast(msg); } }
function renderStrip() {
  const st = $('#strip');
  st.classList.toggle('open', S.stripOpen);
  if (!S.stripOpen) { fill(st); return; }
  const close = h('button', { class: 'closer', 'aria-label': 'Close the loop strip', 'data-faq': 'loop-close', onclick: (e) => { if (faqTap('loop-close', e)) return; S.stripOpen = false; renderStrip(); } });
  const letters = h('div', { class: 'letters' }, [...LETTERS].map((L, i) => h('button', {
    class: 'letter' + (usedLoop(M.pats[i]) ? ' used' : '') + ((S.songSide ? S.paint === i : M.cur === i) ? ' cur' : '') + (S.pending === i ? ' wait' : ''),
    style: `--lc:${LC[i]}`, 'data-faq': 'loop-' + L, 'data-i': i,
    onclick: (e) => { if (faqTap('loop-*', e)) return; if (S.songSide) { S.paint = i; renderStrip(); } else pickLoop(i); } }, L)));
  const kids = [h('div', { class: 'row' }, pick([[false, 'Loop'], [true, 'Song']], S.songSide, (v) => { S.songSide = v; renderStrip(); }, { tag: 'loop-tab' }), close), letters];
  if (!S.songSide) {
    const P = pat(); const bars = P.len / 16;
    kids.push(h('div', { class: 'row' },
      key('loop-when', S.loopAtBar ? 'Wait bar' : 'Now', { icon: S.loopAtBar ? 'wait_bar' : 'now', tile: 'var(--t-other)', on: S.loopAtBar, onclick: () => { S.loopAtBar = !S.loopAtBar; store.set('loopAtBar', S.loopAtBar); renderStrip(); } }),
      h('span', { style: 'flex:1' }), h('span', { class: 'word' }, bars + (bars > 1 ? ' bars' : ' bar')),
      key('loop-minus', '', { icon: 'bar_minus', tile: 'var(--t-other)', title: 'One bar less', sw: false, onclick: () => bars <= 1 ? toast('One bar is the least.') : setLen(P.len - 16, true, `Loop ${LETTERS[M.cur]} is ${bars - 1} bar${bars - 1 > 1 ? 's' : ''} now.`) }),
      key('loop-plus', '', { icon: 'bar_plus', tile: 'var(--t-other)', title: 'One bar more', sw: false, onclick: () => { if (bars >= 8) { toast('8 bars is the most a loop holds.'); return; } setLen(P.len + 16, false, `Loop ${LETTERS[M.cur]} is ${bars + 1} bars now; bar ${bars + 1} is empty.`); S.page = bars; paint(); } }),
      key('loop-double', '', { icon: 'double', tile: 'var(--t-other)', title: 'Double', sw: false, onclick: () => bars * 2 > 8 ? toast('Doubled it would pass 8 bars.') : setLen(P.len * 2, true, `Loop ${LETTERS[M.cur]} is ${bars * 2} bars now. What was there got copied into the new bars.`) })));
    kids.push(h('div', { class: 'row' },
      h('button', { class: 'btn' + (S.copying ? ' y' : ''), 'data-faq': 'loop-copy', onclick: (e) => { if (faqTap('loop-*', e)) return; S.copying = !S.copying; if (S.copying) toast('Tap the loop to copy ' + LETTERS[M.cur] + ' onto.'); renderStrip(); } }, 'Copy to'),
      h('button', { class: 'btn r', 'data-faq': 'loop-clear', onclick: (e) => { if (faqTap('loop-clear', e)) return; edit(() => T().clearLoop(), 'clear loop'); sayUndo(`Loop ${LETTERS[M.cur]} cleared.`); } }, 'Clear loop'),
      h('button', { class: 'btn', 'data-faq': 'bottle', onclick: (e) => { if (faqTap('bottle', e)) return; bottleIt(); } }, 'Bottle it')));
  } else {
    const arr = songArr(), n = Math.min(64, Math.max(16, Math.ceil((songLen() + 1) / 16) * 16));
    kids.push(pick([['loop', 'Play loop'], ['song', 'Play song']], M.mode === 'song' ? 'song' : 'loop', (v) => {
      if (v === 'song' && !songLen()) { toast('Paint some loops into the song lane first.'); return; } edit(() => T().setSongMode(v === 'song')); }, { tag: 'song-play' }));
    const lane = h('div', { class: 'song', 'data-faq': 'song-body' }, arr.slice(0, n).map((v, i) => h('button', {
      style: v >= 0 ? `background:${LC[v]}` : '', 'data-i': i, class: S.heard.playing && M.mode === 'song' && S.heard.song === i ? 'now' : '' }, v >= 0 ? LETTERS[v] : '')));
    // tap paints the picked letter (again clears); hold a bar for put a bar in or take it out
    let ht = 0, held = false;
    lane.addEventListener('pointerdown', (e) => { const b = e.target.closest('button'); if (!b) return; if (faqTap('song-body', e)) return; held = false; ht = setTimeout(() => { held = true; barMenu(+b.dataset.i); }, 450); });
    lane.addEventListener('pointerup', (e) => { clearTimeout(ht); const b = e.target.closest('button'); if (!b || held) return; const i = +b.dataset.i, v = arr[i]; edit(() => T().setSongBar(i, v === S.paint ? -1 : S.paint), 'song bar'); });
    kids.push(lane, h('p', { class: 'note' }, 'Pick a letter above, then tap bars to paint it. Tap a bar again to clear it; hold one to put a bar in or take it out.'));
  }
  fill(st, ...kids);
}
function barMenu(i) {
  sheet(`Bar ${i + 1}`, 'bar-close', h('div', { class: 'list' },
    h('button', { class: 'btn', onclick: () => { closeSheet(); edit(() => T().insertSongBar(i), 'bar in'); } }, 'Put a bar in here'),
    h('button', { class: 'btn r', onclick: () => { closeSheet(); edit(() => T().deleteSongBar(i), 'bar out'); sayUndo(`Bar ${i + 1} deleted.`); } }, 'Take this bar out'),
    h('button', { class: 'btn', onclick: () => { closeSheet(); edit(() => T().setSongBar(i, -1), 'bar clear'); } }, 'Clear it')));
}
function markStrip() { for (const b of document.querySelectorAll('#strip .letter')) b.classList.toggle('playing', S.heard.playing && +b.dataset.i === S.heard.pat); }
/** Bottle it: the loop becomes one sound on a free pad (AppState.resampleLoop). */
async function bottleIt() {
  const free = M.kit.slice(0, 64).findIndex((k) => !k);
  if (free < 0) { toast('No empty pad for the bottle.'); return; }
  toast('Bottling the loop…');
  const { wav16 } = await import('./files.js');
  const { ensureMain } = await import('./core.js');
  T().init(A.ctx ? A.ctx.sampleRate : 44100); T().setMess(T().messJson());
  await Promise.all(M.kit.filter(Boolean).map(ensureMain)); T().refresh();
  const bytes = new Uint8Array(T().wavOf(false, 1).buffer);
  // the bounce is stereo: fold it to mono for the pad
  const dv = new DataView(bytes.buffer), n = (bytes.length - 44) / 4, mono = new Float32Array(n);
  for (let i = 0; i < n; i++) mono[i] = (dv.getInt16(44 + i * 4, true) + dv.getInt16(46 + i * 4, true)) / 65536;
  const key = 'bn:' + Date.now().toString(36) + '000', title = `Loop ${LETTERS[M.cur]} bottled`;
  const { keepOwn } = await import('./core.js');
  store.set('own.' + key, title); LIBS.LIB[key] = { k: key, t: title, c: 'mine', f: '', ty: 'mine', q: [], bpm: M.bpm, beats: pat().len / 4 };
  await keepOwn(key, wav16(mono, A.ctx ? A.ctx.sampleRate : 44100));
  await ensure(key);
  edit(() => T().assign(free, key), 'bottle it'); call('refresh');
  S.bank = Math.floor(free / 16); S.stripOpen = false;
  toast(`Loop ${LETTERS[M.cur]} is a sound on pad ${free + 1}.`); paint();
}

/* ================= the dock ================= */

function renderDock() {
  const d = $('#dock');
  const show = S.tab === 'play' || S.tab === 'synth';
  d.style.display = show ? '' : 'none';
  if (!show) return;
  const rate = RATES.find((r) => r[0] === S.nrRate);
  const drop = key('drop-key', 'Drop', { cap: 'Drop', cls: 'dropkey', on: !!S.drop, lamp: 'var(--oxblood)' });
  fill(d,
    key('dock-edit', 'Edit', { icon: 'edit', tile: 'var(--t-perform)', on: S.mode === 'edit', onclick: () => { S.mode = S.mode === 'edit' ? null : 'edit'; if (S.mode) toast('Tap a pad to edit it.'); paint(); } }),
    flick(key('pack-toggle', 'Pack', { icon: 'pack', tile: 'var(--t-perform)', on: S.open.has(M.sel), onclick: () => togglePack() }), () => openPackPicker()),
    flick(key('hold-toggle', S.hold ? (S.holdCount ? 'Hold ' + S.holdCount : 'Hold last') : 'Hold', { icon: 'hold', tile: 'var(--t-perform)', on: S.hold, onclick: () => switchHold() }), openHoldTray),
    flick(key('chords-toggle', 'Chords', { icon: 'chords', tile: 'var(--t-perform)', on: S.chord !== 'off', onclick: switchChords }), openKey),
    flick(key('repeat-toggle', rate ? rate[1] + (S.nrPat ? ' ' + S.nrPat : '') : 'Repeat', { icon: 'repeat', tile: 'var(--t-perform)', on: S.nr, onclick: () => { S.nr = !S.nr; sendLive(); paint(); } }), openRepeat),
    key('mute-key', 'Mute', { icon: 'mute', tile: 'var(--t-other)', on: S.mode === 'mute', lamp: 'var(--oxblood)', onclick: () => { S.mode = S.mode === 'mute' ? null : 'mute'; if (S.mode) toast('Mute: tap pads to mute or unmute them. Hold a bank number for the whole bank.'); paint(); } }),
    wireDrop(drop),
    flick(h('button', { class: 'lcd', 'data-faq': 'keysbar-open', onclick: (e) => { if (!faqTap('keysbar-open', e)) openKey(); } },
      h('span', {}, h('span', {}, `${NN[M.root]} ${M.scale.toUpperCase()}`), S.chord !== 'off' ? h('small', {}, chordModeLabel().toUpperCase()) : null)), openKey),
  );
}
UI.dock = renderDock;

/* ================= Perform tab ================= */

function renderPerform(tab) {
  const vials = h('div', { class: 'vials', 'data-faq': 'master-throws' }, FXN.map(([f, name, col]) => h('div', { class: 'vial', 'data-f': f, style: `--vc:${col}`, 'data-faq': 'fx-' + f }, h('i'), h('span', {}, name))));
  const xy = h('div', { class: 'xy', 'data-faq': 'perform-xy' }, h('span', { class: 'word lab' }, 'Mud · Thin'));
  const master = h('div', { class: 'masterplate', 'data-faq': 'master-bus' },
    h('div', { class: 'word', style: 'color:var(--brass)' }, 'Master'),
    field('Squash', 0, 1, 0.01, M.squash != null ? M.squash : 0.5, (v) => v === 0 ? 'off' : pct(v), (v) => { live.begin(); T().setMaster(v, M.level != null ? M.level : 1); live.move(); }, () => live.end('squash')),
    field('Level', 0, 1.6, 0.01, M.level != null ? M.level : 1, pct, (v) => { live.begin(); T().setMaster(M.squash != null ? M.squash : 0.5, v); live.move(); }, () => live.end('level')));
  fill(tab, h('div', { class: 'h2' }, 'Master throws'), vials, xy, master,
    h('p', { class: 'note' }, 'Hold a vial to throw its effect on the whole mix; where your finger sits is how much. Drag in the square for the master filter: left is mud, right is thin.'));
  const vt = new Map();
  const amt = (el, e) => { const r = el.getBoundingClientRect(); return Math.max(0.05, Math.min(1, 1 - (e.clientY - r.top) / r.height)); };
  vials.addEventListener('pointerdown', (e) => {
    const v = e.target.closest('.vial'); if (!v) return;
    if (faqTap(v.dataset.faq, e)) return;
    e.preventDefault(); try { vials.setPointerCapture(e.pointerId); } catch {}
    const a = amt(v, e); vt.set(e.pointerId, v); wake();
    call('fxAmount', v.dataset.f, a); call('fx', v.dataset.f, true);
    v.style.setProperty('--amt', a * 100 + '%'); trail('fx ' + v.dataset.f);
  });
  vials.addEventListener('pointermove', (e) => { const v = vt.get(e.pointerId); if (!v) return; const a = amt(v, e); call('fxAmount', v.dataset.f, a); v.style.setProperty('--amt', a * 100 + '%'); });
  const off = (e) => { const v = vt.get(e.pointerId); if (!v) return; vt.delete(e.pointerId); call('fx', v.dataset.f, false); v.style.setProperty('--amt', '0%'); };
  vials.addEventListener('pointerup', off); vials.addEventListener('pointercancel', off);
  const xyAt = (e, on) => { const r = xy.getBoundingClientRect(); const x = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)), y = Math.max(0, Math.min(1, (e.clientY - r.top) / r.height));
    call('xy', x, 1 - y, on); xy.style.setProperty('--x', x * 100 + '%'); xy.style.setProperty('--y', y * 100 + '%'); };
  let xyId = null;
  xy.addEventListener('pointerdown', (e) => { if (faqTap('perform-xy', e)) return; e.preventDefault(); try { xy.setPointerCapture(e.pointerId); } catch {} xyId = e.pointerId; wake(); xyAt(e, true); });
  xy.addEventListener('pointermove', (e) => { if (e.pointerId === xyId) xyAt(e, true); });
  const xyOff = (e) => { if (e.pointerId !== xyId) return; xyId = null; xyAt(e, false); };
  xy.addEventListener('pointerup', xyOff); xy.addEventListener('pointercancel', xyOff);
}

/* ================= Sounds tab ================= */

const TYPES = [['', 'All'], ['kick', 'Kicks'], ['snare', 'Snares'], ['hat', 'Hats'], ['perc', 'Perc'], ['bass', 'Bass'], ['keys', 'Keys'], ['lead', 'Leads'], ['pad', 'Pads'], ['fx', 'FX'], ['voice', 'Voices']];
function renderSounds(tab) {
  const target = S.pickFor >= 0 ? S.pickFor : M.sel;
  const search = h('input', { class: 'search', type: 'search', placeholder: 'Search sounds, types, feels, keys', value: S.search, 'data-faq': 'sounds-search' });
  const list = h('div');
  search.addEventListener('input', () => { S.search = search.value; renderSoundList(list, target); });
  const types = h('div', { class: 'chips' }, TYPES.map(([v, n]) => h('button', { class: (S.stype || '') === v ? 'on' : '', onclick: () => { S.stype = v; renderSounds(tab); } }, n)),
    h('button', { class: S.stars ? 'on' : '', onclick: () => { S.stars = !S.stars; renderSounds(tab); } }, '★ Starred'));
  fill(tab, h('div', { class: 'row' }, h('div', { class: 'h2', style: 'flex:1' }, S.pickFor >= 0 ? `A sound for pad ${S.pickFor + 1}` : 'Sounds'),
    h('button', { class: 'btn', 'data-faq': 'packs-open', onclick: () => import('./packs.js').then((m) => m.openPacks()) }, 'Packs')), search, types, list);
  renderSoundList(list, target);
}
function renderSoundList(list, target) {
  const q = S.search.trim().toLowerCase();
  const onPad = new Set(M.kit.filter(Boolean));
  const stars = new Set(store.get('stars', []));
  const sounds = Object.values(LIBS.LIB);
  const cabs = [{ id: 'mine', name: 'Your Workbench', col: '#46D3C0' }].concat(LIBS.CABS);
  fill(list, ...cabs.map((c) => {
    const items = sounds.filter((s) => s.c === c.id && (!S.stype || s.ty === S.stype) && (!S.stars || stars.has(s.k)) &&
      (!q || (s.t + ' ' + (s.ty || '') + ' ' + (s.q || []).join(' ') + ' ' + (s.g || '') + ' ' + (s.mkey || '')).toLowerCase().includes(q)));
    if (!items.length) return null;
    const shown = q || S.stype || S.stars ? items : items.slice(0, c.open ? items.length : 24);
    const jar = h('div', { class: 'jar', style: `--jc:${c.col}` }, h('h3', {}, c.name, h('small', { class: 'note', style: 'margin:0' }, String(items.length))),
      shown.map((s) => h('div', { class: 'snd' + (onPad.has(s.k) ? ' onpad' : '') },
        h('button', { class: 'star' + (stars.has(s.k) ? ' on' : ''), 'aria-label': 'Star', onclick: (e) => { const st = new Set(store.get('stars', [])); if (st.has(s.k)) st.delete(s.k); else st.add(s.k); store.set('stars', [...st]); e.currentTarget.classList.toggle('on'); } }, '★'),
        h('div', { class: 't', 'data-faq': 'sound-row', onclick: async (e) => { if (faqTap('sound-*', e)) return; wake(); await ensure(s.k); call('preview', s.k, 0, false); } },
          s.t, h('small', {}, [s.ty, s.bpm ? Math.round(s.bpm) + ' bpm' : '', s.mkey || ''].filter(Boolean).join(' · '))),
        c.id === 'mine' ? h('button', { class: 'put', style: 'background:var(--card2);color:var(--cream)', onclick: () => { if (onPad.has(s.k)) { toast('It is on a pad; clear the pad first.'); return; } forgetOwn(s.k); renderSoundList(list, target); } }, 'Toss') : null,
        h('button', { class: 'put', 'data-faq': 'sound-put', onclick: (e) => { if (faqTap('sound-*', e)) return; putOnPad(s.k, target); } }, 'Pad ' + (target + 1)))));
    if (shown.length < items.length) jar.append(h('button', { class: 'btn', onclick: () => { c.open = true; renderSoundList(list, target); } }, `All ${items.length}`));
    return jar;
  }).filter(Boolean));
}
/** Sounds sent from the site's lab (a jar put on a pad) land on free pads here. */
function drainInbox() {
  let q = []; try { q = JSON.parse(localStorage.getItem('mmweb.inbox') || '[]'); localStorage.removeItem('mmweb.inbox'); } catch {}
  for (const k of q) {
    if (M.kit.includes(k)) continue;
    if (!entryOf(k)) { toast('That sound only lives on the site, so it cannot go on the machine.'); continue; }
    const free = M.kit.slice(0, 64).findIndex((x) => !x);
    if (free < 0) { toast('All 64 pads are full. Clear one first.'); break; }
    putOnPad(k, free);
  }
}
async function putOnPad(k, pad) {
  if (pad < 0 || pad >= 64) pad = 0;
  await ensure(k);
  edit(() => T().assign(pad, k), 'sound on pad ' + (pad + 1));
  call('refresh');
  toast(`${titleOf(k)} is on pad ${pad + 1}.`);
  if (S.pickFor >= 0) { S.pickFor = -1; S.bank = Math.floor(pad / 16); S.tab = 'play'; paint(); }
}

/* ================= sheets: tempo, key and chords, repeat ================= */

function openTempo() {
  const taps = [];
  sheet('Tempo', 'tempo-close',
    field('Tempo', 60, 180, 1, M.bpm, (v) => v + ' bpm', (v) => { live.begin(); T().setBpm(v); live.move(); renderTop(); }, () => live.end('tempo')),
    field('Swing', 0, 75, 1, M.swing, (v) => v + '%', (v) => { live.begin(); T().setSwing(v); live.move(); }, () => live.end('swing')),
    h('div', { class: 'row', style: 'margin-top:10px' },
      h('button', { class: 'btn', onclick: () => { edit(() => T().setBpm(M.bpm - 1), 'tempo'); openTempo(); } }, '− 1'),
      h('button', { class: 'btn', onclick: () => { edit(() => T().setBpm(M.bpm + 1), 'tempo'); openTempo(); } }, '+ 1'),
      h('button', { class: 'btn y', 'data-faq': 'tempo-tap', onclick: () => {
        const now = performance.now(); if (taps.length && now - taps[taps.length - 1] > 2000) taps.length = 0; taps.push(now);
        if (taps.length >= 3) { const d = (taps[taps.length - 1] - taps[0]) / (taps.length - 1); edit(() => T().setBpm(Math.round(60000 / d)), 'tap tempo'); toast('Tempo ' + M.bpm + '.'); }
      } }, 'Tap tempo')));
}
let SETS = null;
function openKey() {
  const scales = JSON.parse(T().scales());
  SETS = SETS || JSON.parse(T().chordSetsJson());
  const again = () => openKey();
  const setMode = (m) => { S.chord = m; if (m !== 'off') S.lastChord = m; store.set('chord', m); store.set('chordLast', S.lastChord); S.lastVoiced.clear(); paint(); };
  sheet('Key and chords', 'keys-close',
    h('div', { class: 'word' }, 'Root'),
    h('div', { class: 'row', 'data-faq': 'pick-root' }, NN.map((n, r) => h('button', { class: 'btn' + (M.root === r ? ' y' : ''), style: 'min-width:46px;padding:0 8px', onclick: () => { edit(() => T().setKey(M.scale, r), 'root'); again(); } }, n))),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'Scale'),
    h('div', { class: 'chips', 'data-faq': 'pick-scale' }, scales.map((s) => h('button', { class: M.scale === s ? 'on' : '', onclick: () => { edit(() => T().setKey(s, M.root), 'scale'); again(); } }, s))),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'Chords'),
    h('div', { class: 'chips', 'data-faq': 'pick-chord' }, [['off', 'Single notes'], ['triad', 'Triads'], ['seventh', 'Sevenths'], ['sus', '1-4-5'], ['power', 'Power'], ['octave', 'Octaves']]
      .map(([m, n]) => h('button', { class: S.chord === m ? 'on' : '', onclick: () => { setMode(m); again(); } }, n))),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'Chord sets: 12 chords, all in key'),
    h('div', { class: 'chips' }, SETS.map((c) => h('button', { class: S.chord === 'set:' + c.name ? 'on' : '', onclick: () => {
      setMode('set:' + c.name);
      if (M.scale !== c.home) { edit(() => T().setKey(c.home, M.root), 'scale'); toast(`${c.name} lives in ${c.home}, so the mess moved to ${NN[M.root]} ${c.home.toLowerCase()}.`); }
      again(); } }, c.name))),
    h('div', { class: 'row', style: 'margin-top:10px' },
      pick([[true, 'Play chords'], [false, 'Show them']], S.chordPlay, (v) => { S.chordPlay = v; store.set('chordPlay', v); again(); }, { tag: 'chord-play' }),
      pick([[true, 'Smooth'], [false, 'Root']], S.chordSmooth, (v) => { S.chordSmooth = v; store.set('chordSmooth', v); S.lastVoiced.clear(); again(); }, { tag: 'chord-smooth' })));
}
function openRepeat() {
  const again = () => openRepeat();
  const set = (k, v) => { S[k] = v; store.set(k, v); sendLive(); renderDock(); again(); };
  sheet('Repeat', 'repeat-close',
    h('p', { class: 'note' }, 'With Repeat on, a held pad or key plays again and again at this rate.'),
    h('div', { class: 'row', 'data-faq': 'rate-*' }, RATES.map(([r, n]) => h('button', { class: 'btn' + (S.nrRate === r ? ' y' : ''), onclick: () => set('nrRate', r) }, n))),
    pick([[1, 'Straight'], [2 / 3, 'Triplet'], [1.5, 'Dotted']], S.feel, (f) => set('feel', f), { tag: 'feel' }),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'How each repeat plays'),
    pick([['cut', 'Cut'], ['ring', 'Ring'], ['flow', 'Flow']], S.nrStyle, (v) => set('nrStyle', v), { tag: 'nrstyle' }),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'Pattern'),
    h('div', { class: 'chips', 'data-faq': 'dock-pattern' }, PATS.map(([p, n]) => h('button', { class: S.nrPat === p ? 'on' : '', onclick: () => set('nrPat', p) }, n))),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'Chords and held keys'),
    h('div', { class: 'chips' }, [['rep', 'Repeat'], ['up', 'Up'], ['down', 'Down'], ['updown', 'Up and down'], ['rand', 'Random']].map(([a, n]) => h('button', { class: S.arp === a ? 'on' : '', onclick: () => set('arp', a) }, n))));
}

/* ================= tabs and paint ================= */

const TABS = [['play', 'Play', 'play_tab'], ['synth', 'Synth', 'synth'], ['seq', 'Seq', 'steps'], ['perform', 'Perform', 'fx'], ['sounds', 'Sounds', 'sounds']];
function renderTabs() {
  fill($('#tabs'),
    key('notes', 'Note', { icon: 'notes_bug', tile: 'var(--t-place)', onclick: openNotes, sw: false }),
    h('div', { id: 'tabslot' }, TABS.map(([id, word, icon]) => key('tab-' + word, word, { icon, tile: 'var(--t-place)', on: S.tab === id,
      onclick: () => { if (id !== 'sounds') S.pickFor = -1; S.tab = id; S.mode = null; trail('tab ' + word); paint(); $('#page').scrollTop = 0; } }))));
}
function paint() {
  if (!M) return;
  renderTop(); renderStrip(); renderTabs(); renderDock();
  for (const [id] of TABS) $('#t-' + id).classList.toggle('on', S.tab === id);
  const tab = $('#t-' + S.tab);
  if (S.tab === 'play') renderPads(tab);
  else if (S.tab === 'seq') renderSeq(tab);
  else if (S.tab === 'perform') renderPerform(tab);
  else if (S.tab === 'sounds') renderSounds(tab);
  else if (S.tab === 'synth') renderSynth(tab);
  document.body.classList.toggle('recording', !!S.recording);
  checkLesson();
}
UI.paint = paint;
UI.sendLive = sendLive;
UI.restFx = restFx;
UI.openSample = openSample;
UI.openEdit = (i) => openSample(i);
UI.recordToPad = recordToPad;

/* ================= keyboard (desktop extra) ================= */

const KB = '1234qwerasdfzxcv';
const kbDown = new Set();
addEventListener('keydown', (e) => {
  if (e.target.closest && e.target.closest('input,textarea')) return;
  if (e.code === 'Space') { e.preventDefault(); togglePlay(); return; }
  const d = KB.indexOf(e.key.toLowerCase());
  if (d < 0 || e.repeat || kbDown.has(d)) return;
  const i = S.bank * 16 + d; if (!kitKey(i)) return;
  kbDown.add(d); wake();
  if (S.hold) melodicDown(1000 + i, i, [0], false); else call('down', 950 + d, i, '0', false, false, -1);
  lessonCtx.hits++;
});
addEventListener('keyup', (e) => { const d = KB.indexOf(e.key.toLowerCase()); if (d >= 0 && kbDown.delete(d) && !S.hold) call('up', 950 + d); });
// lessons count real presses
document.addEventListener('pointerdown', (e) => { if (e.target.closest('.pad:not(.empty)')) lessonCtx.hits++; if (e.target.closest('.pk')) lessonCtx.keys++; }, true);
document.addEventListener('pointerup', () => setTimeout(checkLesson, 50), true);

/* ================= boot ================= */

function grit() {
  const c = document.createElement('canvas'); c.width = c.height = 96; const g = c.getContext('2d');
  const s = SKINS[S.skin] || SKINS.classic;
  let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const col = s.light ? '0,0,0' : '255,244,226', a = Math.min(1, s.grit);
  for (let n = 0; n < 96 * 96; n++) { const r = rnd(); if (r < 0.06) g.fillStyle = `rgba(${col},${0.10 * a})`; else if (r < 0.12) g.fillStyle = `rgba(${col},${0.055 * a})`; else continue; g.fillRect(n % 96, Math.floor(n / 96), 1, 1); }
  document.documentElement.style.setProperty('--grit', a > 0 ? `url(${c.toDataURL()})` : 'none');
}
UI.grit = grit;
window.addEventListener('popstate', () => closeSheet(true));
$('#scrim').addEventListener('click', () => closeSheet());

async function boot() {
  applySkin(); grit();
  const [lib, faq] = await Promise.all([fetch('library.json').then((r) => r.json()), fetch('faq.json').then((r) => r.json()).catch(() => [])]);
  for (const s of lib.sounds) LIBS.LIB[s.k] = s;
  LIBS.ALIAS = lib.aliases || {}; LIBS.CABS = lib.cabs; LIBS.FAQ = faq;
  await loadOwn();
  (await import('./packs.js')).registerPacks();
  const saved = store.get('current', null);
  const json = saved && T().normalize(saved) ? saved : T().demoMess();
  T().setMess(json); T().ensureScratch(); reread(); restFx();
  paint();
  $('#start .go').addEventListener('click', async () => {
    $('#start').remove();
    try { await startAudio(); } catch (e) { toast('Sound could not start here: ' + e.message); console.error(e); }
  }, { once: true });
  window.__mm = { S, get M() { return M; }, T: T(), U };
  drainInbox();
  addEventListener('message', (e) => { if (e.data && e.data.t === 'mm-inbox') drainInbox(); });
  addEventListener('storage', (e) => { if (e.key === 'mmweb.inbox' && e.newValue) drainInbox(); });
}
boot();
