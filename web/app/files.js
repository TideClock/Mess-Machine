// Menu sheets: Messes, Files (exports), Settings (skins, large keys, HotKeys), Learn, Notes for Claude, record to pad.
import { S, M, T, U, h, fill, $, sheet, closeSheet, field, pick, chooser, toast, edit, call, kitKey, titleOf, store, trail, UI, BUILD, LETTERS, pat, openMess,
  doRedo, ensure, ensureMain, keepOwn, LIBS, loadKit, wake, A, songLen } from './core.js';

/* ================= skins (tokens.json) ================= */

export const SKINS = {
  classic: { name: 'Classic', ink: '#18161A', paper: '#141215', card: '#1E1B21', card2: '#2E2833', empty: '#3A3440', cream: '#FFF4E2', dim: '#BDB2BD', pink: '#FF4FA3', teal: '#00BEB4', yellow: '#FFD23F', riot: '#FF4A2E', purple: '#C4A1FF', lime: '#B6FF3B', sky: '#7FD1FF', orange: '#FF7A1A', grit: 1, pad: 'jar', light: false },
  specimen: { name: 'Specimen', ink: '#151D1A', paper: '#0E0F0C', card: '#1A2320', card2: '#24302B', empty: '#3A4440', cream: '#E6DFCB', dim: '#A39D8C', pink: '#E0409A', teal: '#2FB8A6', yellow: '#D9B54A', riot: '#C4552B', purple: '#B79CFF', lime: '#B7E23A', sky: '#6FB8D9', orange: '#D9772E', grit: 1.8, pad: 'glass', light: false },
  stage: { name: 'Stage', ink: '#0D0D0D', paper: '#000000', card: '#000000', card2: '#1A1A1A', empty: '#4A4A4A', cream: '#FFFFFF', dim: '#C8C8C8', pink: '#FF2FA0', teal: '#00E5FF', yellow: '#FFE600', riot: '#FF6A00', purple: '#B48CFF', lime: '#A6FF00', sky: '#5AC8FF', orange: '#FF8A00', grit: 0, pad: 'band', light: false },
  felt: { name: 'Felt and Paper', ink: '#DCD3BF', paper: '#E9E2D0', card: '#F3EEE2', card2: '#D8CDB6', empty: '#A89F8C', cream: '#2A2522', dim: '#6B6358', pink: '#C9356F', teal: '#12857B', yellow: '#A87A06', riot: '#BE3D1C', purple: '#6A4CC4', lime: '#5A8A10', sky: '#2F7FB0', orange: '#C0661A', grit: 0, pad: 'felt', light: true },
  rust: { name: 'Rust', ink: '#1C1411', paper: '#140E0B', card: '#231915', card2: '#33241D', empty: '#4A372D', cream: '#EADBC8', dim: '#B09A86', pink: '#D9572B', teal: '#4FA392', yellow: '#D9A441', riot: '#B8361E', purple: '#B08BC9', lime: '#9FBF4A', sky: '#6FA3B8', orange: '#E07B2E', grit: 2, pad: 'jar', light: false },
};
export function applySkin() {
  const s = SKINS[S.skin] || SKINS.classic, r = document.documentElement.style;
  for (const k of ['ink', 'paper', 'card', 'card2', 'empty', 'cream', 'dim', 'pink', 'teal', 'yellow', 'riot', 'purple', 'lime', 'sky', 'orange']) r.setProperty('--' + k, s[k]);
  document.documentElement.dataset.face = s.pad; document.documentElement.dataset.light = s.light ? '1' : '';
  document.documentElement.style.setProperty('--gritA', String(Math.min(1, s.grit)));
  document.documentElement.classList.toggle('big', !!S.big);
}

/* ================= menu ================= */

export function openMenu() {
  sheet('Menu', 'menu-close',
    h('div', { class: 'list' },
      h('button', { class: 'btn', 'data-faq': 'messes', onclick: openMesses }, 'Messes'),
      h('button', { class: 'btn', 'data-faq': 'files', onclick: openFiles }, 'Pour it out (WAV, stems, MIDI)'),
      h('button', { class: 'btn', 'data-faq': 'learn', onclick: openLearn }, 'Learn'),
      h('button', { class: 'btn', 'data-faq': 'settings', onclick: openSettings }, 'Settings'),
      h('button', { class: 'btn', 'data-faq': 'notes-open', onclick: openNotes }, 'Notes for Claude'),
      h('button', { class: 'btn', disabled: !U.redo.length, onclick: () => { doRedo(); closeSheet(); } }, 'Redo'),
      h('button', { class: 'btn', onclick: openAbout }, 'About this web build'),
      window.parent !== window ? h('button', { class: 'btn y', onclick: () => { closeSheet(); window.parent.postMessage({ t: 'mm-close' }, '*'); } }, 'Back to the lab') : null));
}

