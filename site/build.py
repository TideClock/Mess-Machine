"""Build index.html from index2.src.html by filling the data placeholders."""
import json, os
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, 'index2.src.html')).read()
ram = open(os.path.join(here, 'ramblings.json')).read()
loops = json.load(open('/home/claude/bw/loops/meta.json'))
crates_p = os.path.join(here, 'crates.json')
crates = json.load(open(crates_p)) if os.path.exists(crates_p) else []
tracks_p = os.path.join(here, 'qtracks.json')
tracks = json.load(open(tracks_p)) if os.path.exists(tracks_p) else []
qlab_p = os.path.join(here, 'qlab.json')
qlab = json.load(open(qlab_p)) if os.path.exists(qlab_p) else []
out = (src.replace('__RAMBLINGS__', ram)
          .replace('__LOOPS__', json.dumps(loops))
          .replace('__CRATES__', json.dumps(crates))
          .replace('__QTRACKS__', json.dumps(tracks))
          .replace('__QLAB__', json.dumps(qlab)))
for k in ('__RAMBLINGS__', '__LOOPS__', '__CRATES__', '__QTRACKS__', '__QLAB__'):
    assert k not in out, k
open(os.path.join(here, 'index.html'), 'w').write(out)
print('built', len(out)//1024, 'KB')

# the Brotherwife site: same build, flagged, with its own title
bw = out.replace('<title>Neat Shit and Dye</title>', '<title>Brotherwife</title>\n<script>window.NSD_SITE = "bw";</script>', 1)
assert 'NSD_SITE' in bw
open(os.path.join(here, 'bw.html'), 'w').write(bw)
print('built bw.html')
