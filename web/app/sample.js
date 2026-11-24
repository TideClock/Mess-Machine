// The Sample sheet (DoctorScreen.kt): waveform with trim lines, Hear it, Loop the trim, and pages Play, Trim, Shape,
// FX and Tools. Every change is the pad sound's edit (the doctor's table) in the mess, so it saves and plays the same.
import { S, M, T, h, fill, $, key, pick, sheet, closeSheet, field, toast, sayUndo, edit, live, call, kitKey, titleOf, trail, UI, ensure, ensureMain, wake, pct, addChop,
  chopOf, LETTERS, entryOf } from './core.js';

let page = 'play';
export function openSample(i, pg) {
  const k = kitKey(i); if (!k) return;
  if (k.startsWith('synth:')) { T.setSel(i); M.sel = i; S.synthScratch = false; S.tab = 'synth'; closeSheet(); UI.paint(); return; }
  if (pg) page = pg;
  T.setSel(i); M.sel = i;
  const ed = JSON.parse(T.editJson(k));
  const fx = ed.fx || { rvb: 0, rvbSize: 0.5, echo: 0, echoT: 1, dirt: 0, crush: 0, chorus: 0, wob: 0, wobRate: 0.3 };
  const write = () => { const e2 = { ...ed, fx }; T.setEditJson(k, JSON.stringify(e2)); };
  const set = (n, v) => { live.begin(); if (n in fx && n !== 'fx') fx[n] = v; else ed[n] = v; write(); live.move(); };
  const done = () => live.end('sample ' + titleOf(k));
  const now = (n, v) => { set(n, v); done(); openSample(i); };
  const again = () => openSample(i);
  const sec = T.seconds(k);
  const wave = h('canvas', { class: 'wave', 'data-faq': 'doctor-wave' });
  const head = h('div', { class: 'note', style: 'margin:0 0 6px' }, sec ? `${sec.toFixed(2)} s · plays ${((ed.en - ed.st) * sec).toFixed(2)} s` : 'loading…');
  const sw = (name, on, fn, tag) => h('button', { class: 'btn' + (on ? ' y' : ''), 'data-faq': tag, onclick: fn }, name);
  const pages = pick([['play', 'Play'], ['trim', 'Trim'], ['shape', 'Shape'], ['fx', 'FX'], ['tools', 'Tools']], page, (v) => { page = v; again(); }, { tag: 'edit-tabs' });
  let body;
  if (page === 'play') body = [
    h('div', { class: 'word' }, 'Pitch'),
    pick([['auto', 'Auto'], ['stretch', 'Stretch'], ['grain', 'Grain']], ed.pitch || 'auto', (v) => now('pitch', v), { tag: 'doctor-pitch' }),
    h('p', { class: 'note' }, 'Stretch is tape: higher is faster and shorter. Grain keeps the length. Auto: keys keep length, the Tune here stays tape.'),
    h('div', { class: 'word' }, 'When it is held'),
    h('div', { class: 'row' }, [['loop', 'Loop it'], ['tail', 'Hold the tail'], ['once', 'Play once'], ['sustain', 'Sustain'], ['beat', 'On the beat']].map(([v, n]) => sw(n, (ed.held || 'loop') === v, () => now('held', v), 'held-' + v))),
    (ed.held === 'beat') ? h('div', { class: 'row' }, [[1, 'Every beat'], [2, '2 beats'], [4, '1 bar']].map(([v, n]) => sw(n, (ed.beatN || 1) === v, () => now('beatN', v), 'beatn-' + v))) : null,
    h('div', { class: 'word' }, 'Plays as'),
    pick([['one', 'One-shot'], ['gate', 'Gate (stops on lift)']], ed.mode || 'one', (v) => now('mode', v), { tag: 'doctor-mode' }),
  ];
  else if (page === 'trim') body = [
    h('div', { class: 'row' }, sw('Reverse', ed.rev, () => now('rev', !ed.rev)), sw('Loop', ed.loop, () => now('loop', !ed.loop)), sw('Normalize', ed.norm, () => now('norm', !ed.norm))),
    field('Start', 0, 0.99, 0.001, ed.st, pct, (v) => { set('st', Math.min(v, ed.en - 0.01)); drawWave(); }, done),
    field('End', 0.01, 1, 0.001, ed.en, pct, (v) => { set('en', Math.max(v, ed.st + 0.01)); drawWave(); }, done),
    h('p', { class: 'note' }, 'Or drag the yellow lines on the wave.'),
  ];
  else if (page === 'shape') body = [
    field('Volume', 0, 2, 0.01, ed.vol, pct, (v) => set('vol', v), done),
    field('Tune', -24, 24, 1, ed.tune, (v) => (v > 0 ? '+' : '') + v + ' st', (v) => set('tune', v), done),
    field('Fine', -100, 100, 1, ed.fine, (v) => v + ' ct', (v) => set('fine', v), done),
    field('Pan', -1, 1, 0.01, ed.pan, (v) => Math.abs(v) < 0.02 ? 'center' : v < 0 ? Math.round(-v * 100) + '% left' : Math.round(v * 100) + '% right', (v) => set('pan', v), done),
    field('Tone', -1, 1, 0.01, ed.tone, (v) => Math.abs(v) < 0.02 ? 'open' : v < 0 ? 'darker ' + Math.round(-v * 100) : 'thinner ' + Math.round(v * 100), (v) => set('tone', v), done),
    field('Attack', 0, 1, 0.005, ed.att, (v) => Math.round(v * 1000) + ' ms', (v) => set('att', v), done),
    field('Release', 0, 1.5, 0.005, ed.rel, (v) => Math.round(v * 1000) + ' ms', (v) => set('rel', v), done),
    field('Choke', 0, 4, 1, ed.choke, (v) => v ? 'group ' + v : 'none', (v) => set('choke', v), done),
  ];
  else if (page === 'fx') body = [
    h('div', { class: 'word' }, 'Reverb'), field('Amount', 0, 1, 0.01, fx.rvb, pct, (v) => set('rvb', v), done),
    field('Space', 0, 1, 0.01, fx.rvbSize, (v) => v < 0.33 ? 'small room' : v < 0.66 ? 'hall' : 'cave', (v) => set('rvbSize', v), done),
    h('div', { class: 'word' }, 'Echo'), field('Amount', 0, 1, 0.01, fx.echo, pct, (v) => set('echo', v), done),
    pick([[0, '1/16'], [1, '1/8'], [2, '3/16'], [3, '1/4']], fx.echoT, (v) => now('echoT', v)),
    h('div', { class: 'word' }, 'Dirt'), field('Drive', 0, 1, 0.01, fx.dirt, pct, (v) => set('dirt', v), done),
    h('div', { class: 'word' }, 'Crush'), field('Bits and rate', 0, 1, 0.01, fx.crush, pct, (v) => set('crush', v), done),
    h('div', { class: 'word' }, 'Chorus'), field('Depth', 0, 1, 0.01, fx.chorus, pct, (v) => set('chorus', v), done),
    h('div', { class: 'word' }, 'Wobble'), field('Depth', 0, 1, 0.01, fx.wob, pct, (v) => set('wob', v), done),
    field('Speed', 0, 1, 0.01, fx.wobRate, (v) => (0.3 + v * 5.7).toFixed(1) + ' Hz', (v) => set('wobRate', v), done),
  ];
  else body = [
    h('div', { class: 'word' }, 'Fit to tempo'),
    h('div', { class: 'row' }, [0.5, 1, 2, 4].map((b) => h('button', { class: 'btn', onclick: () => fitToTempo(k, b, i) }, b < 1 ? 'Half bar' : b + (b > 1 ? ' bars' : ' bar')))),
    h('div', { class: 'word' }, 'Chop to pads (equal slices of the trim)'),
    h('div', { class: 'row' }, [2, 4, 8, 16].map((n) => h('button', { class: 'btn', onclick: () => chopToPads(k, n, i) }, n + ' slices'))),
    h('div', { class: 'word' }, 'This pad'),
    h('div', { class: 'row' },
      h('button', { class: 'btn', onclick: () => { S.pickFor = i; S.tab = 'sounds'; closeSheet(); UI.paint(); } }, 'Swap sound'),
      h('button', { class: 'btn' + ((M.mutes || []).includes(k) ? ' y' : ''), onclick: () => { edit(() => T.toggleMute(k), 'mute'); again(); } }, 'Mute'),
      h('button', { class: 'btn' + ((M.solos || []).includes(k) ? ' y' : ''), onclick: () => { edit(() => T.toggleSolo(k), 'solo'); again(); } }, 'Solo'),
      h('button', { class: 'btn r', onclick: () => { edit(() => T.clearSoundInLoop(i), 'out of loop'); sayUndo(`Pad ${i + 1} is out of loop ${LETTERS[M.cur]}.`); } }, 'Out of this loop'),
      h('button', { class: 'btn r', onclick: () => { closeSheet(); edit(() => T.clearPad(i), 'clear pad'); sayUndo(`Pad ${i + 1} cleared.`); } }, 'Clear pad'),
      h('button', { class: 'btn r', onclick: () => { edit(() => T.setEditJson(k, '{}'), 'reset sound'); toast('Back to how the sound came.'); again(); } }, 'Reset everything')),
  ];
  sheet('Sample', 'tray-done',
    h('div', { class: 'stitle' }, titleOf(k)), head, h('div', { class: 'wavebox' }, wave),
    h('div', { class: 'row', style: 'margin:8px 0' },
      h('button', { class: 'btn', style: 'border-color:var(--teal)', 'data-faq': 'doctor-hear', onclick: () => { wake(); call('stopPreviews'); call('preview', k, 0, false); } }, 'Hear it'),
      h('button', { class: 'btn', 'data-faq': 'doctor-loop', onclick: (e) => { wake(); const on = !e.currentTarget.classList.contains('y'); e.currentTarget.classList.toggle('y', on); call('stopPreviews'); if (on) call('preview', k, 0, true); } }, 'Loop the trim')),
    pages, h('div', { class: 'spage' }, body));
  // the wave: drawn from the page's own copy of the sound
  function drawWave() {
    const c = wave, r = c.getBoundingClientRect(); if (!r.width) return;
    const dpr = devicePixelRatio || 1; c.width = r.width * dpr; c.height = r.height * dpr;
    const g = c.getContext('2d'); g.scale(dpr, dpr); g.clearRect(0, 0, r.width, r.height);
    const cols = Math.floor(r.width), o = T.overview(k, 0, 1, cols);
    const mid = r.height / 2;
    g.fillStyle = '#FFD23F';
    let pk = 1e-4; for (let n = 0; n < o.length; n++) pk = Math.max(pk, Math.abs(o[n]));
    for (let x = 0; x < cols && o.length; x++) { const lo = o[x * 2] / pk, hi = o[x * 2 + 1] / pk; g.globalAlpha = x / cols >= ed.st && x / cols <= ed.en ? 1 : 0.3; g.fillRect(x, mid - hi * mid * 0.92, 1, Math.max(1, (hi - lo) * mid * 0.92)); }
    g.globalAlpha = 1; g.fillStyle = '#FFF4E2';
    g.fillRect(ed.st * r.width - 1, 0, 3, r.height); g.fillRect(ed.en * r.width - 2, 0, 3, r.height);
  }
  ensureMain(k).then(() => { const s2 = T.seconds(k); if (s2) head.textContent = `${s2.toFixed(2)} s · plays ${((ed.en - ed.st) * s2).toFixed(2)} s`; requestAnimationFrame(drawWave); });
  let drag = null;
  wave.addEventListener('pointerdown', (e) => {
    const r = wave.getBoundingClientRect(), f = (e.clientX - r.left) / r.width;
    drag = Math.abs(f - ed.st) < Math.abs(f - ed.en) ? 'st' : 'en';
    try { wave.setPointerCapture(e.pointerId); } catch {}
    moveTo(e);
  });
  const moveTo = (e) => { if (!drag) return; const r = wave.getBoundingClientRect(); let f = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    if (drag === 'st') f = Math.min(f, ed.en - 0.01); else f = Math.max(f, ed.st + 0.01); set(drag, f); drawWave(); };
  wave.addEventListener('pointermove', moveTo);
  wave.addEventListener('pointerup', () => { if (drag) { drag = null; done(); call('preview', k, 0, false); } });
}