/* ----- messes ----- */

const messes = () => store.get('messes', []);
export function openMesses() {
  const list = h('div', { class: 'list' });
  const redraw = () => fill(list, ...messes().map((m, n) => h('div', { class: 'it' },
    h('div', { class: 't' }, m.name || 'Untitled mess', h('small', {}, new Date(m.at).toLocaleString())),
    h('button', { class: 'btn y', onclick: () => { if (openMess(m.json, m.name)) closeSheet(); } }, 'Open'),
    h('button', { class: 'btn r', onclick: () => { const a = messes(); const [gone] = a.splice(n, 1); store.set('messes', a); redraw(); toast(`Tossed ${gone.name || 'it'}.`, 'Undo', () => { const b = messes(); b.splice(n, 0, gone); store.set('messes', b); }); } }, 'Toss'))));
  redraw();
  const name = h('input', { class: 'search', value: M.name || '', placeholder: 'Name this mess', maxlength: 48 });
  const file = h('input', { type: 'file', accept: '.json,.mess,.txt,application/json', style: 'display:none' });
  file.addEventListener('change', async () => { const f = file.files[0]; if (f && openMess(await f.text(), f.name)) closeSheet(); });
  const paste = h('textarea', { placeholder: 'Paste a mess here (from the app: Messes, Share, copy) and press Open pasted.' });
  const save = (asNew) => {
    edit(() => T.setName(name.value.trim()), 'name'); const json = T.messJson(); const a = messes(); const i = asNew ? -1 : a.findIndex((m) => m.name === (M.name || 'Untitled mess'));
    const rec = { name: M.name || 'Untitled mess', at: Date.now(), json }; if (i >= 0) a[i] = rec; else a.unshift(rec); store.set('messes', a); redraw(); toast('Saved ' + rec.name + '.');
  };
  sheet('Messes', 'messes-close', name,
    h('div', { class: 'row' },
      h('button', { class: 'btn y', onclick: () => save(false) }, 'Save'),
      h('button', { class: 'btn', onclick: () => save(true) }, 'Save new'),
      h('button', { class: 'btn', onclick: () => { if (openMess(T.starterMess(), 'a new mess')) closeSheet(); } }, 'New'),
      h('button', { class: 'btn', onclick: () => { if (openMess(T.demoMess(), 'the first mess')) closeSheet(); } }, 'Demo'),
      h('button', { class: 'btn', onclick: () => file.click() }, 'Open a file'),
      h('button', { class: 'btn', onclick: () => saveFile((M.name || 'mess').replace(/[^\w -]+/g, '') + '.json', new TextEncoder().encode(T.messJson()), 'application/json') }, 'Save as file'),
      h('button', { class: 'btn', onclick: async () => { try { await navigator.clipboard.writeText(T.messJson()); toast('The mess is copied. Paste it into the app or a file.'); } catch { toast('Copy is blocked here.'); } } }, 'Copy mess')),
    file, list, paste, h('button', { class: 'btn', onclick: () => { if (paste.value.trim() && openMess(paste.value.trim(), 'the pasted mess')) closeSheet(); } }, 'Open pasted'),
    h('p', { class: 'note' }, 'Messes save in this browser and the one you are on autosaves. The file is the app\'s own format, so a mess opens on the phone and the other way round.'));
}

/* ----- files: the app's bounce, stems and MIDI writers ----- */

