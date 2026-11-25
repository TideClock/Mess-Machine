"""Write every icon as a 40 x 40 PNG (32 x 32 art, ink outline, white glow, clear background) into the app's
ui/src/commonMain/composeResources/drawable as ic_<name>.png. The app draws the colored tile behind it, so a look can recolor tiles.
Run from this folder: python3 export.py ../../ui/src/commonMain/composeResources/drawable"""
import sys, re, json
from icons import *
out = sys.argv[1]
names = {}
for name, (cat, f) in I.items():
    t = tile(f(), (0, 0, 0, 0))
    # tile() paints the tile in the given color; with a clear color only the glow and the art are left
    slug = 'ic_' + re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')
    t.save(f'{out}/{slug}.png')
    names[name] = (slug, cat)
json.dump(names, open('icon-names.json', 'w'), indent=1)
print(len(names), 'icons')
