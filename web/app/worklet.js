// The Mess Machine's audio thread: the Android app's own engine (engine.js, compiled from its Java by TeaVM).
import './shim.js';
import * as E from './engine.js';

class MessEngine extends AudioWorkletProcessor {
  constructor() {
    super();
    E.init(sampleRate);
    this.blocks = 0; this.slow = 0; this.worst = 0;
    this.port.onmessage = (e) => this.msg(e.data);
    this.port.postMessage({ t: 'ready', rate: sampleRate });
  }
  msg(m) {
    try {
      if (m.t === 'call') { const r = E[m.f](...(m.a || [])); if (m.id) this.port.postMessage({ t: 'ret', id: m.id, r }); }
      else if (m.t === 'file') { const sec = E.addFile(m.key, new Int8Array(m.buf), m.bpm || 0, m.beats || 0); this.port.postMessage({ t: 'file', key: m.key, sec }); }
      else if (m.t === 'mess') E.setMess(m.json);
    } catch (err) { this.port.postMessage({ t: 'err', err: String(err && err.message || err), what: m.t + ':' + (m.f || '') }); }
  }
  process(inputs, outputs) {
    const o = outputs[0], L = o[0], R = o[1] || o[0];
    const a = Date.now();
    E.render(L, R);
    const d = Date.now() - a;
    if (d > this.worst) this.worst = d;
    if (d * sampleRate / 1000 > L.length) this.slow++;
    let ev = null;
    for (let k = 0; k < 32; k++) { const s = E.pollRec(); if (!s) break; (ev || (ev = [])).push(s); }
    if (ev) this.port.postMessage({ t: 'rec', ev });
    if (++this.blocks % 4 === 0) {
      const msg = { t: 'pos', h: E.heard(), c: E.countIn(), fx: E.fxActive() };
      if (this.blocks % 1024 === 0) { msg.slow = this.slow; msg.worst = this.worst; msg.err = E.errors(); msg.last = E.lastError(); msg.voices = E.voices(); this.worst = 0; }
      this.port.postMessage(msg);
    }
    return true;
  }
}
registerProcessor('mess-engine', MessEngine);