/** The claude.ai page runtime: this page's own, or the site's when the machine runs inside it (same origin). */
export function claudeRT() { try { return window.claude || (window.parent !== window && window.parent.claude) || null; } catch { return null; } }
async function downloads() { try { const c = claudeRT(); return c ? await c.use('downloads') : null; } catch { return null; } }
export async function saveFile(filename, bytes, type) {
  const dl = await downloads();
  if (dl) { try { await dl.save({ filename, data: bytes }); toast('Saved ' + filename + '.'); return; } catch (e) { toast('Not saved: ' + (e && e.message || e)); return; } }
  try {
    const a = h('a', { href: URL.createObjectURL(new Blob([bytes], { type: type || 'application/octet-stream' })), download: filename });
    document.body.append(a); a.click(); a.remove(); toast('Saved ' + filename + '.');
  } catch { toast('This page cannot save files here.'); }
}
async function ready() {
  T.init(A.ctx ? A.ctx.sampleRate : 44100);
  T.setMess(T.messJson());
  const keys = M.kit.filter(Boolean);
  for (const s of Object.values(M.synths || {})) if (s.src) keys.push(s.src);
  await Promise.all(keys.map(ensureMain));
  T.refresh();
}
export function openFiles() {
  const song = M.mode === 'song' && songLen() > 0;
  const base = (M.name || 'mess').replace(/[^\w -]+/g, '').trim() || 'mess';
  const go = async (label, fn) => { toast(label + '…'); await new Promise((r) => setTimeout(r, 30)); await ready(); await fn(); };
  sheet('Pour it out', 'files-close',
    h('p', { class: 'note' }, 'Rendered by the app\'s own engine, offline, so it sounds like the phone.'),
    h('div', { class: 'list' },
      [1, 2, 4].map((n) => h('button', { class: 'btn', onclick: () => go('Rendering', () => saveFile(`${base} loop ${LETTERS[M.cur]} x${n}.wav`, new Uint8Array(T.wavOf(false, n).buffer), 'audio/wav')) }, `WAV: loop ${LETTERS[M.cur]}, ${n} time${n > 1 ? 's' : ''}`)),
      h('button', { class: 'btn', disabled: !songLen(), onclick: () => go('Rendering the song', () => saveFile(`${base} song.wav`, new Uint8Array(T.wavOf(true, 1).buffer), 'audio/wav')) }, 'WAV: the song'),
      h('button', { class: 'btn', onclick: () => go('Rendering stems', async () => {
        const keys = T.stems(song, song ? 1 : 2).split('\n').filter(Boolean);
        if (!window.JSZip) await new Promise((res, rej) => { const s = document.createElement('script'); s.src = 'https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js'; s.onload = res; s.onerror = rej; document.head.append(s); });
        const zip = new window.JSZip();
        keys.forEach((k, i) => zip.file(`${String(i + 1).padStart(2, '0')} ${titleOf(k).replace(/[^\w -]+/g, '')}.wav`, new Uint8Array(T.stemWav(i).buffer)));
        await saveFile(`${base} stems.zip`, await zip.generateAsync({ type: 'uint8array' }), 'application/zip');
      }) }, 'Stems: every sound on its own, one length (zip)'),
      h('button', { class: 'btn', onclick: () => go('Writing MIDI', () => {
        const names = {}; for (const k of M.kit) if (k) names[k] = titleOf(k);
        return saveFile(`${base}.mid`, new Uint8Array(T.midiOf(song, song ? 1 : 2, JSON.stringify(names)).buffer), 'audio/midi');
      }) }, 'MIDI file (for a DAW)')));
}

/* ----- settings ----- */

export function openSettings() {
  const again = () => openSettings();
  const HOT = [['edit', 'Edit'], ['sample', 'Sample'], ['keys', 'Padiano'], ['fx', 'FX'], ['lfo', 'LFO'], ['xy', 'XY'], ['mute', 'Mute'], ['none', 'Nothing']];
  sheet('Settings', 'settings-close',
    h('div', { class: 'word' }, 'Skin'),
    h('div', { class: 'list' }, Object.entries(SKINS).map(([id, s]) => h('button', { class: 'btn' + (S.skin === id ? ' y' : ''), 'data-faq': 'look-' + id,
      onclick: () => { S.skin = id; store.set('skin', id); applySkin(); UI.grit && UI.grit(); UI.paint(); again(); } }, s.name))),
    h('div', { class: 'row', style: 'margin:10px 0' },
      h('button', { class: 'btn' + (S.big ? ' y' : ''), 'data-faq': 'look-large', onclick: () => { S.big = !S.big; store.set('big', S.big); applySkin(); UI.paint(); again(); } }, 'Large keys'),
      h('button', { class: 'btn' + (store.get('buzz', true) ? ' y' : ''), onclick: () => { store.set('buzz', !store.get('buzz', true)); again(); } }, 'Buzz on pad hits'),
      h('button', { class: 'btn' + (store.get('awake', true) ? ' y' : ''), onclick: () => { store.set('awake', !store.get('awake', true)); UI.wakeLock && UI.wakeLock(); again(); } }, 'Keep the screen awake')),
    h('div', { class: 'word' }, 'HotKeys: a swipe off a pad'),
    ['up', 'down', 'left', 'right'].map((d) => h('div', { class: 'row', style: 'margin-top:6px' }, h('span', { class: 'word', style: 'min-width:5em' }, d),
      h('button', { class: 'btn', 'data-faq': 'swipe-' + d, onclick: () => chooser('Swipe ' + d, HOT, S.hot[d], (v) => { S.hot[d] = v; store.set('hot', S.hot); again(); }) }, (HOT.find((x) => x[0] === S.hot[d]) || HOT[7])[1] + ' ▾'))));
}

