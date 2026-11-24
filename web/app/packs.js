// Sound packs (SE-17, Packs.kt): zips on the GitHub release "packs" (pack.json plus FLAC clips), listed in packs.json.
// Inside claude.ai the page cannot reach GitHub; the Netlify copy passes them through its /packs function.
import { S, h, fill, sheet, closeSheet, toast, keepOwn, ensure, LIBS, store, UI, call } from './core.js';

const SAFE = /^[a-z0-9][a-z0-9-]{0,40}$/;
const base = () => (location.hostname.endsWith('netlify.app') || location.hostname === 'localhost' ? '/packs' : null);
async function zipLib() {
  if (!window.JSZip) await new Promise((res, rej) => { const s = document.createElement('script'); s.src = 'https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js'; s.onload = res; s.onerror = rej; document.head.append(s); });
  return window.JSZip;
}
export function registerPacks() {
  for (const p of store.get('packs', [])) {
    LIBS.CABS.push({ id: 'pack:' + p.id, name: p.name, col: '#FF7A1A' });
    for (const c of p.clips) LIBS.LIB[c.k] = { k: c.k, t: c.t, c: 'pack:' + p.id, f: '', ty: c.ty || 'chop', q: c.tags || [], bpm: c.bpm || 0, beats: c.beats || 0 };
  }
}
export async function openPacks() {
  const st = h('p', { class: 'note' }, 'Checking for packs…');
  const list = h('div', { class: 'list' });
  sheet('Packs', 'packs-close', st, list);
  const b = base();
  if (!b) { st.textContent = 'Packs come from GitHub, which this page inside claude.ai cannot reach. Open the Netlify copy (mess-machine.netlify.app) to install them; they stay in that browser.'; return; }
  let offers = [];
  try { const r = await fetch(b); if (r.status === 404) { st.textContent = 'No packs have been sent yet.'; return; } offers = await r.json(); }
  catch (e) { st.textContent = 'Could not reach the packs: ' + e.message; return; }
  const have = new Map(store.get('packs', []).map((p) => [p.id, p.version]));
  st.textContent = offers.length ? 'Sounds picked in the Vault, sent here as jars.' : 'No packs yet.';
  fill(list, offers.filter((o) => SAFE.test(o.id)).map((o) => h('div', { class: 'it' },
    h('div', { class: 't' }, o.name, h('small', {}, `${o.clips || '?'} sounds · ${o.blurb || ''}`)),
    h('button', { class: 'btn y', onclick: (e) => install(o, e.currentTarget) }, have.has(o.id) ? (have.get(o.id) < (o.version || 1) ? 'Update' : 'Again') : 'Install'))));
}
async function install(o, btn) {
  btn.textContent = 'Getting…';
  try {
    const JSZip = await zipLib();
    const r = await fetch(base() + '?f=' + encodeURIComponent(o.file)); if (!r.ok) throw new Error('GitHub said ' + r.status);
    const zip = await JSZip.loadAsync(await r.arrayBuffer());
    const meta = JSON.parse(await zip.file('pack.json').async('string'));
    const id = meta.id || o.id; if (!SAFE.test(id)) throw new Error('bad pack id');
    const clips = [];
    for (const c of meta.clips || []) {
      const f = zip.file(c.file); if (!f) continue;
      const key = `pack:${id}:${c.id || c.file.replace(/^.*\//, '').replace(/\.[^.]+$/, '')}`;
      await keepOwn(key, await f.async('uint8array'));
      clips.push({ k: key, t: c.title || key, ty: c.type, tags: c.tags, bpm: c.bpm, beats: c.beats });
    }
    const packs = store.get('packs', []).filter((p) => p.id !== id);
    packs.push({ id, name: meta.name || id, version: meta.version || 1, clips }); store.set('packs', packs);
    LIBS.CABS = LIBS.CABS.filter((c) => c.id !== 'pack:' + id); registerPacks();
    btn.textContent = 'Installed';
    toast(`${meta.name || id}: ${clips.length} sounds, in Sounds as a jar.`);
    UI.paint();
  } catch (e) { btn.textContent = 'Try again'; toast('Pack not installed: ' + e.message); }
}
