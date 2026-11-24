#!/bin/bash
# Run before every publish of the site (NSD-29). Builds, then runs the checks that exit nonzero on failure.
#   ASSETS=/path/with/img  bash site/tools/precheck.sh
# ASSETS must hold the img/ folder from the live artifact (read it with Artifact read_file, paths=[...]).
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-/tmp/nsd-check}"
mkdir -p "$OUT/shots"
python3 "$HERE/build.py"
cp "$HERE/index.html" "$HERE/bw.html" "$OUT/"
[ -n "$ASSETS" ] && ln -sfn "$ASSETS/img" "$OUT/img"
cd "$OUT"
NSD_PAGE="$OUT/index.html" node "$HERE/tools/mm.js"
NSD_PAGE="$OUT/bw.html" node "$HERE/tools/mm.js"
NSD_PAGE="$OUT/index.html" node "$HERE/tools/walk.js"
NSD_PAGE="$OUT/index.html" node "$HERE/tools/run.js" > /dev/null
node -e "
const r = require('$OUT/results.json'); let bad = 0;
for (const [k, m] of Object.entries(r)) { const e = m.errors.filter(x => !/TUNNEL|ERR_FILE_NOT_FOUND|failed: .*\/(img|samples|audio|loops)\//.test(x)); if (e.length) { bad++; console.log('FAIL', k, e.join(' | ')); } }
console.log(bad ? bad + ' rooms with page errors' : 'PASS every room, phone and desktop, no page errors'); process.exit(bad ? 1 : 0);"
NSD_PAGE="$OUT/index.html" node "$HERE/tools/weight.js"
echo "precheck passed"