/* ----- notes for Claude ----- */

async function dbNs() { try { const c = claudeRT(); return c ? await c.use('db') : null; } catch { return null; } }
export function openNotes() {
  const box = h('textarea', { placeholder: 'What happened, what you expected, what you want. Claude reads these.' });
  const st = h('p', { class: 'note' });
  sheet('Notes for Claude', 'notes-close', box,
    h('div', { class: 'row' }, h('button', { class: 'btn y', onclick: async () => {
      const text = box.value.trim(); if (!text) return;
      const note = { text, build: BUILD, at: new Date().toISOString(), ua: navigator.userAgent, mess: `${M.name || 'untitled'}: ${M.kit.filter(Boolean).length} pads, loop ${LETTERS[M.cur]} ${pat().len / 16} bars, ${M.bpm} bpm, tab ${S.tab}`, last: S.trail.slice(-15) };
      const db = await dbNs();
      if (db) { try { await db.collection('notes').doc(String(Date.now())).set(note); st.textContent = 'Sent. Claude will see it.'; box.value = ''; return; } catch (e) { st.textContent = 'Could not send: ' + e.message; } }
      const q = store.get('notes', []); q.push(note); store.set('notes', q); st.textContent = 'Kept in this browser (not signed in here). Copy it into the chat if you like.'; box.value = '';
    } }, 'Send')), st);
}
export function openAbout() {
  sheet('About this web build', 'about-close',
    h('p', { class: 'note' }, `${BUILD}. The sound is the Android app's own engine (build 170), compiled to run in the browser, so a mess plays the same here as on the phone.`),
    h('p', { class: 'note' }, 'Web only: keyboard keys 1234 qwer asdf zxcv play the bank, space plays and pauses.'),
    h('p', { class: 'note' }, 'Inside claude.ai the page cannot open the microphone or MIDI. Record to pad picks a recording from your phone instead.'));
}

/* ----- record to pad: the mic where the page may use it, else a recording picked from the phone ----- */

export async function recordToPad(pad) {
  if (S.recording) { S.recording.stop(); return; }
  let stream = null;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); } catch {}
  if (!stream) { pickRecording(pad); return; }
  const rec = new MediaRecorder(stream), parts = [];
  rec.ondataavailable = (e) => parts.push(e.data);
  rec.onstop = async () => { stream.getTracks().forEach((t) => t.stop()); S.recording = null; UI.paint(); await placeAudio(pad, await new Blob(parts).arrayBuffer(), 'Mic take'); };
  S.recording = rec; rec.start(); toast(`Recording onto pad ${pad + 1}. Tap the pad again to stop.`); UI.paint();
}
function pickRecording(pad) {
  const f = h('input', { type: 'file', accept: 'audio/*', capture: 'user', style: 'display:none' });
  f.addEventListener('change', async () => { const file = f.files[0]; if (file) await placeAudio(pad, await file.arrayBuffer(), file.name.replace(/\.[^.]+$/, '').slice(0, 30)); f.remove(); });
  document.body.append(f);
  toast('This page cannot use the mic. Pick or record a sound on your phone and it lands on the pad.');
  f.click();
}
async function placeAudio(pad, buf, title) {
  try {
    const ctx = A.ctx || new AudioContext();
    const ab = await ctx.decodeAudioData(buf.slice(0));
    const n = ab.length, mono = new Float32Array(n);
    for (let c = 0; c < ab.numberOfChannels; c++) { const d = ab.getChannelData(c); for (let i = 0; i < n; i++) mono[i] += d[i] / ab.numberOfChannels; }
    const key = 'bn:' + Date.now().toString(36) + String(Math.floor(Math.random() * 1000)).padStart(3, '0');
    const wav = wav16(mono, ab.sampleRate);
    store.set('own.' + key, title);
    LIBS.LIB[key] = { k: key, t: title, c: 'mine', f: '', ty: 'mine', q: [] };
    await keepOwn(key, wav);
    await ensure(key);
    edit(() => T.assign(pad, key), 'sound on pad ' + (pad + 1));
    call('refresh');
    toast(`${title} is on pad ${pad + 1}.`);
  } catch (e) { toast('Could not read that sound: ' + (e && e.message || e)); }
}
export function wav16(d, rate) {
  const b = new ArrayBuffer(44 + d.length * 2), v = new DataView(b);
  const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  w(0, 'RIFF'); v.setUint32(4, 36 + d.length * 2, true); w(8, 'WAVE'); w(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); w(36, 'data'); v.setUint32(40, d.length * 2, true);
  for (let i = 0; i < d.length; i++) v.setInt16(44 + i * 2, Math.max(-1, Math.min(1, d[i])) * 32767, true);
  return new Uint8Array(b);
}