/** AppState.fitToTempo: re-pitch so the trimmed sound fills a length at the current tempo. */
async function fitToTempo(k, bars, i) {
  await ensureMain(k);
  const ed = JSON.parse(T.editJson(k));
  const len = (ed.en - ed.st) * T.seconds(k), target = bars * 16 * 60 / M.bpm / 4;
  const semis = 12 * Math.log(len / target) / Math.log(2);
  if (Math.abs(semis) > 24.5) { toast('That would take more than two octaves of re-pitching. Trim closer to the length first.'); return; }
  ed.tune = Math.round(semis); ed.fine = Math.round((semis - ed.tune) * 100);
  edit(() => T.setEditJson(k, JSON.stringify(ed)), 'fit to tempo');
  toast(`Re-pitched ${semis > 0 ? 'up' : 'down'} ${Math.abs(semis).toFixed(2)} semitones to fill it at ${M.bpm} BPM.`);
  openSample(i);
}
/** AppState.chopToPads with equal cuts: each slice is a chop on the next free pad. */
function chopToPads(k, n, i) {
  const ed = JSON.parse(T.editJson(k));
  const bounds = []; for (let q = 0; q <= n; q++) bounds.push(ed.st + (ed.en - ed.st) * q / n);
  const base = chopOf(k) || k, root = titleOf(k).replace(/ chop \d+$/, '').slice(0, 22);
  const free = []; for (let p = 0; p < 64; p++) if (!kitKey(p)) free.push(p);
  const keys = []; for (let q = 0; q < n; q++) keys.push(addChop(base, `${root} chop ${q + 1}`));
  let placed = 0;
  edit(() => keys.forEach((ck, q) => { T.setEditJson(ck, JSON.stringify({ ...ed, st: bounds[q], en: bounds[q + 1] })); if (q < free.length) { T.assign(free[q], ck); placed++; } }), 'chop ' + n);
  Promise.all(keys.map(ensure)).then(() => call('refresh'));
  if (placed) S.bank = Math.floor(free[0] / 16);
  closeSheet();
  toast(`${n} slices in Your Workbench` + (placed < n ? `, ${placed} on pads (no more empty pads).` : ', all on pads.'));
  UI.paint();
}
