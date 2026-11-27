#!/usr/bin/env python3
"""Pack the web build's assets for a claude.ai artifact (docs/WEB-CMP.md): an artifact will not serve .flac, so the
sounds go in base64 JSON packs of about 2 MB and assets/index.json lists the packs instead of the files.
Usage: tools/web-pack.py <page folder>   (changes the folder in place)"""
import base64, json, os, sys
page = sys.argv[1]; A = os.path.join(page, 'assets')
names = json.load(open(os.path.join(A, 'index.json')))
packs, cur, size, n = [], {}, 0, 0
def flush():
    global cur, size, n
    if not cur: return
    name = f'packs/p{n}.json'; os.makedirs(os.path.join(A, 'packs'), exist_ok=True)
    json.dump(cur, open(os.path.join(A, name), 'w')); packs.append(name); cur, size, n = {}, 0, n + 1
for p in names:
    b = open(os.path.join(A, p), 'rb').read(); cur[p] = base64.b64encode(b).decode(); size += len(b)
    os.remove(os.path.join(A, p))
    if size > 2_000_000: flush()
flush()
json.dump({'packs': packs}, open(os.path.join(A, 'index.json'), 'w'))
for root, dirs, files in os.walk(A, topdown=False):
    if not os.listdir(root): os.rmdir(root)
# the .wasm files too: an artifact will not take them as they are, so each becomes wasm/<name>.json and a small
# fetch shim in index.html answers mm.js's request for the .wasm with the bytes from that file
wasms = [f for f in os.listdir(page) if f.endswith('.wasm')]
os.makedirs(os.path.join(page, 'wasm'), exist_ok=True)
for w in wasms:
    json.dump(base64.b64encode(open(os.path.join(page, w), 'rb').read()).decode(), open(os.path.join(page, 'wasm', w + '.json'), 'w'))
    os.remove(os.path.join(page, w))
shim = """<script>
(() => { const real = window.fetch.bind(window), packed = %s;
  window.fetch = async (u, o) => { const s = String(u && u.url || u), n = s.split('/').pop().split('?')[0];
    if (packed.includes(n)) { const t = await (await real('wasm/' + n + '.json')).json(); const b = Uint8Array.from(atob(t), c => c.charCodeAt(0));
      return new Response(b, { headers: { 'Content-Type': 'application/wasm' } }); }
    return real(u, o); }; })();
</script>
<script src="mm.js"></script>""" % json.dumps(wasms)
ix = os.path.join(page, 'index.html'); h = open(ix).read()
if '<script src="mm.js"></script>' not in h: raise SystemExit('mm.js tag not found')
open(ix, 'w').write(h.replace('<script src="mm.js"></script>', shim, 1))
print(len(packs), 'packs,', len(wasms), 'wasm')