/* ================= Learn: lessons that watch the real machine (Tutorials.kt) ================= */

const LESSONS = [
  { id: 'pads', group: 'Start here', title: 'Pads and banks', steps: [
    ['Tap a few pads.', (c) => c.hits >= 3],
    ['Tap 2 under BANK: a second set of 16 pads.', () => S.bank === 1],
    ['Back to bank 1.', () => S.bank === 0],
    ['Tap Edit on the bar at the bottom, then tap a pad: its Sample sheet opens instead of it playing. A flick up off a pad does the same.', () => $('#sheet').classList.contains('on')],
    ['Close it: the screw in its corner, or Back.', () => !$('#sheet').classList.contains('on')]] },
  { id: 'steps', group: 'Writing', title: 'Steps', steps: [
    ['Tap SEQ.', () => S.tab === 'seq'],
    ['Tap a few steps on the first row.', (c) => c.v !== M_steps()],
    ['Press Play.', () => S.playing],
    ['Press Stop.', () => !S.playing]] },
  { id: 'live', group: 'Writing', title: 'Record live', steps: [
    ['Tap the record button at the top so it lights up.', () => S.rec],
    ['Press Play.', () => S.playing],
    ['Play a beat on the pads, in time with the loop.', (c) => c.v !== M_steps()],
    ['Press Stop, then tap record again to turn it off.', () => !S.playing && !S.rec],
    ['Tap SEQ to see what you played.', () => S.tab === 'seq']] },
  { id: 'hold', group: 'Playing', title: 'Hold', steps: [
    ['Tap Hold on the bottom bar to turn it on.', () => S.hold],
    ['Tap a pad. It keeps playing on its own, and an H shows in its corner.', () => S.latched.length >= 1],
    ['Tap a second pad. It holds too: Hold keeps as many as its number (flick up on Hold to change it).', () => S.latched.length >= 2],
    ['Tap each held pad again to let them go.', () => S.latched.length === 0],
    ['Tap Hold again to turn it off.', () => !S.hold]] },
  { id: 'padiano', group: 'Playing', title: 'Padiano', steps: [
    ['Tap the first pad, then Pack on the bar at the bottom: its FannyPack opens with the Padiano in it.', () => S.open.has(M.sel)],
    ['Play some keys. In key only (the gear) keeps every key in the mess\'s key.', (c) => c.keys >= 3],
    ['Tap Pack on the bottom bar to zip the pack shut.', () => !S.open.has(M.sel)]] },
  { id: 'chords', group: 'Playing', title: 'Chords and key', steps: [
    ['Tap Pack on the bottom bar: the sound\'s Padiano opens under its row.', () => S.open.has(M.sel)],
    ['Tap the key readout on the bar at the bottom, then pick a different scale.', (c) => M.scale !== c.scale],
    ['Tap Chords on the bottom bar: each key now plays a chord in the key.', () => S.chord !== 'off'],
    ['Play a few keys.', (c) => c.keys >= 3],
    ['Tap Pack to zip it shut.', () => !S.open.has(M.sel)]] },
  { id: 'synth', group: 'Synths', title: 'Make a synth', steps: [
    ['Tap SYNTH.', () => S.tab === 'synth'],
    ['Tap To pad: the synth you see lands there.', (c) => M.kit.slice(0, 64).filter((k) => k.startsWith('synth:')).length > c.synths],
    ['Play the keyboard under the knobs.', (c) => c.keys >= 2],
    ['Pick Acid, then play the keys: one voice that slides.', () => { const s = JSON.parse(T.synthJson(M.sel) || 'null'); return s && s.kind === 'analog' && Math.round(s.v.mode) === 2; }]] },
  { id: 'roll', group: 'Writing', title: 'Roll', steps: [
    ['Tap SEQ, then Roll at the top.', () => S.tab === 'seq' && S.seq === 'roll'],
    ['Tap in the grid to draw a few notes.', (c) => T.notesOf(M.kit[M.sel] || '') !== c.notes],
    ['Press Play.', () => S.playing], ['Press Stop.', () => !S.playing]] },
  { id: 'song', group: 'Writing', title: 'Song', steps: [
    ['Tap the loop readout at the top, then Song.', () => S.stripOpen && S.songSide],
    ['Tap a few bars to fill them with the loop.', () => songLen() >= 2],
    ['Pick Play song, then press Play: it plays the song, bar by bar.', () => S.playing && M.mode === 'song'],
    ['Press Stop.', () => !S.playing]] },
  { id: 'sheet', group: 'Samples', title: 'The Sample sheet', steps: [
    ['Flick up off a pad (or Edit, then the pad).', () => $('#sheet').classList.contains('on') && $('#sheet .h2').textContent === 'Sample'],
    ['Tap Trim. Drag the yellow lines to cut the start or the end.', (c) => T.editJson(M.kit[M.sel] || '') !== c.edit],
    ['Tap FX and turn one up (reverb, echo, dirt...).', () => /"rvb":0\.[1-9]|"echo":0\.[1-9]|"dirt":0\.[1-9]|"crush":0\.[1-9]/.test(T.editJson(M.kit[M.sel] || ''))]] },
  { id: 'sphere', group: 'Playing', title: 'XY sphere', steps: [
    ['Press a pad and, without lifting, draw a small circle on it.', () => !!document.querySelector('.sphere')],
    ['Keep your finger down and push the ball around. Lift to let go.', () => !document.querySelector('.sphere')]] },
];
const M_steps = () => JSON.stringify(M.pats[M.cur].steps);
export function openLearn() {
  const done = store.get('learned', []);
  const groups = [...new Set(LESSONS.map((l) => l.group))];
  sheet('Learn', 'learn-close', groups.map((g) => [h('div', { class: 'word', style: 'margin-top:10px' }, g),
    h('div', { class: 'list' }, LESSONS.filter((l) => l.group === g).map((l) => h('button', { class: 'btn' + (done.includes(l.id) ? ' y' : ''), onclick: () => { closeSheet(); startLesson(l); } }, l.title + (done.includes(l.id) ? ' ✓' : ''))))]));
}
let lesson = null;
export const lessonCtx = { hits: 0, keys: 0 };
function startLesson(l) {
  lesson = { l, i: 0, c: null };
  armStep();
  trail('lesson ' + l.id);
}
function armStep() {
  const { l, i } = lesson;
  lessonCtx.hits = 0; lessonCtx.keys = 0;
  lesson.c = { v: M_steps(), scale: M.scale, synths: M.kit.slice(0, 64).filter((k) => k.startsWith('synth:')).length, notes: T.notesOf(M.kit[M.sel] || ''), edit: T.editJson(M.kit[M.sel] || ''), get hits() { return lessonCtx.hits; }, get keys() { return lessonCtx.keys; } };
  const bar = $('#tutor');
  fill(bar, h('div', { class: 'tt' }, h('b', {}, `${l.title} · ${i + 1} of ${l.steps.length}`), h('span', {}, l.steps[i][0])),
    h('button', { class: 'btn', onclick: () => { lesson.i++; next(); } }, 'Skip step'), h('button', { class: 'closer', 'aria-label': 'End the lesson', onclick: endLesson }));
  bar.classList.add('on');
}
function next() { if (!lesson) return; if (lesson.i >= lesson.l.steps.length) { const d = store.get('learned', []); if (!d.includes(lesson.l.id)) d.push(lesson.l.id); store.set('learned', d); toast(`${lesson.l.title}: done.`); endLesson(); } else armStep(); }
function endLesson() { lesson = null; $('#tutor').classList.remove('on'); }
/** Called often: moves the lesson on when its step has been done for real. */
export function checkLesson() { if (!lesson) return; try { if (lesson.l.steps[lesson.i][1](lesson.c)) { lesson.i++; next(); } } catch {} }
