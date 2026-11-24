"""Build index.html from index2.src.html by filling the data placeholders."""
import json, os
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, 'index2.src.html')).read()
ram = open(os.path.join(here, 'ramblings.json')).read()
loops = json.load(open(os.path.join(here, 'loops-meta.json')))
# every data file must be there: a missing one used to turn a shelf silently empty (CODE-10)
crates = json.load(open(os.path.join(here, 'crates.json')))
tracks = json.load(open(os.path.join(here, 'qtracks.json')))
qlab = json.load(open(os.path.join(here, 'qlab.json')))
# data goes inside a <script>: never let it close the tag
js = lambda v: json.dumps(v).replace('</', '<\\/')
out = (src.replace('__RAMBLINGS__', ram.replace('</', '<\\/'))
          .replace('__LOOPS__', js(loops))
          .replace('__CRATES__', js(crates))
          .replace('__QTRACKS__', js(tracks))
          .replace('__QLAB__', js(qlab)))
for k in ('__RAMBLINGS__', '__LOOPS__', '__CRATES__', '__QTRACKS__', '__QLAB__'):
    if k in out: raise SystemExit('placeholder left: ' + k)
# the house, ready-made (NSD-44): tools/house-snapshot.js renders it from the last build; the script redraws it on load
snap_p = os.path.join(here, 'house.snapshot.html')
HOUSE = '<nav class="house" id="house" aria-label="Rooms of the house"></nav>'
if os.path.exists(snap_p):
    if HOUSE not in out: raise SystemExit('house container not found')
    out = out.replace(HOUSE, HOUSE.replace('></nav>', '>' + open(snap_p).read() + '</nav>'), 1)
# a whole document: publishing needs the doctype, and phones need the viewport (the page used to rely on claude.ai's wrapper)
out = ('<!DOCTYPE html>\n<html lang="en">\n<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n'
       + out + '\n</html>\n')
open(os.path.join(here, 'index.html'), 'w').write(out)
print('built', len(out)//1024, 'KB')

# the Brotherwife site: same build, flagged, with its own title
bw = out.replace('<title>Neat Shit and Dye</title>', '<title>Brotherwife</title>\n<script>window.NSD_SITE = "bw";</script>', 1)
if 'NSD_SITE' not in bw: raise SystemExit('bw flag not set')
open(os.path.join(here, 'bw.html'), 'w').write(bw)
print('built bw.html')

# refresh the house snapshot from this build when node and Playwright are around
if os.environ.get('NSD_SNAPSHOT') == '1':
    import subprocess
    subprocess.run(['node', os.path.join(here, 'tools', 'house-snapshot.js')], check=True)
