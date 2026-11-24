#!/usr/bin/env bash
# Builds the web Mess Machine into web/app/dist: the page, the compiled engine, the app's icons, fonts and sounds.
# Artifacts cannot serve .flac, so each sound ships as base64 text (same bytes; the app's own decoder reads them).
set -euo pipefail
cd "$(dirname "$0")"
R=../..
rm -rf dist && mkdir -p dist/icons dist/fonts dist/snd
cp index.html mm.css *.js faq.json dist/
cp ../engine/out/engine.js dist/
# every icon the app has
cp $R/app/src/main/res/drawable-nodpi/ic_*.png dist/icons/
# Latin-only WOFF2 copies of the app's fonts (Rubik Distressed alone goes from 892 KB to about 200 KB)
command -v pyftsubset >/dev/null || pip install -q fonttools brotli
for f in $R/app/src/main/res/font/*.ttf; do
  pyftsubset "$f" --flavor=woff2 --layout-features='*' --output-file="dist/fonts/$(basename "${f%.ttf}").woff2" \
    --unicodes='U+0000-00FF,U+0131,U+0152-0153,U+02C6,U+02DA,U+02DC,U+2000-206F,U+20AC,U+2122,U+2190-21FF,U+2212,U+2215,U+25A0-25FF,U+2600-26FF,U+2713-2717'
done
cp $R/app/src/main/assets/sounds/library.json dist/
python3 - "$R/app/src/main/assets/sounds" dist/snd <<'PY'
# Sounds travel in chunks (one JSON of base64 FLAC per jar, hits and loops split by size) so a publish stays under
# the 256-file limit; snd/index.json maps each file to its chunk.
import sys, os, json, base64
src, out = sys.argv[1], sys.argv[2]
lib = json.load(open(os.path.join(src, 'library.json')))
chunks, index, cur = {}, {}, {}
for s in lib['sounds']:
    f = s['f']; c = s['c']; b = base64.b64encode(open(os.path.join(src, f), 'rb').read()).decode()
    n = 0
    while True:
        name = f'{c}-{n}'
        size = sum(len(v) for v in chunks.get(name, {}).values())
        if size + len(b) <= 2_500_000 or not chunks.get(name): break
        n += 1
    chunks.setdefault(name, {})[f] = b; index[f] = name
for name, d in chunks.items(): json.dump(d, open(os.path.join(out, name + '.json'), 'w'))
json.dump(index, open(os.path.join(out, 'index.json'), 'w'))
print(len(index), 'sounds in', len(chunks), 'chunks')
PY
du -sh dist; find dist -type f | wc -l
