// The Synth tab (SE-11, SynthScreen.kt): the synth on the picked pad, or the scratch synth (slot 64) when that pad has
// none. Simple shows the knobs that matter most, Full every knob in groups. The keyboard is the same Padiano.
import { S, M, T, h, fill, key, pick, sheet, chooser, toast, edit, live, call, kitKey, titleOf, trail, faqTap, UI, SCRATCH, wake, ensure } from './core.js';
import { padiano } from './play.js';

let KINDS = null;
const kinds = () => (KINDS || (KINDS = JSON.parse(T.synthKinds())));
const ICON = { analog: 'analog', fm: 'fm', wave: 'wavetable', grain: 'granular', drum: 'drums', pluck: 'pluck' };
const SHORT = { analog: 'Analog', fm: 'FM', wave: 'Wave', grain: 'Grain', drum: 'Drum', pluck: 'Pluck' };

export function renderSynth(tab) {
  T.ensureScratch();
  const synthPads = M.kit.map((k, i) => (i < 64 && k.startsWith('synth:') ? i : -1)).filter((i) => i >= 0);
  const scratch = S.synthScratch || !kitKey(M.sel).startsWith('synth:');
  const pad = scratch ? SCRATCH : M.sel;
  const sp = JSON.parse(T.synthJson(pad) || 'null');
  if (!sp) { fill(tab, h('p', { class: 'note' }, 'No synth here.')); return; }
  const kind = kinds().find((k) => k.id === sp.kind) || kinds()[0];
  const tile = h('button', { class: 'synthtile', 'data-faq': 'synth-tile', 'aria-label': 'Play the synth' }, h('img', { src: 'icons/ic_' + ICON[kind.id] + '.png', alt: '' }));
  tile.addEventListener('pointerdown', (e) => { if (faqTap('synth-tile', e)) return; wake(); call('down', 990, pad, '0', true, false, -1); tile.classList.add('down'); });
  const off = () => { call('up', 990); tile.classList.remove('down'); };
  tile.addEventListener('pointerup', off); tile.addEventListener('pointercancel', off);
  const which = h('button', { class: 'btn', 'data-faq': 'synth-pad', onclick: () => chooser('Which synth', [[-1, 'Scratch synth']].concat(synthPads.map((i) => [i, `Pad ${i + 1} · ${titleOf(kitKey(i))}`])), scratch ? -1 : pad,
    (v) => { if (v < 0) S.synthScratch = true; else { S.synthScratch = false; T.setSel(v); M.sel = v; } UI.paint(); }) }, (scratch ? 'Scratch' : 'Pad ' + (pad + 1)) + ' ▾');
  const preset = h('button', { class: 'btn', 'data-faq': 'synth-preset', onclick: () => chooser(kind.name + ' presets', kind.presets.map((n, i) => [i, n]), kind.presets.indexOf(sp.name),
    (i) => { edit(() => T.loadSynthPreset(pad, kind.id, i), 'synth preset'); loadSrc(); }) }, sp.name + ' ▾');
  const mode = kind.params.find((p) => p.id === 'mode');
  const head = h('div', { class: 'synthhead' }, tile, h('div', { class: 'col' },
    h('div', { class: 'row' }, which, preset),
    h('div', { class: 'row' }, pick([[false, 'Simple'], [true, 'Full']], S.synthFull, (v) => { S.synthFull = v; UI.paint(); }, { tag: 'synthview' }),
      mode ? pick(mode.choices.map((c, i) => [i, c]), Math.round(sp.v.mode || 0), (i) => edit(() => T.setSynth(pad, 'mode', i), 'synth mode'), { tag: 'synthmode' }) : null)));
  const kindsRow = pick(kinds().filter((k) => !scratch || k.id !== 'grain').map((k) => [k.id, SHORT[k.id]]), kind.id,
    (id) => { if (id !== kind.id) { edit(() => T.setSynthKind(pad, id), 'synth kind'); loadSrc(); } }, { tag: 'synthkind' });
  const put = [];
  if (scratch) {
    let empty = -1; for (let i = S.bank * 16; i < S.bank * 16 + 16; i++) if (!kitKey(i)) { empty = i; break; }
    if (empty >= 0) put.push(h('button', { class: 'btn', 'data-faq': 'synth-new-empty', onclick: () => toPad(empty) }, `To pad ${empty + 1}`));
    const sel = M.sel, full = !!kitKey(sel), armed = S.replaceArmed === sel;
    if (!full) { if (empty !== sel) put.push(h('button', { class: 'btn', 'data-faq': 'synth-new-here', onclick: () => toPad(sel) }, `To pad ${sel + 1}`)); }
    else put.push(h('button', { class: 'btn' + (armed ? ' r' : ''), 'data-faq': 'synth-new-here', onclick: () => { if (armed) { S.replaceArmed = -1; toPad(sel); } else { S.replaceArmed = sel; toast(`Tap again to replace ${titleOf(kitKey(sel))} with this synth.`); UI.paint(); } } },
      armed ? `Replace ${sel + 1}?` : `Over ${sel + 1}`));
  }
  const model = kind.params.find((p) => p.id === 'model');
  const models = model ? h('div', { class: 'row', 'data-faq': 'synthmodel-chips' }, model.choices.map((c, i) => h('button', { class: 'btn' + (Math.round(sp.v.model) === i ? ' y' : ''),
    onclick: () => edit(() => T.setSynth(pad, 'model', i), 'synth model') }, c))) : null;
  let grain = null;
  if (kind.id === 'grain') {
    const srcs = [...new Set(M.kit.filter((k) => k && !k.startsWith('synth:')))];
    grain = h('div', { class: 'row' }, h('span', { class: 'word' }, 'Grains of'),
      srcs.length ? h('button', { class: 'btn', 'data-faq': 'grain-src', onclick: () => chooser('Grains of', srcs.map((k) => [k, titleOf(k)]), sp.src, (k) => { edit(() => T.setGrainSource(pad, k), 'grain source'); ensure(k).then(() => call('refresh')); }) }, (titleOf(sp.src) || 'Pick a sound') + ' ▾')
        : h('span', { class: 'note' }, 'No sounds on pads yet.'));
  }
  const knobs = [];
  if (S.synthFull) {
    for (const g of [...new Set(kind.params.map((p) => p.group))]) {
      const ps = kind.params.filter((p) => p.group === g && p.id !== 'mode' && p.id !== 'model');
      if (!ps.length) continue;
      knobs.push(h('div', { class: 'word', style: 'margin:8px 0 4px;color:var(--dim)' }, g), knobRow(pad, kind, sp, ps));
    }
  } else knobs.push(knobRow(pad, kind, sp, kind.params.filter((p) => p.simple && p.id !== 'mode' && p.id !== 'model')));
  fill(tab, head, kindsRow, put.length ? h('div', { class: 'row', style: 'justify-content:flex-end;margin:8px 0' }, put) : null, models, grain, ...knobs,
    h('div', { style: 'margin-top:12px' }, padiano(pad, { synth: true })));
  function loadSrc() { const s = JSON.parse(T.synthJson(pad) || 'null'); if (s && s.src) ensure(s.src).then(() => call('refresh')); }
}
function toPad(i) {
  if (edit(() => T.scratchToPad(i), 'scratch synth to pad ' + (i + 1))) { S.synthScratch = false; toast(`The synth is on pad ${i + 1}. Its knobs are here; Steps and the Padiano play it.`); UI.paint(); }
}

