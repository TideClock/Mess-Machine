// AudioWorkletGlobalScope has no performance clock on some browsers; the engine's System.nanoTime needs one.
if (typeof globalThis.performance === 'undefined' || typeof globalThis.performance.now !== 'function') {
  globalThis.performance = { now: () => (typeof currentFrame !== 'undefined' ? currentFrame / sampleRate * 1000 : Date.now()), shim: true };
}
