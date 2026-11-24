// The Seq tab: Steps (accent and ghost, drag paint, the step sheet, row tools) and the Roll (C1 to C6, lengths,
// fold to the scale, chords as stacks). Behaviour follows StepsScreen, RollScreen and AppState.
import { S, M, T, U, h, fill, $, key, pick, sheet, closeSheet, toast, sayUndo, edit, live, call, kitKey, titleOf, padCol, trail, faqTap, noteName, pcCol, UI, pat,
  stepOn, accOf, notesAt, LETTERS, reread, send, autosave } from './core.js';
import { bankRow, chordFor } from './play.js';

export function renderSeq(tab) {
  const head = h('div', { class: 'row' }, h('span', { class: 'word', style: 'color:var(--dim)' }, 'Sequence'),
    pick([['steps', 'Steps'], ['roll', 'Roll']], S.seq, (v) => { S.seq = v; trail('seq ' + v); UI.paint(); }, { tag: 'seq' }));
  if (S.seq === 'roll') { fill(tab, head, roll()); return; }
  const P = pat();
  const pages = P.len / 16;
  if (S.page >= pages) S.page = pages - 1;
  const rows = [];
  for (let d = 0; d < 16; d++) { const i = S.bank * 16 + d; if (kitKey(i)) rows.push(i); }
  const tools = h('div', { class: 'row', style: 'margin-top:10px' },
    h('button', { class: 'btn', 'data-faq': 'random', onclick: (e) => { if (faqTap('random', e)) return; if (!edit(() => T.randomDrums(S.bank), 'random drums')) toast(`Bank ${S.bank + 1} is empty.`); } }, 'Random drums'),
    h('button', { class: 'btn r', 'data-faq': 'clear-all-notes', onclick: (e) => { if (faqTap('clear-all-notes', e)) return; edit(() => T.clearNotes(), 'clear notes'); sayUndo(`Pitches and roll notes cleared from loop ${LETTERS[M.cur]}.`); } }, 'Clear notes'));
  const barsRow = pages > 1 ? h('div', { class: 'bars' }, Array.from({ length: pages }, (_, b) => h('button', { class: b === S.page ? 'on' : '', 'data-faq': 'page-' + b,
    onclick: (e) => { if (faqTap('page-*', e)) return; S.page = b; S.follow = !S.playing; UI.paint(); } }, 'Bar ' + (b + 1)))) : null;
  const grid = h('div', { class: 'grid', id: 'grid', style: 'grid-template-columns:minmax(64px,96px) repeat(16,minmax(0,1fr))' });
  for (const i of rows) {
    const k = kitKey(i), pc = padCol(k), dots = notesAt(P, k);
    const ch = (P.chance && P.chance[k]) || '';
    grid.append(h('div', { class: 'nm' + (M.sel === i ? ' sel' : ''), style: `--pc:${pc}`, 'data-faq': 'steps-name-' + i, 'data-pad': i }, String(i + 1), ' ', titleOf(k)));
    for (let c = 0; c < 16; c++) {
      const j = S.page * 16 + c, on = stepOn(P, k, j), a = accOf(P, k, j);
      const chv = ch ? ch.split(',')[j] : '';
      grid.append(h('div', { class: 'c' + (c % 8 >= 4 ? ' q' : '') + (on ? ' on' : '') + (on && a === 'a' ? ' acc' : '') + (on && a === 'g' ? ' ghost' : '') + (dots.has(j) ? ' dot' : '') + (on && chv ? ' chance' : ''),
        style: `--pc:${pc}`, 'data-k': k, 'data-j': j, 'data-faq': 'steps-' + i }));
    }
  }
  const empty = rows.length ? null : h('p', { class: 'note' }, `Bank ${S.bank + 1} has no sounds yet. Put some on its pads first.`);
  fill(tab, head, bankRow('steps-bank-', false), tools, barsRow, grid, empty,
    rows.length ? h('p', { class: 'note' }, 'Tap a lit step for accent, then ghost, then off. Hold a step for its chance. Hold a name for row tools.') : null);
  wireGrid(grid);
  markPlayhead();
}
export function markPlayhead() {
  const g = $('#grid'); if (!g) return;
  for (const c of g.querySelectorAll('.now')) c.classList.remove('now');
  const { step, pat: pi, playing } = S.heard;
  if (!playing || pi !== M.cur || Math.floor(step / 16) !== S.page) return;
  for (const c of g.querySelectorAll(`[data-j="${step}"]`)) c.classList.add('now');
}
function wireGrid(grid) {
  let g = null, holdT = 0, nameT = 0;
  grid.addEventListener('pointerdown', (e) => {
    const nm = e.target.closest('.nm');
    if (nm) {
      if (faqTap(nm.dataset.faq, e)) return;
      const i = +nm.dataset.pad;
      nameT = setTimeout(() => { nameT = -1; openRowTools(i); }, 450);
      const up = () => { nm.removeEventListener('pointerup', up); if (nameT !== -1) { clearTimeout(nameT); T.setSel(i); M.sel = i; call('preview', kitKey(i), 0, false); UI.paint(); } };
      nm.addEventListener('pointerup', up);
      return;
    }
    const c = e.target.closest('.c'); if (!c) return;
    if (faqTap(c.dataset.faq, e)) return;
    e.preventDefault(); try { grid.setPointerCapture(e.pointerId); } catch {}
    const before = T.messJson();
    const k = c.dataset.k, j = +c.dataset.j;
    const st = T.toggleStep(k, j);
    g = { k, j, before, on: st >= 0, seen: new Set([j]), x0: e.clientX, y0: e.clientY };
    if (st === 1 && !S.playing) call('preview', k, 0, false);
    reread(); send(); paintCells(k);
    // hold a step (still) for the step sheet: the tap's own change is taken back first
    holdT = setTimeout(() => { if (!g || g.seen.size > 1) return; T.setMess(g.before); reread(); send(); const gk = g.k, gj = g.j; g = null; openStepSheet(gk, gj); }, 450);
  });
  grid.addEventListener('pointermove', (e) => {
    if (!g) return;
    if (Math.hypot(e.clientX - g.x0, e.clientY - g.y0) > 10) clearTimeout(holdT);
    const el = document.elementFromPoint(e.clientX, e.clientY);
    const c = el && el.closest && el.closest('#grid .c');
    if (!c || c.dataset.k !== g.k) return;
    const j = +c.dataset.j; if (g.seen.has(j)) return;
    g.seen.add(j); T.paintStep(g.k, j, g.on); reread(); send(); paintCells(g.k);
  });
  const end = () => {
    clearTimeout(holdT);
    if (!g) return;
    const after = T.messJson();
    if (after !== g.before) { U.undo.push(g.before); if (U.undo.length > 80) U.undo.shift(); U.redo = []; trail('steps ' + titleOf(g.k)); autosave(after); UI.top(); }
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

/* ----- the step sheet: strength, chance and loop condition ----- */

const CHANCES = [[100, 'Always'], [75, '75%'], [50, '50%'], [25, '25%'], [101, '1:2'], [102, '2:2'], [103, '1:4'], [104, '4:4']];
function openStepSheet(k, j) {
  const st = JSON.parse(T.stepJson(k, j));
  const again = () => openStepSheet(k, j);
  sheet(`Step ${j + 1} · ${titleOf(k)}`, 'stepsheet-done',
    h('div', { class: 'row', style: 'margin-bottom:10px' },
      h('button', { class: 'btn' + (st.on ? ' y' : ''), onclick: () => { edit(() => T.paintStep(k, j, !st.on), 'step'); again(); } }, st.on ? 'On' : 'Off')),
    h('div', { class: 'word' }, 'Strength'),
    pick([[0, 'Ghost'], [1, 'Normal'], [2, 'Accent']], st.strength, (v) => { edit(() => { if (!st.on) T.paintStep(k, j, true); T.setStrength(k, j, v); }, 'strength'); again(); }, { tag: 'step-strength' }),
    h('div', { class: 'word', style: 'margin-top:10px' }, 'Plays'),
    h('div', { class: 'list', 'data-faq': 'step-chance' }, CHANCES.map(([v, n]) => h('button', { class: 'btn' + (st.chance === v ? ' y' : ''),
      onclick: () => { edit(() => T.setChance(k, j, v), 'chance'); again(); } }, n + (v === 101 ? ' (first time round of 2)' : v === 102 ? ' (second of 2)' : v === 103 ? ' (first of 4)' : v === 104 ? ' (fourth of 4)' : '')))),
    Object.keys(st.locks).length ? h('div', { class: 'row', style: 'margin-top:10px' }, h('span', { class: 'note' }, Object.keys(st.locks).length + ' locked control(s) on this step.'),
      h('button', { class: 'btn r', onclick: () => { edit(() => { for (const t of Object.keys(st.locks)) T.setLock(k, +t, j, NaN); }, 'unlock step'); again(); } }, 'Unlock')) : null);
}

/* ----- row tools ----- */

function openRowTools(i) {
  const k = kitKey(i);
  const tool = (t, n, label) => h('button', { class: 'btn', 'data-faq': 'row-' + t, onclick: () => { edit(() => T.rowTool(k, t, n), 'row ' + t); toast(label); } }, label);
  sheet(`${i + 1} ${titleOf(k)} · row tools`, 'rowtools-close',
    h('div', { class: 'list' },
      tool('every', 2, 'Every 2nd step'), tool('every', 4, 'Every 4th step'), tool('every', 8, 'Every 8th step'),
      tool('repeat', 4, 'Repeat the first beat'), tool('repeat', 8, 'Repeat the first half bar'), tool('repeat', 16, 'Repeat the first bar'),
      tool('nudge', -1, 'Nudge left'), tool('nudge', 1, 'Nudge right'), tool('spread', 3, 'Spread 3 hits'), tool('spread', 5, 'Spread 5 hits'),
      h('button', { class: 'btn', onclick: () => { edit(() => T.toggleMute(k), 'mute'); closeSheet(); } }, (M.mutes || []).includes(k) ? 'Unmute' : 'Mute'),
      h('button', { class: 'btn', onclick: () => { edit(() => T.toggleSolo(k), 'solo'); closeSheet(); } }, (M.solos || []).includes(k) ? 'Unsolo' : 'Solo'),
      h('button', { class: 'btn r', onclick: () => { edit(() => T.rowTool(k, 'clear', 0), 'clear row'); closeSheet(); sayUndo('Row cleared.'); } }, 'Clear the row')));
}

/* ================= the Roll ================= */

const HI = 24, LO = -36;
function roll() {
  const P = pat();
  let pad = M.sel;
  if (!kitKey(pad)) { pad = M.kit.findIndex(Boolean); if (pad < 0) return h('p', { class: 'note' }, 'Put a sound on a pad first.'); }
  const k = kitKey(pad);
  const sc = new Set((T.stripNotes(M.scale, M.root) || '').split(',').filter(Boolean).map(Number));
  const top = Math.min(HI, 12 + S.rollOct * 12), bottom = Math.max(LO, top - 24);
  const pitches = []; for (let p = top; p >= bottom; p--) if (!M.fold || sc.has(p)) pitches.push(p);
  const notes = T.notesOf(k).split(',').filter(Boolean).map((x) => x.split('.').map(Number));
  const pages = P.len / 16; if (S.page >= pages) S.page = pages - 1;
  const col0 = S.page * 16;
  const padsRow = h('div', { class: 'row', style: 'margin:10px 0 6px' }, M.kit.slice(S.bank * 16, S.bank * 16 + 16).map((kk, d) => kk ? h('button', {
    class: 'chipbtn' + (S.bank * 16 + d === pad ? ' on' : ''), style: `--pc:${padCol(kk)}`, onclick: () => { T.setSel(S.bank * 16 + d); M.sel = S.bank * 16 + d; UI.paint(); } }, String(S.bank * 16 + d + 1)) : null));
  const tools = h('div', { class: 'rolltools' },
    h('span', { class: 'word' }, 'Length'), pick([[1, '1'], [2, '2'], [4, '4'], [8, '8']], S.rollLen, (v) => { S.rollLen = v; UI.paint(); }, { tag: 'roll-len' }),
    key('roll-octdown', '', { cap: 'Oct−', cls: 'mini', onclick: () => { S.rollOct = Math.max(-3, S.rollOct - 1); UI.paint(); } }),
    key('roll-octup', '', { cap: 'Oct+', cls: 'mini', onclick: () => { S.rollOct = Math.min(1, S.rollOct + 1); UI.paint(); } }),
    key('roll-fold', '', { cap: 'Fold', cls: 'mini', on: !!M.fold, onclick: () => edit(() => T.setFold(!M.fold), 'fold') }),
    h('button', { class: 'btn r', 'data-faq': 'roll-clear', onclick: () => { edit(() => { for (const n of notes) T.removeNote(k, n[0], n[1]); }, 'clear roll'); sayUndo('Roll notes cleared for this pad.'); } }, 'Clear'));
  const barsRow = pages > 1 ? h('div', { class: 'bars' }, Array.from({ length: pages }, (_, b) => h('button', { class: b === S.page ? 'on' : '', onclick: () => { S.page = b; S.follow = !S.playing; UI.paint(); } }, 'Bar ' + (b + 1)))) : null;
  const grid = h('div', { class: 'roll', id: 'rollgrid', style: `--rows:${pitches.length}`, 'data-faq': 'roll' });
  pitches.forEach((p, r) => {
    const pc = ((p % 12) + 12) % 12;
    grid.append(h('div', { class: 'rk' + ([1, 3, 6, 8, 10].includes(pc) ? ' b' : '') + (sc.has(p) ? ' in' : ''), style: `grid-row:${r + 1};--rc:${pcCol(((p - M.root) % 12 + 12) % 12)}`, 'data-p': p }, pc === 0 ? noteName(p) : ''));
    for (let c = 0; c < 16; c++) grid.append(h('div', { class: 'rc' + (c % 4 === 0 ? ' beat' : '') + (sc.has(p) ? ' in' : ''), style: `grid-row:${r + 1};grid-column:${c + 2}`, 'data-p': p, 'data-s': col0 + c }));
  });
  for (const [s, p, l] of notes) {
    const r = pitches.indexOf(p); if (r < 0) continue;
    const c0 = s - col0, c1 = Math.min(16, c0 + l); if (c1 <= 0 || c0 >= 16) continue;
    grid.append(h('div', { class: 'rn', style: `grid-row:${r + 1};grid-column:${Math.max(0, c0) + 2}/${c1 + 2};--pc:${padCol(k)}`, 'data-p': p, 'data-s': s }));
  }
  grid.addEventListener('pointerdown', (e) => {
    if (faqTap('roll', e)) return;
    const n = e.target.closest('.rn');
    if (n) { edit(() => T.removeNote(k, +n.dataset.s, +n.dataset.p), 'roll note off'); return; }
    const c = e.target.closest('.rc'); if (!c) return;
    const p = +c.dataset.p, s = +c.dataset.s;
    const ps = chordFor(p);
    edit(() => T.addNotes(k, s, ps.join(','), S.rollLen), 'roll note');
    if (!S.playing) call('down', 900, pad, ps.join(','), true, false, -1), setTimeout(() => call('up', 900), 220);
  });
  return h('div', {}, padsRow, tools, barsRow, h('div', { class: 'rollwrap' }, grid),
    h('p', { class: 'note' }, `${titleOf(k)} · tap to draw a note ${S.rollLen} step${S.rollLen > 1 ? 's' : ''} long, tap a note to take it out.${S.chord !== 'off' ? ' Chords are on, so a tap writes the chord.' : ''}`));
}