/** A row of knobs: drag up or down to turn, double tap to put it back. */
function knobRow(pad, kind, sp, params) {
  return h('div', { class: 'knobs' }, params.map((p) => {
    const v = sp.v[p.id] != null ? sp.v[p.id] : p.def;
    const k = T.paramToKnob(kind.id, p.id, v);
    const el = h('div', { class: 'knob', 'data-faq': 'knob-' + p.id, style: `--k:${k}` }, h('div', { class: 'dial' }, h('i')), h('span', { class: 'kn' }, p.name), h('span', { class: 'kv' }, T.paramShow(kind.id, p.id, v)));
    let y0 = 0, k0 = 0, lastTap = 0, cur = k;
    el.addEventListener('pointerdown', (e) => {
      if (faqTap('knob-*', e)) return;
      e.preventDefault(); try { el.setPointerCapture(e.pointerId); } catch {}
      const now = performance.now();
      if (now - lastTap < 300) { edit(() => T.setSynth(pad, p.id, p.def), 'knob reset'); lastTap = 0; return; }
      lastTap = now; y0 = e.clientY; k0 = cur; el.dataset.drag = '1'; live.begin();
    });
    el.addEventListener('pointermove', (e) => {
      if (!el.dataset.drag) return;
      cur = Math.max(0, Math.min(1, k0 + (y0 - e.clientY) / 160));
      const val = T.paramFromKnob(kind.id, p.id, cur);
      T.setSynth(pad, p.id, val); live.move();
      el.style.setProperty('--k', cur); el.querySelector('.kv').textContent = T.paramShow(kind.id, p.id, val);
    });
    const up = () => { if (!el.dataset.drag) return; delete el.dataset.drag; live.end('knob ' + p.id); };
    el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
    return el;
  }));
}
