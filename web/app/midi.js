// MIDI in (where the browser and host allow it: not inside claude.ai). Notes 36 to 51 play the bank on screen like
// a drum pad controller; every other note plays the picked pad's Padiano; pitch bend moves its pitch.
import { S, M, call, wake, toast, kitKey, trail, TG } from './core.js';
import { pianoDown, pianoUp } from './play.js';

export async function startMidi() {
  if (!navigator.requestMIDIAccess) return false;
  let access;
  try { access = await navigator.requestMIDIAccess(); } catch { return false; }
  const hook = (inp) => { inp.onmidimessage = onMsg; };
  access.inputs.forEach(hook);
  access.onstatechange = (e) => { if (e.port.type === 'input' && e.port.state === 'connected') { hook(e.port); toast(`MIDI: ${e.port.name} is in.`); } };
  if (access.inputs.size) toast(`MIDI: ${[...access.inputs.values()].map((i) => i.name).join(', ')}.`);
  return true;
}
const held = new Map();
function onMsg(e) {
  const [st, a, b] = e.data, type = st & 0xF0;
  wake();
  if (type === 0x90 && b > 0) {
    if (a >= 36 && a <= 51) { const pad = S.bank * 16 + (a - 36); if (!kitKey(pad)) return; const id = 2000 + a; held.set(a, { id }); call('down', id, pad, '0', false, false, b / 127); }
    else { const pad = M.sel, p = a - 60; if (!kitKey(pad)) return; pianoDown(pad, p); held.set(a, { pad, p }); }
    trail('midi ' + a);
  } else if (type === 0x80 || (type === 0x90 && b === 0)) {
    const h = held.get(a); if (!h) return; held.delete(a);
    if (h.id) call('up', h.id); else pianoUp(h.pad, h.p);
  } else if (type === 0xE0) {
    const v = ((b << 7) | a) - 8192;
    if (Math.abs(v) < 64) call('release', M.sel, TG.PITCH); else call('mod', M.sel, TG.PITCH, (v / 8192) * 2);
  }
}
