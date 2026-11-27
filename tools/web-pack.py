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
print(len(packs), 'packs')
