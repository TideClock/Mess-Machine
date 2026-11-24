// The compiled engine against the JVM render of the same mess (test/Golden.java).
import { readFileSync } from 'node:fs';
const [sounds, golden] = process.argv.slice(2);
const E = await import('./out/engine.js');
E.init(48000);
for (const name of ['basement-kick', 'wet-clap', 'pot-lid-hat', 'bw-snare', 'tom-of-doom', 'bubble-pop', 'spring-doink', 'tape-wobble-chord']) {
  let b; try { b = readFileSync(`${sounds}/${name}.flac`); } catch { continue; }
  E.addFile(`samples/${name}.wav`, new Int8Array(b.buffer, b.byteOffset, b.length), 0, 0);
}
console.log('missing', E.setMess(E.demoMess()));
E.play(0);
const g = new Float32Array(readFileSync(golden).buffer.slice(0));
const L = new Float32Array(128), R = new Float32Array(128);
let maxd = 0, at = -1, energy = 0;
for (let b = 0; b < g.length / 256; b++) {
  E.render(L, R);
  for (let i = 0; i < 128; i++) {
    const j = (b * 128 + i) * 2;
    const d = Math.max(Math.abs(L[i] - g[j]), Math.abs(R[i] - g[j + 1]));
    energy += g[j] * g[j];
    if (d > maxd) { maxd = d; at = b * 128 + i; }
  }
}
console.log('golden energy', energy.toFixed(3), 'max diff', maxd.toExponential(3), 'at frame', at, 'errors', E.errors(), E.lastError());
if (energy < 1 || maxd > 1e-4 || E.errors() > 0) process.exit(1);
