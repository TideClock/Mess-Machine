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
# the .wasm files and the fonts too: an artifact will not take them as they are, and a host that serves through a
# function (Hubbub) caps one answer at a few MB, so each becomes wasm/<name>.<k>.json parts of at most 1.5 MB, and a
# small fetch shim in index.html answers the page's request for the file with the parts joined back together
PART = 1_500_000
big = sorted(f for f in os.listdir(page) if f.endswith('.wasm') or f.endswith('.ttf'))
os.makedirs(os.path.join(page, 'wasm'), exist_ok=True)
parts = {}
for w in big:
    t = base64.b64encode(open(os.path.join(page, w), 'rb').read()).decode()
    n = 0
    for k in range(0, len(t), PART):
        json.dump(t[k:k + PART], open(os.path.join(page, 'wasm', f'{w}.{n}.json'), 'w')); n += 1
    parts[w] = n
    os.remove(os.path.join(page, w))
shim = """<script>
(() => { const real = window.fetch.bind(window), packed = %s;
  window.fetch = async (u, o) => { const s = String(u && u.url || u), n = s.split('/').pop().split('?')[0];
    if (packed[n]) { const ps = await Promise.all([...Array(packed[n]).keys()].map(async k => (await real('wasm/' + n + '.' + k + '.json')).json()));
      const t = atob(ps.join('')), b = new Uint8Array(t.length); for (let i = 0; i < t.length; i++) b[i] = t.charCodeAt(i);
      return new Response(b, { headers: { 'Content-Type': n.endsWith('.wasm') ? 'application/wasm' : 'font/ttf' } }); }
    return real(u, o); }; })();
</script>
<script src="mm.js"></script>""" % json.dumps(parts)
ix = os.path.join(page, 'index.html'); h = open(ix).read()
if '<script src="mm.js"></script>' not in h: raise SystemExit('mm.js tag not found')
# the shim goes first, before the page's own script fetches the fonts
i = h.index('<script>')
h = h[:i] + shim.replace('<script src="mm.js"></script>', '') + h[i:]
open(ix, 'w').write(h)
print(len(packs), 'packs,', len(big), 'big files in', sum(parts.values()), 'parts')
