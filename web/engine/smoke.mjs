// Quick check that the compiled engine loads in plain JS and makes sound.
const E = await import('./out/engine.js');
E.init(48000);
const rate = 48000, n = rate * 2;
const tone = new Float32Array(rate / 4);
for (let i = 0; i < tone.length; i++) tone[i] = Math.sin(i * 2 * Math.PI * 110 / rate) * Math.exp(-i / 3000);
const mess = JSON.parse(E.demoMess());
for (const k of mess.kit || []) if (k) E.addSample(k, tone, rate, 0, 0);
console.log('missing', E.setMess(JSON.stringify(mess)));
E.play(0);
const L = new Float32Array(128), R = new Float32Array(128);
let sum = 0, peak = 0;
for (let b = 0; b < n / 128; b++) { E.render(L, R); for (let i = 0; i < 128; i++) { sum += L[i] * L[i]; peak = Math.max(peak, Math.abs(L[i])); } }
console.log('rms', Math.sqrt(sum / n).toFixed(5), 'peak', peak.toFixed(4), 'errors', E.errors(), E.lastError());
console.log('heard', E.heard(), 'scales', E.scales().slice(0, 80));
console.log('roundtrip', E.normalize(E.messJson()) === E.messJson());
if (E.errors() > 0 || peak === 0) process.exit(1);
